package com.example.cbumanage.user.service;

import com.example.cbumanage.application.repository.MemberApplicationRepository;
import com.example.cbumanage.email.service.EmailManager;
import com.example.cbumanage.email.service.EmailService;
import com.example.cbumanage.global.common.JwtProvider;
import com.example.cbumanage.global.error.BaseException;
import com.example.cbumanage.global.error.ErrorCode;
import com.example.cbumanage.global.setting.service.SystemSettingService;
import com.example.cbumanage.global.util.RedisUtil;
import com.example.cbumanage.user.dto.PasswordResetRequest;
import com.example.cbumanage.user.entity.User;
import com.example.cbumanage.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PasswordResetAttemptLimitTest {
    private static final String EMAIL = "user@example.com";
    private static final String CODE = "123456";
    private final UserRepository users = mock(UserRepository.class);
    private final RedisUtil redis = mock(RedisUtil.class);
    private final LoginService login = new LoginService(users, mock(JwtProvider.class), redis,
            mock(MemberApplicationRepository.class));
    private final User user = new User(EMAIL, 20240001L, "original-password");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(user, "userId", 7L);
        ReflectionTestUtils.setField(login, "salt", "test-salt");
        when(users.findByStudentNumberAndDeletedAtIsNull(user.getStudentNumber())).thenReturn(Optional.of(user));
    }

    @Test
    void correctCodeWithinLimitChangesPasswordAndConsumesCodeAndRefreshToken() {
        when(redis.increaseWithExpire("mail:verify:" + EMAIL, 600L)).thenReturn(10L);
        when(redis.getData(EMAIL)).thenReturn(CODE);

        login.resetPassword(request(CODE));

        assertThat(user.getPassword()).isNotEqualTo("original-password").isNotEqualTo("new-password");
        verify(redis).increaseWithExpire("mail:verify:" + EMAIL, 600L);
        verify(redis).deleteData(EMAIL);
        verify(redis).deleteData("refresh:7");
    }

    @Test
    void incorrectCodeCountsAttemptWithoutChangingPasswordOrConsumingData() {
        when(redis.increaseWithExpire("mail:verify:" + EMAIL, 600L)).thenReturn(1L);
        when(redis.getData(EMAIL)).thenReturn(CODE);

        assertUnauthorized("wrong-code");

        verify(redis).increaseWithExpire("mail:verify:" + EMAIL, 600L);
        assertRejectedWithoutMutation();
    }

    @Test
    void missingCodeStillCountsAttempt() {
        when(redis.increaseWithExpire("mail:verify:" + EMAIL, 600L)).thenReturn(1L);

        assertUnauthorized(CODE);

        verify(redis).increaseWithExpire("mail:verify:" + EMAIL, 600L);
        assertRejectedWithoutMutation();
    }

    @Test
    void correctCodeOverLimitIsRejectedBeforeReadingOrConsumingCode() {
        when(redis.increaseWithExpire("mail:verify:" + EMAIL, 600L)).thenReturn(11L);

        assertUnauthorized(CODE);

        verify(redis, never()).getData(anyString());
        assertRejectedWithoutMutation();
    }

    @Test
    void mailVerificationAndPasswordResetShareTheSameAttemptBudget() {
        EmailService emailService = new EmailService(mock(JavaMailSender.class), redis, users,
                mock(SystemSettingService.class), mock(EmailManager.class));
        AtomicLong attempts = new AtomicLong();
        when(redis.increaseWithExpire("mail:verify:" + EMAIL, 600L))
                .thenAnswer(invocation -> attempts.incrementAndGet());
        when(redis.getData(EMAIL)).thenReturn(CODE);

        emailService.validateAuthCode(EMAIL, "wrong-code");
        for (int i = 0; i < 9; i++) {
            assertUnauthorized("wrong-code");
        }
        clearInvocations(redis);

        assertUnauthorized(CODE);

        assertThat(attempts.get()).isEqualTo(11L);
        verify(redis, never()).getData(EMAIL);
        assertRejectedWithoutMutation();
    }

    @Test
    void invalidRequestAndWrongAccountEmailDoNotCountCodeGuesses() {
        assertThatThrownBy(() -> login.resetPassword(new PasswordResetRequest(null, EMAIL, CODE, "new-password")))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> login.resetPassword(new PasswordResetRequest(
                user.getStudentNumber(), "other@example.com", CODE, "new-password")))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(redis);
        assertThat(user.getPassword()).isEqualTo("original-password");
    }

    private PasswordResetRequest request(String code) {
        return new PasswordResetRequest(user.getStudentNumber(), EMAIL, code, "new-password");
    }

    private void assertUnauthorized(String code) {
        assertThatThrownBy(() -> login.resetPassword(request(code)))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    private void assertRejectedWithoutMutation() {
        assertThat(user.getPassword()).isEqualTo("original-password");
        verify(redis, never()).deleteData(anyString());
    }
}
