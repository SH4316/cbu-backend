package com.example.cbumanage.post.service;

import com.example.cbumanage.freeboard.repository.PostFreeboardRepository;
import com.example.cbumanage.global.error.BaseException;
import com.example.cbumanage.global.error.ErrorCode;
import com.example.cbumanage.group.entity.enums.GroupMemberStatus;
import com.example.cbumanage.group.repository.GroupMemberRepository;
import com.example.cbumanage.group.repository.GroupRepository;
import com.example.cbumanage.post.dto.PostDTO;
import com.example.cbumanage.post.entity.Post;
import com.example.cbumanage.post.repository.PostRepository;
import com.example.cbumanage.post.util.PostMapper;
import com.example.cbumanage.report.entity.PostReport;
import com.example.cbumanage.report.repository.PostReportRepository;
import com.example.cbumanage.user.entity.Role;
import com.example.cbumanage.user.entity.User;
import com.example.cbumanage.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PostServiceReportAccessTest {
    private static final Long USER_ID = 10L;
    private static final Long POST_ID = 100L;
    private static final Long GROUP_ID = 200L;
    private final PostRepository posts = mock(PostRepository.class);
    private final PostMapper mapper = mock(PostMapper.class);
    private final UserRepository users = mock(UserRepository.class);
    private final GroupMemberRepository members = mock(GroupMemberRepository.class);
    private final PostReportRepository reports = mock(PostReportRepository.class);
    private final PostService service = new PostService(posts, mapper, users,
            mock(PostFreeboardRepository.class), mock(GroupRepository.class), members, reports);

    private Post reportPost(Long authorId) {
        Post post = Post.create(authorId, "보고서", "비공개 본문", 7);
        ReflectionTestUtils.setField(post, "id", POST_ID);
        when(posts.findByIdAndIsDeletedFalse(POST_ID)).thenReturn(Optional.of(post));
        when(mapper.toPostInfoDTO(post)).thenReturn(dto(post));
        return post;
    }

    private PostDTO.PostInfoDTO dto(Post post) {
        return new PostDTO.PostInfoDTO(post.getId(), "작성자", 1L, post.getAuthorId(),
                post.getTitle(), post.getContent(), null, null);
    }

    private void user(Role role) {
        User user = mock(User.class);
        when(user.getRole()).thenReturn(role);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    private void membership(boolean active) {
        PostReport report = mock(PostReport.class);
        when(report.getGroupId()).thenReturn(GROUP_ID);
        when(reports.findByPostId(POST_ID)).thenReturn(Optional.of(report));
        when(members.existsActiveMember(USER_ID, GROUP_ID, GroupMemberStatus.ACTIVE)).thenReturn(active);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ROLE_ADMIN", "ROLE_PRESIDENT", "ROLE_VICE_PRESIDENT", "ROLE_SECRETARY"})
    void reportDetailAllowsEveryRoleThatCanViewAllReports(Role role) {
        Post post = reportPost(99L);
        user(role);

        assertThat(service.getPostById(POST_ID, USER_ID)).isEqualTo(dto(post));
        verifyNoInteractions(members);
    }

    @Test
    void reportDetailAllowsActiveGroupMember() {
        Post post = reportPost(99L);
        user(Role.ROLE_USER);
        membership(true);

        assertThat(service.getPostById(POST_ID, USER_ID)).isEqualTo(dto(post));
    }

    @Test
    void reportDetailAllowsAuthorWhoLeftTheGroup() {
        Post post = reportPost(USER_ID);
        user(Role.ROLE_USER);

        assertThat(service.getPostById(POST_ID, USER_ID)).isEqualTo(dto(post));
        verifyNoInteractions(members);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ROLE_USER", "ROLE_MANAGER"})
    void reportDetailDeniesNonmembersAndInactiveMembersWithApiForbidden(Role role) {
        reportPost(99L);
        user(role);
        membership(false);

        assertThatThrownBy(() -> service.getPostById(POST_ID, USER_ID))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(mapper, never()).toPostInfoDTO(any());
    }

    @Test
    void commonReportListUsesVisibilityBeforePagingAndPreservesTotal() {
        user(Role.ROLE_USER);
        Post post = reportPost(USER_ID);
        Pageable pageable = PageRequest.of(1, 2, Sort.by("createdAt").descending());
        when(posts.findVisibleReports(USER_ID, pageable)).thenReturn(new PageImpl<>(List.of(post), pageable, 3));

        Page<PostDTO.PostInfoDTO> result = service.getPostsByCategory(pageable, 7, USER_ID);

        assertThat(result.getContent()).containsExactly(dto(post));
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getNumber()).isEqualTo(1);
        verify(posts, never()).findByCategoryAndIsDeletedFalse(anyInt(), any());
    }

    @Test
    void commonReportListAllowsAllReportsForSecretary() {
        user(Role.ROLE_SECRETARY);
        Pageable pageable = PageRequest.of(0, 2);
        when(posts.findByCategoryAndIsDeletedFalse(7, pageable)).thenReturn(Page.empty(pageable));

        assertThat(service.getPostsByCategory(pageable, 7, USER_ID)).isEmpty();
        verify(posts).findByCategoryAndIsDeletedFalse(7, pageable);
        verify(posts, never()).findVisibleReports(anyLong(), any());
    }

    @Test
    void unrelatedCategoryKeepsCommonReadBehavior() {
        Post post = Post.create(99L, "일반 글", "본문", 6);
        when(posts.findByIdAndIsDeletedFalse(POST_ID)).thenReturn(Optional.of(post));
        when(mapper.toPostInfoDTO(post)).thenReturn(dto(post));
        Pageable pageable = PageRequest.of(0, 2);
        when(posts.findByCategoryAndIsDeletedFalse(6, pageable)).thenReturn(new PageImpl<>(List.of(post)));

        assertThat(service.getPostById(POST_ID, USER_ID)).isEqualTo(dto(post));
        assertThat(service.getPostsByCategory(pageable, 6, USER_ID).getContent()).containsExactly(dto(post));
        verifyNoInteractions(users, members, reports);
    }
}
