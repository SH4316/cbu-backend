package com.example.cbumanage.post.service;

import com.example.cbumanage.comment.service.CommentService;
import com.example.cbumanage.freeboard.controller.PostFreeboardController;
import com.example.cbumanage.freeboard.entity.PostFreeboard;
import com.example.cbumanage.freeboard.repository.PostFreeboardRepository;
import com.example.cbumanage.freeboard.service.PostFreeboardService;
import com.example.cbumanage.global.error.GlobalExceptionHandler;
import com.example.cbumanage.group.repository.GroupMemberRepository;
import com.example.cbumanage.group.repository.GroupRepository;
import com.example.cbumanage.post.dto.PostDTO;
import com.example.cbumanage.post.entity.Post;
import com.example.cbumanage.post.repository.PostRepository;
import com.example.cbumanage.post.util.PostMapper;
import com.example.cbumanage.report.controller.PostReportController;
import com.example.cbumanage.report.entity.PostReport;
import com.example.cbumanage.report.repository.PostReportRepository;
import com.example.cbumanage.report.service.PostReportHWPService;
import com.example.cbumanage.report.service.PostReportService;
import com.example.cbumanage.reportmember.repository.ReportMemberRepository;
import com.example.cbumanage.user.entity.User;
import com.example.cbumanage.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class PostDetailSoftDeleteTest {
    private static final Long POST_ID = 1L;
    private static final Long USER_ID = 10L;
    private final PostRepository posts = mock(PostRepository.class);
    private final PostMapper mapper = mock(PostMapper.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final PostFreeboardRepository freeboards = mock(PostFreeboardRepository.class);
    private final PostReportRepository reports = mock(PostReportRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PostFreeboardService freeboardService = new PostFreeboardService(
            freeboards, posts, mock(PostService.class), mapper, entityManager);
    private final PostReportService reportService = new PostReportService(
            mock(PostService.class), entityManager, posts, reports, mapper, users,
            mock(GroupMemberRepository.class), mock(GroupRepository.class), mock(ReportMemberRepository.class));

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void activeFreeboardStillReturnsDetailAndIncrementsViewCount(boolean anonymous) {
        PostFreeboard freeboard = freeboard(anonymous, false);
        PostDTO.PostFreeboardResponse response;
        if (anonymous) {
            response = mock(PostDTO.PostFreeboardAnonymousInfoDTO.class);
            when(mapper.toPostFreeboardAnonymousInfoDTO(freeboard, USER_ID))
                    .thenReturn((PostDTO.PostFreeboardAnonymousInfoDTO) response);
        } else {
            response = mock(PostDTO.PostFreeboardInfoDTO.class);
            when(mapper.toPostFreeboardInfoDTO(freeboard)).thenReturn((PostDTO.PostFreeboardInfoDTO) response);
        }

        assertThat(freeboardService.getFreeBoard(POST_ID, USER_ID)).isSameAs(response);
        verify(posts).incrementViewCount(POST_ID);
        verify(entityManager).clear();
    }

    @Test
    void deletedFreeboardReturns404WithoutIncrementingOrMapping() throws Exception {
        freeboard(false, true);
        MockMvc mvc = standaloneSetup(new PostFreeboardController(freeboardService, mock(CommentService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/api/v1/freeboard/{postId}", POST_ID).principal(authentication()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(posts, mapper, entityManager);
    }

    @Test
    void activeReportStillReturnsDetailToAuthorAndIncrementsViewCount() {
        PostReport report = report(false);
        when(posts.findById(POST_ID)).thenReturn(Optional.of(report.getPost()));
        when(users.findById(USER_ID)).thenReturn(Optional.of(new User("author@example.com", 20240001L, "encoded")));
        PostDTO.PostReportViewDTO response = mock(PostDTO.PostReportViewDTO.class);
        when(mapper.toPostReportViewDTO(report.getPost(), report)).thenReturn(response);

        assertThat(reportService.getPostReportViewDTO(POST_ID, USER_ID)).isSameAs(response);
        verify(posts).incrementViewCount(POST_ID);
        verify(entityManager).clear();
    }

    @Test
    void deletedReportReturns404WithoutIncrementingOrMapping() throws Exception {
        report(true);
        MockMvc mvc = standaloneSetup(new PostReportController(reportService, mock(PostReportHWPService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/api/v1/report/{postId}", POST_ID).principal(authentication()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(posts, mapper, entityManager, users);
    }

    private PostFreeboard freeboard(boolean anonymous, boolean deleted) {
        Post post = Post.create(USER_ID, "title", "content", 8);
        if (deleted) post.delete();
        PostFreeboard freeboard = PostFreeboard.create(post, anonymous, null);
        when(freeboards.findByPostId(POST_ID)).thenReturn(Optional.of(freeboard));
        return freeboard;
    }

    private PostReport report(boolean deleted) {
        Post post = Post.create(USER_ID, "title", "content", 7);
        if (deleted) post.delete();
        PostReport report = PostReport.create(post, 2L, LocalDateTime.now(), "location", null, "reflection", "next");
        when(reports.findByPostId(POST_ID)).thenReturn(Optional.of(report));
        return report;
    }

    private UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken(USER_ID.toString(), "unused");
    }
}
