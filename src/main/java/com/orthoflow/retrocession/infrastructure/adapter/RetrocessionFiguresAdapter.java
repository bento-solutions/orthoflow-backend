package com.orthoflow.retrocession.infrastructure.adapter;

import com.orthoflow.finance.application.service.RetrocessionFigures;
import com.orthoflow.retrocession.application.service.RetrocessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** Answers the finance dashboard's "what do we owe collaborators" with the live simulation. */
@Component
@RequiredArgsConstructor
public class RetrocessionFiguresAdapter implements RetrocessionFigures {

    private final RetrocessionService retrocessions;

    @Override
    public BigDecimal owedFor(UUID practiceId, LocalDate from, LocalDate to) {
        return retrocessions.owed(practiceId, from, to);
    }

    @Override
    public Map<LocalDate, BigDecimal> accruedByDay(UUID practiceId, LocalDate from, LocalDate to) {
        return retrocessions.accruedByDay(practiceId, from, to);
    }
}
