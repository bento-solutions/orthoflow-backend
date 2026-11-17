package com.orthoflow.auth.presentation.controller;

import com.orthoflow.auth.application.dto.RegisterRequest;
import com.orthoflow.auth.application.service.AuthService;
import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.common.exception.UnauthorizedException;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.common.tenancy.Practices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The first account on an empty database is the practice owner, an administrator, whatever role
 * the request names and whether or not registration is restricted (UI V2 audit L9: a local or demo
 * setup with registration open used to start with a doctor who could not reach Settings).
 */
class AuthControllerRegisterTest {

    private final AuthService authService = mock(AuthService.class);
    private final AuthController controller = new AuthController(authService, mock(CurrentUserProvider.class));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private RegisterRequest doctor() {
        RegisterRequest request = new RegisterRequest();
        request.setRole(UserRole.DOCTOR);
        return request;
    }

    @Test
    void firstAccountIsAdminWhenRegistrationIsOpen() {
        ReflectionTestUtils.setField(controller, "restrictRegistrationToBootstrap", false);
        when(authService.noUsersExist()).thenReturn(true);
        RegisterRequest request = doctor();

        controller.register(request);

        assertThat(request.getRole()).isEqualTo(UserRole.ADMIN);
        verify(authService).register(eq(request), eq(Practices.DEFAULT_ID));
    }

    @Test
    void firstAccountIsAdminWhenRegistrationIsRestricted() {
        ReflectionTestUtils.setField(controller, "restrictRegistrationToBootstrap", true);
        when(authService.noUsersExist()).thenReturn(true);
        RegisterRequest request = doctor();

        controller.register(request);

        assertThat(request.getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void laterAccountsKeepTheirRoleWhenRegistrationIsOpen() {
        ReflectionTestUtils.setField(controller, "restrictRegistrationToBootstrap", false);
        when(authService.noUsersExist()).thenReturn(false);
        RegisterRequest request = doctor();

        controller.register(request);

        assertThat(request.getRole()).isEqualTo(UserRole.DOCTOR);
    }

    @Test
    void laterAccountsNeedAnAdminWhenRegistrationIsRestricted() {
        ReflectionTestUtils.setField(controller, "restrictRegistrationToBootstrap", true);
        when(authService.noUsersExist()).thenReturn(false);

        assertThatThrownBy(() -> controller.register(doctor())).isInstanceOf(UnauthorizedException.class);
        verify(authService, never()).register(any(), any());
    }
}
