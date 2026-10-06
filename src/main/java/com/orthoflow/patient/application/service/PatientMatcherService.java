package com.orthoflow.patient.application.service;

import com.orthoflow.patient.application.port.PatientMatcher;
import com.orthoflow.patient.infrastructure.adapter.query.PatientDirectoryQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PatientMatcherService implements PatientMatcher {

    private final PatientDirectoryQuery query;

    @Override
    @Transactional(readOnly = true)
    public List<Candidate> candidates(UUID practiceId, String firstName, String lastName, LocalDate dateOfBirth, String phone, String cin) {
        return query.candidates(practiceId, firstName, lastName, dateOfBirth, phone, cin);
    }
}
