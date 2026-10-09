package com.orthoflow.billing.application.service;

import com.orthoflow.common.numbering.DocumentNumbers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class InvoiceNumberGenerator {

    private final DocumentNumbers documentNumbers;

    public String generate(String regionCode) {
        Long sequenceValue = documentNumbers.next("invoice");
        int year = LocalDate.now().getYear();
        return String.format("INV-%d-%s-%05d", year, regionCode, sequenceValue);
    }
}
