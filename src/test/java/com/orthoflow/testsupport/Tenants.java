package com.orthoflow.testsupport;

import com.orthoflow.common.tenancy.CurrentPractice;
import com.orthoflow.common.tenancy.Tenancy;
import jakarta.persistence.EntityManagerFactory;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;

/** Tenancy for unit tests, where no database session is ever open. */
public final class Tenants {

    private Tenants() {
    }

    public static Tenancy direct() {
        return new Tenancy(mock(EntityManagerFactory.class));
    }

    public static CurrentPractice fixed(UUID practiceId) {
        CurrentPractice current = mock(CurrentPractice.class);
        lenient().when(current.require()).thenReturn(practiceId);
        return current;
    }
}
