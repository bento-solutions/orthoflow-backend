package com.orthoflow.common.tenancy;

import org.hibernate.annotations.TenantId;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostLoadEvent;
import org.hibernate.event.spi.PostLoadEventListener;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

/**
 * Closes the one gap in Hibernate's {@code @TenantId}: it filters every query, but
 * not a load by primary key ({@code findById}, {@code getReferenceById}, a lazy
 * association). Every loaded entity whose {@code @TenantId} names another clinic
 * than the session's is refused with a {@link CrossClinicAccessException} (a 404),
 * so guessing another clinic's id finds nothing (ADR 0007).
 */
public class TenantLoadGuard implements PostLoadEventListener, Integrator {

    private static final Logger log = LoggerFactory.getLogger(TenantLoadGuard.class);

    private static final ClassValue<Optional<Field>> TENANT_FIELD = new ClassValue<>() {
        @Override
        protected Optional<Field> computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.isAnnotationPresent(TenantId.class)) {
                        f.setAccessible(true);
                        return Optional.of(f);
                    }
                }
            }
            return Optional.empty();
        }
    };

    @Override
    public void onPostLoad(PostLoadEvent event) {
        Object entity = event.getEntity();
        Optional<Field> field = TENANT_FIELD.get(entity.getClass());
        if (field.isEmpty()) {
            return;
        }
        Object session = event.getSession().getTenantIdentifierValue();
        if (TenantContext.ALL_CLINICS.equals(session)) {
            return;
        }
        Object owner;
        try {
            owner = field.get().get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        if (!(session instanceof UUID) || !session.equals(owner)) {
            log.warn("Refused to load {} {} of clinic {} in a session for clinic {}",
                    entity.getClass().getSimpleName(), event.getId(), owner, session);
            throw new CrossClinicAccessException(entity.getClass().getSimpleName());
        }
    }

    @Override
    public void integrate(Metadata metadata, BootstrapContext bootstrapContext, SessionFactoryImplementor sessionFactory) {
        sessionFactory.getServiceRegistry().getService(EventListenerRegistry.class)
                .appendListeners(EventType.POST_LOAD, this);
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {
    }
}
