package com.orthoflow.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import org.hibernate.annotations.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every entity is filtered to the signed-in user's clinic by Hibernate (ADR 0007), so
 * a new entity that forgets its {@code @TenantId} would be readable from every clinic.
 * The few that are not clinic data are listed here, each with the reason.
 */
class EveryEntityIsScopedToAClinicTest {

    private static final Map<String, String> NOT_CLINIC_DATA = Map.of(
            "User", "signing in finds an account by e-mail before its clinic is known; every user query names the clinic",
            "UserSession", "belongs to a user, read only by the user's own id",
            "PasswordResetToken", "found by its hash before anyone is signed in",
            "PracticeProfile", "is the clinic itself (the practices table), read by the signed-in user's clinic id",
            "HelpNote", "built-in notes are shared by every clinic (practice_id null); the service filters explicitly");

    @Test
    void everyEntityMapsItsClinicAsTheTenantId() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<String> unscoped = new ArrayList<>();
        int scoped = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents("com.orthoflow")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            if (NOT_CLINIC_DATA.containsKey(type.getSimpleName())) {
                continue;
            }
            Field tenant = tenantField(type);
            if (tenant == null || tenant.getType() != UUID.class
                    || !"practice_id".equals(tenant.getAnnotation(Column.class).name())) {
                unscoped.add(type.getName());
            } else {
                scoped++;
            }
        }
        assertThat(unscoped).as("entities without @TenantId practice_id").isEmpty();
        assertThat(scoped).isGreaterThan(60);
    }

    private static Field tenantField(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(TenantId.class)) {
                    return f;
                }
            }
        }
        return null;
    }
}
