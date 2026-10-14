package com.example.cbumanage.project.service;

import com.example.cbumanage.group.entity.Group;
import com.example.cbumanage.group.entity.enums.GroupRecruitmentStatus;
import com.example.cbumanage.group.repository.GroupRepository;
import com.example.cbumanage.group.service.GroupService;
import com.example.cbumanage.post.dto.PostDTO;
import com.example.cbumanage.post.entity.Post;
import com.example.cbumanage.post.repository.PostRepository;
import com.example.cbumanage.post.service.PostService;
import com.example.cbumanage.post.util.PostMapper;
import com.example.cbumanage.project.entity.Project;
import com.example.cbumanage.project.entity.enums.ProjectFieldType;
import com.example.cbumanage.project.repository.ProjectRepository;
import com.example.cbumanage.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectServiceTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final PostRepository posts = mock(PostRepository.class);
    private final PostService postService = mock(PostService.class);
    private final GroupService groupService = mock(GroupService.class);
    private final PostMapper mapper = new PostMapper(null, null, null, null, null);
    private final ProjectService service = new ProjectService(projects, posts, mock(UserRepository.class),
            mock(GroupRepository.class), mapper, postService, groupService);
    private final Post post = Post.create(7L, "original title", "original content", 2);
    private final Group group = Group.create("original title", 1, 5, 10L, 2);
    private Project project;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void omittedRecruitingPassesValidationAndPreservesExistingRecruitment(boolean originalRecruiting) throws Exception {
        setUpProject(originalRecruiting);
        PostDTO.PostProjectUpdateRequestDTO request = readValidRequest("");

        service.updatePostProject(request, 10L, 7L);

        assertThat(project.getRecruiting()).isEqualTo(originalRecruiting);
        assertThat(group.getRecruitmentStatus()).isEqualTo(originalRecruiting
                ? GroupRecruitmentStatus.OPEN : GroupRecruitmentStatus.CLOSED);
        verify(groupService, never()).updateGroupRecruitment(anyLong(), anyLong(), any());
        assertThat(group.getGroupName()).isEqualTo("updated title");
        assertThat(post.getTitle()).isEqualTo("updated title");
        assertThat(post.getContent()).isEqualTo("updated content");
        assertThat(project.getRecruitmentFields()).containsExactly(ProjectFieldType.DESIGN);
    }

    @Test
    void explicitNullRecruitingAlsoPreservesExistingRecruitment() throws Exception {
        setUpProject(true);
        PostDTO.PostProjectUpdateRequestDTO request = readValidRequest(", \"recruiting\": null");

        service.updatePostProject(request, 10L, 7L);

        assertThat(project.getRecruiting()).isTrue();
        assertThat(group.getRecruitmentStatus()).isEqualTo(GroupRecruitmentStatus.OPEN);
        verify(groupService, never()).updateGroupRecruitment(anyLong(), anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void explicitRecruitingChangesProjectAndRequestsCorrespondingGroupStatus(boolean recruiting) throws Exception {
        setUpProject(!recruiting);
        PostDTO.PostProjectUpdateRequestDTO request = readValidRequest(", \"recruiting\": " + recruiting);

        service.updatePostProject(request, 10L, 7L);

        assertThat(project.getRecruiting()).isEqualTo(recruiting);
        verify(groupService).updateGroupRecruitment(20L, 7L,
                recruiting ? GroupRecruitmentStatus.OPEN : GroupRecruitmentStatus.CLOSED);
    }

    private void setUpProject(boolean recruiting) {
        ReflectionTestUtils.setField(group, "id", 20L);
        if (recruiting) {
            group.openRecruitment();
        }
        project = Project.create(post, List.of("BACKEND"), recruiting, null, group);
        when(posts.findById(10L)).thenReturn(Optional.of(post));
        when(projects.findByPostId(10L)).thenReturn(Optional.of(project));
        doAnswer(invocation -> {
            PostDTO.PostUpdateDTO dto = invocation.getArgument(0);
            post.changeTitle(dto.title());
            post.changeContent(dto.content());
            return null;
        }).when(postService).updatePost(any(PostDTO.PostUpdateDTO.class), same(post));
    }

    private PostDTO.PostProjectUpdateRequestDTO readValidRequest(String recruitingField) throws Exception {
        String json = """
                {"title": "updated title", "content": "updated content", "recruitmentFields": ["DESIGN"]%s}
                """.formatted(recruitingField);
        PostDTO.PostProjectUpdateRequestDTO request = new ObjectMapper().readValue(json,
                PostDTO.PostProjectUpdateRequestDTO.class);
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
        return request;
    }
}
