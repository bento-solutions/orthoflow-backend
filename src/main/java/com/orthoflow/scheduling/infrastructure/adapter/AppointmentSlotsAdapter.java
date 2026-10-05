package com.orthoflow.scheduling.infrastructure.adapter;

import com.orthoflow.lab.application.port.AppointmentSlots;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AppointmentSlotsAdapter implements AppointmentSlots {

    private final AppointmentJpaRepository appointments;

    @Override
    public Optional<Slot> find(UUID appointmentId) {
        return appointments.findById(appointmentId).map(a -> new Slot(a.getId(), a.getPracticeId(), a.getPatientId(), a.getDateTime()));
    }
}
