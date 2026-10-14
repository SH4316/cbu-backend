package com.example.cbumanage.report.service;

import com.example.cbumanage.global.error.BaseException;
import com.example.cbumanage.global.error.ErrorCode;
import com.example.cbumanage.group.entity.Group;
import com.example.cbumanage.group.entity.enums.GroupMemberStatus;
import com.example.cbumanage.group.entity.enums.GroupStatus;
import com.example.cbumanage.group.repository.GroupMemberRepository;
import com.example.cbumanage.group.repository.GroupRepository;
import com.example.cbumanage.post.dto.PostDTO;
import com.example.cbumanage.post.entity.Post;
import com.example.cbumanage.post.repository.PostRepository;
import com.example.cbumanage.post.service.PostService;
import com.example.cbumanage.post.util.PostMapper;
import com.example.cbumanage.report.entity.PostReport;
import com.example.cbumanage.report.repository.PostReportRepository;
import com.example.cbumanage.reportmember.repository.ReportMemberRepository;
import com.example.cbumanage.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostReportAuthorMembershipTest {
    private static final Long GROUP_ID = 20L;
    private static final Long AUTHOR_ID = 10L;
    private static final Long PARTICIPANT_ID = 30L;
    private final PostService posts = mock(PostService.class);
    private final PostRepository postRepository = mock(PostRepository.class);
    private final PostReportRepository reports = mock(PostReportRepository.class);
    private final PostMapper mapper = mock(PostMapper.class);
    private final GroupRepository groups = mock(GroupRepository.class);
    private final GroupMemberRepository members = mock(GroupMemberRepository.class);
    private final ReportMemberRepository reportMembers = mock(ReportMemberRepository.class);
    private final PostReportService service = new PostReportService(posts, mock(EntityManager.class),
            postRepository, reports, mapper, mock(UserRepository.class), members, groups, reportMembers);
    private final PostDTO.PostReportCreateRequestDTO request = new PostDTO.PostReportCreateRequestDTO(
            "보고서", "본문", "활동 장소", LocalDateTime.of(2026, 10, 14, 12, 0), "image.jpg",
            7, GROUP_ID, List.of(PARTICIPANT_ID), "후기", "다음 활동");

    private void approvedGroup() {
        Group group = mock(Group.class);
        when(group.getStatus()).thenReturn(GroupStatus.ACTIVE);
        when(groups.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(groups.findByIdAndIsDeletedFalse(GROUP_ID)).thenReturn(Optional.of(group));
    }

    @Test
    void activeAuthorCanCreateReportEvenWhenOnlyAnotherMemberParticipated() {
        approvedGroup();
        when(members.existsByGroupIdAndUserUserIdAndGroupMemberStatus(GROUP_ID, AUTHOR_ID, GroupMemberStatus.ACTIVE)).thenReturn(true);
        when(members.existsByGroupIdAndUserUserIdAndGroupMemberStatus(GROUP_ID, PARTICIPANT_ID, GroupMemberStatus.ACTIVE)).thenReturn(true);
        PostDTO.PostCreateDTO postRequest = new PostDTO.PostCreateDTO(AUTHOR_ID, request.title(), request.content(), 7);
        when(mapper.toPostCreateDTO(request, AUTHOR_ID)).thenReturn(postRequest);
        Post post = Post.create(AUTHOR_ID, request.title(), request.content(), 7);
        ReflectionTestUtils.setField(post, "id", 100L);
        when(posts.createPost(postRequest)).thenReturn(post);
        PostDTO.ReportCreateDTO reportRequest = new PostDTO.ReportCreateDTO(100L, request.location(), request.reportImage(),
                request.date(), GROUP_ID, request.reflection(), request.nextPlan());
        when(mapper.toReportCreateDTO(request, 100L)).thenReturn(reportRequest);
        when(postRepository.findById(100L)).thenReturn(Optional.of(post));
        when(reports.save(any(PostReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createPostReport(request, AUTHOR_ID);

        verify(reports).save(any(PostReport.class));
        verify(reportMembers).save(argThat(member -> member.getUserId().equals(PARTICIPANT_ID)));
    }

    @Test
    void nonmemberCannotUseActiveParticipantsToCreateReportForAnotherGroup() {
        approvedGroup();
        when(members.existsByGroupIdAndUserUserIdAndGroupMemberStatus(eq(GROUP_ID), anyLong(), eq(GroupMemberStatus.ACTIVE)))
                .thenAnswer(invocation -> PARTICIPANT_ID.equals(invocation.getArgument(1)));

        assertDeniedBeforeWriting();
    }

    @ParameterizedTest
    @EnumSource(value = GroupMemberStatus.class, names = {"INACTIVE", "PENDING", "REJECTED"})
    void callerMembershipMustBeActive(GroupMemberStatus existingStatus) {
        approvedGroup();
        when(members.existsByGroupIdAndUserUserIdAndGroupMemberStatus(eq(GROUP_ID), eq(AUTHOR_ID), any(GroupMemberStatus.class)))
                .thenAnswer(invocation -> existingStatus == invocation.getArgument(2));

        assertDeniedBeforeWriting();
    }

    private void assertDeniedBeforeWriting() {
        assertThatThrownBy(() -> service.createPostReport(request, AUTHOR_ID))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(posts, reports, reportMembers);
    }
}
