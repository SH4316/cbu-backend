package com.example.cbumanage.group.service;

import com.example.cbumanage.group.entity.Group;
import com.example.cbumanage.group.entity.GroupMember;
import com.example.cbumanage.group.entity.enums.GroupMemberRole;
import com.example.cbumanage.group.entity.enums.GroupMemberStatus;
import com.example.cbumanage.group.entity.enums.GroupRecruitmentStatus;
import com.example.cbumanage.group.entity.enums.GroupStatus;
import com.example.cbumanage.group.repository.GroupMemberRepository;
import com.example.cbumanage.group.repository.GroupRepository;
import com.example.cbumanage.group.util.GroupUtil;
import com.example.cbumanage.global.error.BaseException;
import com.example.cbumanage.global.error.ErrorCode;
import com.example.cbumanage.comment.repository.CommentRepository;
import com.example.cbumanage.project.entity.Project;
import com.example.cbumanage.project.repository.ProjectRepository;
import com.example.cbumanage.study.entity.Study;
import com.example.cbumanage.study.repository.StudyRepository;
import com.example.cbumanage.user.entity.User;
import com.example.cbumanage.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GroupServiceTest {

    private final GroupRepository groupRepository = mock(GroupRepository.class);
    private final GroupMemberRepository groupMemberRepository = mock(GroupMemberRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final StudyRepository studyRepository = mock(StudyRepository.class);

    private final GroupService groupService = new GroupService(
            groupRepository,
            groupMemberRepository,
            mock(UserRepository.class),
            mock(CommentRepository.class),
            projectRepository,
            studyRepository,
            mock(GroupUtil.class)
    );

    private static final Long GROUP_ID = 1L;
    private static final Long LEADER_ID = 10L;
    private static final Long MEMBER_ID = 20L;

    private static User user(String email) {
        return new User(email, 20240001L, "encoded");
    }

    private GroupMember leaderOf(Group group) {
        GroupMember leader = GroupMember.create(group, user("leader@example.com"),
                GroupMemberStatus.ACTIVE, GroupMemberRole.LEADER);
        when(groupMemberRepository.findByGroupIdAndUserUserId(GROUP_ID, LEADER_ID)).thenReturn(leader);
        return leader;
    }

    private Group group() {
        Group group = Group.create("테스트 그룹", 1, 5, 100L, 0);
        // 영속화되지 않은 엔티티는 id가 null이라 리더 조회가 빗나간다
        ReflectionTestUtils.setField(group, "id", GROUP_ID);
        when(groupRepository.findByIdAndIsDeletedFalse(GROUP_ID)).thenReturn(Optional.of(group));
        when(projectRepository.findByGroupId(GROUP_ID)).thenReturn(Optional.empty());
        when(studyRepository.findByGroupId(GROUP_ID)).thenReturn(Optional.empty());
        return group;
    }

    private GroupMember applicantIn(Group group, Long groupMemberId) {
        GroupMember applicant = GroupMember.create(group, user("member@example.com"),
                GroupMemberStatus.PENDING, GroupMemberRole.MEMBER);
        when(groupMemberRepository.findById(groupMemberId)).thenReturn(Optional.of(applicant));
        return applicant;
    }

    @Test
    void 활동중단으로_바꾸면_거절로_저장되지_않고_재신청_횟수도_오르지_않는다() {
        Group group = group();
        leaderOf(group);
        GroupMember applicant = applicantIn(group, 50L);

        groupService.updateStatusGroupMember(50L, LEADER_ID, GroupMemberStatus.INACTIVE, null);

        assertThat(applicant.getGroupMemberStatus()).isEqualTo(GroupMemberStatus.INACTIVE);
        assertThat(applicant.getRejectedCount()).isZero();
        assertThat(applicant.getMemberRejectReason()).isNull();
    }

    @Test
    void 거절로_바꿀_때만_재신청_횟수가_오른다() {
        Group group = group();
        leaderOf(group);
        GroupMember applicant = applicantIn(group, 51L);

        groupService.updateStatusGroupMember(51L, LEADER_ID, GroupMemberStatus.REJECTED, "이번엔 어려워요");

        assertThat(applicant.getGroupMemberStatus()).isEqualTo(GroupMemberStatus.REJECTED);
        assertThat(applicant.getRejectedCount()).isEqualTo(1);
    }

    @Test
    void 팀장은_자기_상태를_바꿀_수_없다() {
        Group group = group();
        GroupMember leader = leaderOf(group);
        when(groupMemberRepository.findById(60L)).thenReturn(Optional.of(leader));

        assertThatThrownBy(() -> groupService.updateStatusGroupMember(
                60L, LEADER_ID, GroupMemberStatus.REJECTED, "실수"))
                .isInstanceOf(BaseException.class)
                .extracting(e -> ((BaseException) e).getErrorCode())
                .isEqualTo(ErrorCode.GROUP_LEADER_STATUS_IMMUTABLE);

        assertThat(leader.getGroupMemberStatus()).isEqualTo(GroupMemberStatus.ACTIVE);
    }

    @Test
    void 신청_취소가_거절_이력을_지우지_않는다() {
        Group group = group();
        GroupMember applicant = GroupMember.create(group, user("member@example.com"),
                GroupMemberStatus.PENDING, GroupMemberRole.MEMBER);
        applicant.rejectByLeader("한 번 거절됨");
        applicant.pending(); // 재신청
        when(groupMemberRepository.findByGroupIdAndUserUserId(GROUP_ID, MEMBER_ID)).thenReturn(applicant);

        groupService.cancelApplication(GROUP_ID, MEMBER_ID);

        // row를 지우면 rejectedCount가 사라져 3회 제한이 취소 한 번으로 초기화된다
        verify(groupMemberRepository, never()).delete(any(GroupMember.class));
        assertThat(applicant.getRejectedCount()).isEqualTo(1);
    }

    @Test
    void 거절된_적_없는_신청_취소는_흔적을_남기지_않는다() {
        Group group = group();
        GroupMember applicant = GroupMember.create(group, user("member@example.com"),
                GroupMemberStatus.PENDING, GroupMemberRole.MEMBER);
        when(groupMemberRepository.findByGroupIdAndUserUserId(GROUP_ID, MEMBER_ID)).thenReturn(applicant);

        groupService.cancelApplication(GROUP_ID, MEMBER_ID);

        verify(groupMemberRepository).delete(applicant);
    }

    @Test
    void 재신청하면_지난_거절_사유가_남지_않는다() {
        Group group = group();
        GroupMember applicant = GroupMember.create(group, user("member@example.com"),
                GroupMemberStatus.PENDING, GroupMemberRole.MEMBER);
        applicant.rejectByLeader("지난번 거절 사유");

        applicant.pending();

        assertThat(applicant.getMemberRejectReason()).isNull();
        assertThat(applicant.getRejectedCount()).isEqualTo(1);
    }

    @Test
    void 정원에_도달하면_남은_신청자를_거절하되_거절_횟수는_늘리지_않는다() {
        Group group = group();
        group.openRecruitment();
        leaderOf(group);
        GroupMember accepted = applicantIn(group, 70L);
        GroupMember pending = GroupMember.create(group, user("pending@example.com"),
                GroupMemberStatus.PENDING, GroupMemberRole.MEMBER);
        GroupMember reapplied = GroupMember.create(group, user("reapplied@example.com"),
                GroupMemberStatus.PENDING, GroupMemberRole.MEMBER);
        reapplied.rejectByLeader("지난 거절");
        reapplied.pending();
        when(groupRepository.countByGroupIdAndStatus(GROUP_ID, GroupMemberStatus.ACTIVE)).thenReturn(4);
        when(groupMemberRepository.findByGroupIdAndGroupMemberStatus(GROUP_ID, GroupMemberStatus.PENDING))
                .thenReturn(List.of(pending, reapplied));
        Project project = mock(Project.class);
        Study study = mock(Study.class);
        when(projectRepository.findByGroupId(GROUP_ID)).thenReturn(Optional.of(project));
        when(studyRepository.findByGroupId(GROUP_ID)).thenReturn(Optional.of(study));

        groupService.updateStatusGroupMember(70L, LEADER_ID, GroupMemberStatus.ACTIVE, null);

        assertThat(accepted.getGroupMemberStatus()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(group.getRecruitmentStatus()).isEqualTo(GroupRecruitmentStatus.CLOSED);
        assertThat(group.getStatus()).isEqualTo(GroupStatus.PENDING);
        assertThat(pending.getGroupMemberStatus()).isEqualTo(GroupMemberStatus.REJECTED);
        assertThat(pending.getRejectedCount()).isZero();
        assertThat(pending.getMemberRejectReason()).isEqualTo("모집이 마감되어 자동으로 거절되었습니다.");
        assertThat(reapplied.getGroupMemberStatus()).isEqualTo(GroupMemberStatus.REJECTED);
        assertThat(reapplied.getRejectedCount()).isEqualTo(1);
        verify(project).updateRecruiting(false);
        verify(study).updateRecruiting(false);
    }

    @Test
    void 반려된_그룹이_정원에_도달하면_재심사_요청으로_전환된다() {
        Group group = group();
        group.reject("인원 보충 필요");
        group.openRecruitment();
        leaderOf(group);
        GroupMember accepted = applicantIn(group, 71L);
        when(groupRepository.countByGroupIdAndStatus(GROUP_ID, GroupMemberStatus.ACTIVE)).thenReturn(4);

        groupService.updateStatusGroupMember(71L, LEADER_ID, GroupMemberStatus.ACTIVE, null);

        assertThat(accepted.getGroupMemberStatus()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(group.getRecruitmentStatus()).isEqualTo(GroupRecruitmentStatus.CLOSED);
        assertThat(group.getStatus()).isEqualTo(GroupStatus.RESUBMITTED);
    }

    @Test
    void 정원_미만에서는_수락해도_마감하거나_재심사를_요청하지_않는다() {
        Group group = group();
        group.reject("인원 보충 필요");
        group.openRecruitment();
        leaderOf(group);
        GroupMember accepted = applicantIn(group, 72L);
        when(groupRepository.countByGroupIdAndStatus(GROUP_ID, GroupMemberStatus.ACTIVE)).thenReturn(3);

        groupService.updateStatusGroupMember(72L, LEADER_ID, GroupMemberStatus.ACTIVE, null);

        assertThat(accepted.getGroupMemberStatus()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(group.getRecruitmentStatus()).isEqualTo(GroupRecruitmentStatus.OPEN);
        assertThat(group.getStatus()).isEqualTo(GroupStatus.REJECTED);
        verify(groupMemberRepository, never()).findByGroupIdAndGroupMemberStatus(GROUP_ID, GroupMemberStatus.PENDING);
        verifyNoInteractions(projectRepository, studyRepository);
    }

    @Test
    void 이미_정원이_가득_차면_기존처럼_수락을_거부한다() {
        Group group = group();
        group.openRecruitment();
        leaderOf(group);
        GroupMember applicant = applicantIn(group, 73L);
        when(groupRepository.countByGroupIdAndStatus(GROUP_ID, GroupMemberStatus.ACTIVE)).thenReturn(5);

        assertThatThrownBy(() -> groupService.updateStatusGroupMember(73L, LEADER_ID, GroupMemberStatus.ACTIVE, null))
                .isInstanceOf(BaseException.class)
                .extracting(e -> ((BaseException) e).getErrorCode())
                .isEqualTo(ErrorCode.GROUP_CAPACITY_EXCEEDED);
        assertThat(applicant.getGroupMemberStatus()).isEqualTo(GroupMemberStatus.PENDING);
        assertThat(group.getRecruitmentStatus()).isEqualTo(GroupRecruitmentStatus.OPEN);
        verify(groupMemberRepository, never()).findByGroupIdAndGroupMemberStatus(GROUP_ID, GroupMemberStatus.PENDING);
    }

    @Test
    void 수동_마감도_동일하게_대기자를_정리하고_반려된_그룹을_재심사_요청한다() {
        Group group = group();
        group.reject("인원 보충 필요");
        group.openRecruitment();
        leaderOf(group);
        GroupMember pending = applicantIn(group, 74L);
        when(groupRepository.countByGroupIdAndStatus(GROUP_ID, GroupMemberStatus.ACTIVE)).thenReturn(2);
        when(groupMemberRepository.findByGroupIdAndGroupMemberStatus(GROUP_ID, GroupMemberStatus.PENDING))
                .thenReturn(List.of(pending));

        groupService.updateGroupRecruitment(GROUP_ID, LEADER_ID, GroupRecruitmentStatus.CLOSED);

        assertThat(group.getStatus()).isEqualTo(GroupStatus.RESUBMITTED);
        assertThat(group.getRecruitmentStatus()).isEqualTo(GroupRecruitmentStatus.CLOSED);
        assertThat(pending.getGroupMemberStatus()).isEqualTo(GroupMemberStatus.REJECTED);
        assertThat(pending.getRejectedCount()).isZero();
    }

}
