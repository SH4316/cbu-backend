package com.example.cbumanage.global.common;

import com.example.cbumanage.user.entity.MemberStatus;
import com.example.cbumanage.user.entity.Role;
import com.example.cbumanage.user.entity.User;
import com.example.cbumanage.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtFilterTest {
    private final JwtProvider jwt = mock(JwtProvider.class);
    private final UserRepository users = mock(UserRepository.class);
    private final JwtFilter filter = new JwtFilter(jwt, users);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final FilterChain chain = mock(FilterChain.class);
    private final User user = new User("user@example.com", 20240001L, "password");
    private final UUID uuid = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        ReflectionTestUtils.setField(user, "userId", 7L);
        request.setCookies(new Cookie("accessToken", "access-token"));
        Claims claims = mock(Claims.class);
        when(jwt.validateToken("access-token")).thenReturn(true);
        when(jwt.getClaims("access-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn(uuid.toString());
        // DB의 현재 role/status로 판정해야 하므로 이전 관리자 claim도 통과 근거가 아니다.
        when(claims.get("role", String.class)).thenReturn(Role.ROLE_ADMIN.name());
        when(users.findByUserUuidAndDeletedAtIsNull(uuid)).thenReturn(Optional.of(user));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(MemberStatus.class)
    void ordinaryMemberAuthenticationRequiresActiveStatus(MemberStatus status) throws Exception {
        user.changeRole(Role.ROLE_USER);
        ReflectionTestUtils.setField(user, "memberStatus", status);

        filter.doFilter(request, response, chain);

        if (status == MemberStatus.ACTIVE) {
            assertAuthenticated(Role.ROLE_USER);
        } else {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "ROLE_USER", mode = EnumSource.Mode.EXCLUDE)
    void staffKeepExistingStatusExceptionAndUseCurrentDatabaseRole(Role role) throws Exception {
        user.changeRole(role);
        ReflectionTestUtils.setField(user, "memberStatus", MemberStatus.INACTIVE);

        filter.doFilter(request, response, chain);

        assertAuthenticated(role);
        verify(chain).doFilter(request, response);
    }

    @Test
    void deletedOrMissingUserIsNotAuthenticated() throws Exception {
        when(users.findByUserUuidAndDeletedAtIsNull(uuid)).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    private void assertAuthenticated(Role role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo("7");
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly(role.name());
    }
}
