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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PostReportDeletedGroupTest {
    private static final Long GROUP_ID = 20L;
    private static final Long AUTHOR_ID = 10L;
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
            7, GROUP_ID, List.of(AUTHOR_ID), "후기", "다음 활동");

    private Group group(GroupStatus status) {
        Group group = Group.create("그룹", 1, 5, 100L, 1);
        ReflectionTestUtils.setField(group, "status", status);
        return group;
    }

    @Test
    void deletedApprovedGroupCannotReceiveNewReport() {
        Group group = group(GroupStatus.ACTIVE);
        group.delete();
        when(groups.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(groups.findByIdAndIsDeletedFalse(GROUP_ID)).thenReturn(Optional.empty());

        assertRejected(ErrorCode.GROUP_NOT_FOUND);
        verify(groups, never()).findById(GROUP_ID);
    }

    @Test
    void liveUnapprovedGroupStillUsesGroupNotApprovedError() {
        when(groups.findByIdAndIsDeletedFalse(GROUP_ID)).thenReturn(Optional.of(group(GroupStatus.PENDING)));

        assertRejected(ErrorCode.GROUP_NOT_APPROVED);
    }

    @Test
    void liveApprovedGroupStillCreatesReport() {
        when(groups.findByIdAndIsDeletedFalse(GROUP_ID)).thenReturn(Optional.of(group(GroupStatus.ACTIVE)));
        when(members.existsByGroupIdAndUserUserIdAndGroupMemberStatus(GROUP_ID, AUTHOR_ID, GroupMemberStatus.ACTIVE)).thenReturn(true);
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

        verify(reports).save(argThat(report -> report.getGroupId().equals(GROUP_ID)));
        verify(reportMembers).save(argThat(member -> member.getUserId().equals(AUTHOR_ID)));
    }

    private void assertRejected(ErrorCode errorCode) {
        assertThatThrownBy(() -> service.createPostReport(request, AUTHOR_ID))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
        verifyNoInteractions(reports, reportMembers);
    }
}
