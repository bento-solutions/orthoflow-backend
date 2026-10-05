package com.orthoflow.finance.application.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What the clinic owes collaborating doctors for a period, supplied by the
 * retrocession module when it exists. The dashboard asks through this seam, and
 * shows zero until something answers — the equation is complete from day one.
 */
public interface RetrocessionFigures {

    BigDecimal owedFor(UUID practiceId, LocalDate from, LocalDate to);
}
