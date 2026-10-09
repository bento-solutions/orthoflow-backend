package com.orthoflow.treatment.presentation.controller;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.treatment.application.service.NgapNomenclature;
import com.orthoflow.treatment.application.service.NgapNomenclature.NgapAct;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The NGAP acts, read-only, for choosing a treatment's insurer code. Any signed-in user (GET /reference/**). */
@RestController
@RequestMapping("/reference/ngap-acts")
@RequiredArgsConstructor
public class NgapController {

    private final NgapNomenclature nomenclature;

    @GetMapping
    public List<NgapAct> search(@RequestParam(required = false) String q, @RequestParam(required = false) String chapter,
                                @RequestParam(defaultValue = "300") int limit) {
        return nomenclature.search(q, chapter, limit);
    }

    @GetMapping("/chapters")
    public List<String> chapters() {
        return nomenclature.chapters();
    }

    @GetMapping("/{code}")
    public NgapAct get(@PathVariable String code) {
        return nomenclature.find(code).orElseThrow(() -> new NotFoundException("NGAP act not found: " + code));
    }
}
