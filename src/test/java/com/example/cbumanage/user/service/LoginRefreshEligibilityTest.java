package com.example.cbumanage.user.service;

import com.example.cbumanage.application.repository.MemberApplicationRepository;
import com.example.cbumanage.global.common.JwtProvider;
import com.example.cbumanage.global.common.TokenInfo;
import com.example.cbumanage.global.error.BaseException;
import com.example.cbumanage.global.error.ErrorCode;
import com.example.cbumanage.global.util.RedisUtil;
import com.example.cbumanage.user.entity.MemberStatus;
import com.example.cbumanage.user.entity.Role;
import com.example.cbumanage.user.entity.User;
import com.example.cbumanage.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LoginRefreshEligibilityTest {
    private final UserRepository users = mock(UserRepository.class);
    private final JwtProvider jwt = mock(JwtProvider.class);
    private final RedisUtil redis = mock(RedisUtil.class);
    private final LoginService login = new LoginService(users, jwt, redis, mock(MemberApplicationRepository.class));
    private final User user = new User("user@example.com", 20240001L, "password");
    private final UUID uuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final TokenInfo newTokens = new TokenInfo("new-access", "new-refresh");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(user, "userId", 7L);
        ReflectionTestUtils.setField(user, "userUuid", uuid);
        ReflectionTestUtils.setField(login, "refreshExpireTime", 864000000L);
        Claims claims = mock(Claims.class);
        when(jwt.validateToken("refresh-token")).thenReturn(true);
        when(jwt.getClaims("refresh-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn(uuid.toString());
        when(users.findByUserUuidAndDeletedAtIsNull(uuid)).thenReturn(Optional.of(user));
        when(redis.getData("refresh:7")).thenReturn("refresh-token");
        when(jwt.createToken(any(UUID.class), anyString(), any(Role.class))).thenReturn(newTokens);
    }

    @ParameterizedTest
    @EnumSource(MemberStatus.class)
    void ordinaryMemberRefreshRequiresActiveStatus(MemberStatus status) {
        user.changeRole(Role.ROLE_USER);
        ReflectionTestUtils.setField(user, "memberStatus", status);

        if (status == MemberStatus.ACTIVE) {
            assertRefreshSucceeds(Role.ROLE_USER);
        } else {
            assertThatThrownBy(() -> login.refresh("refresh-token"))
                    .isInstanceOfSatisfying(BaseException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_APPROVED));
            verify(jwt, never()).createToken(any(UUID.class), anyString(), any(Role.class));
            verifyNoInteractions(redis);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "ROLE_USER", mode = EnumSource.Mode.EXCLUDE)
    void staffKeepExistingStatusException(Role role) {
        user.changeRole(role);
        ReflectionTestUtils.setField(user, "memberStatus", MemberStatus.INACTIVE);

        assertRefreshSucceeds(role);
    }

    @Test
    void activeMemberStillNeedsStoredRefreshToken() {
        user.changeRole(Role.ROLE_USER);
        ReflectionTestUtils.setField(user, "memberStatus", MemberStatus.ACTIVE);
        when(redis.getData("refresh:7")).thenReturn("different-token");

        assertThatThrownBy(() -> login.refresh("refresh-token"))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(jwt, never()).createToken(any(UUID.class), anyString(), any(Role.class));
        verify(redis, never()).setDataExpire(anyString(), anyString(), anyLong());
    }

    private void assertRefreshSucceeds(Role role) {
        assertThat(login.refresh("refresh-token")).isSameAs(newTokens);
        verify(jwt).createToken(uuid, String.valueOf(user.getStudentNumber()), role);
        verify(redis).setDataExpire("refresh:7", "new-refresh", 864000L);
    }
}
