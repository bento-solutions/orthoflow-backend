package com.orthoflow.voice.infrastructure.provider;

import com.orthoflow.voice.infrastructure.nlu.VoiceNluProperties;
import com.orthoflow.voice.infrastructure.stt.SpeechToTextProperties;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractionProperties;
import com.orthoflow.voice.infrastructure.summary.VoiceSummaryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * At startup, says which outside vendors will receive patient audio or text
 * and have not been attested as protected.
 *
 * <p>Whether a vendor may train on what it is sent depends on the plan the key
 * belongs to, and nothing in the code can see that. A free-tier key is the
 * common trap: the chain works identically on it, so nothing fails, and the
 * terms differ invisibly. This makes the question show up in the log of every
 * deployment that routes health data to a vendor, until someone has read the
 * contract and set {@code orthoflow.voice.providers.<vendor>.data-protected}.
 */
@Component
@Slf4j
public class VoiceDataHandlingReport {

    private final VoiceProviderProperties vendors;
    private final SpeechToTextProperties stt;
    private final VoiceNluProperties nlu;
    private final VoiceSummaryProperties summary;
    private final ConsultationExtractionProperties consultation;

    public VoiceDataHandlingReport(VoiceProviderProperties vendors, SpeechToTextProperties stt,
                                   VoiceNluProperties nlu, VoiceSummaryProperties summary,
                                   ConsultationExtractionProperties consultation) {
        this.vendors = vendors;
        this.stt = stt;
        this.nlu = nlu;
        this.summary = summary;
        this.consultation = consultation;
    }

    private static Set<String> names(String primary, List<String> fallbacks) {
        Set<String> all = new LinkedHashSet<>();
        if (primary != null && !primary.isBlank()) all.add(primary.trim().toLowerCase());
        for (String name : fallbacks == null ? List.<String>of() : fallbacks) {
            if (name != null && !name.isBlank()) all.add(name.trim().toLowerCase());
        }
        return all;
    }

    /** Stage → vendors that stage would send patient data to, configured ones only. */
    Map<String, Set<String>> receivers() {
        Map<String, Set<String>> byStage = new LinkedHashMap<>();
        if (stt.isEnabled()) {
            byStage.put("audio transcription", names(stt.getProvider(), stt.getFallbacks()));
        }
        String nluPrimary = nlu.getNlu().getProvider();
        if (nluPrimary != null && !nluPrimary.isBlank() && !nluPrimary.equalsIgnoreCase("disabled")) {
            byStage.put("command interpretation", names(nluPrimary, nlu.getNlu().getFallbacks()));
        }
        if (summary.isEnabled()) {
            List<String> fallbackVendors = new ArrayList<>();
            for (String route : summary.getFallbacks() == null ? List.<String>of() : summary.getFallbacks()) {
                fallbackVendors.add(route.contains(":") ? route.substring(0, route.indexOf(':')) : route);
            }
            byStage.put("consultation summary", names(summary.getProvider(), fallbackVendors));
        }
        if (consultation.isEnabled() && consultation.getExtraction().isEnabled()) {
            // The whole conversation, not a command: the heaviest thing a vendor is sent.
            ConsultationExtractionProperties.Extraction extraction = consultation.getExtraction();
            List<String> fallbackVendors = new ArrayList<>();
            for (String route : extraction.getFallbacks() == null ? List.<String>of() : extraction.getFallbacks()) {
                fallbackVendors.add(route.contains(":") ? route.substring(0, route.indexOf(':')) : route);
            }
            byStage.put("consultation transcript reading", names(extraction.getProvider(), fallbackVendors));
        }
        byStage.replaceAll((stage, vendorNames) -> {
            Set<String> held = new TreeSet<>();
            for (String name : vendorNames) {
                VoiceProviderProperties.Vendor vendor = vendors.vendor(name);
                if (vendor != null && vendor.hasConfiguredKey()) held.add(name);
            }
            return held;
        });
        byStage.values().removeIf(Set::isEmpty);
        return byStage;
    }

    /** Vendors that would receive patient data and are not attested (or are outside the allowed regions), with the stages they serve. */
    Map<String, List<String>> unattested() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        receivers().forEach((stage, names) -> names.forEach(name -> {
            VoiceProviderProperties.Vendor vendor = vendors.vendor(name);
            if (vendor != null && (!vendor.isDataProtected() || vendor.isBlocked())) {
                result.computeIfAbsent(name, k -> new ArrayList<>()).add(stage);
            }
        }));
        return result;
    }

    @EventListener(ApplicationReadyEvent.class)
    void report() {
        unattested().forEach((vendor, stages) -> {
            if (vendors.isRequireDataProtection()) {
                log.error("'{}' has a key but is NOT used for {}: it is not attested as data-protected"
                                + " (orthoflow.voice.providers.{}.data-protected / VOICE_{}_DATA_PROTECTED) or its region '{}' is not in"
                                + " orthoflow.voice.allowed-regions {}. Patient data is never sent to it; the stage falls through to the next vendor"
                                + " or to the on-device fallback.",
                        vendor, String.join(", ", stages), vendor, vendor.toUpperCase(), vendors.vendor(vendor).getRegion(), vendors.getAllowedRegions());
            } else {
                log.warn("Patient data goes to '{}' ({}) and that vendor is not marked data-protected. Protection is not being enforced"
                                + " (VOICE_REQUIRE_DATA_PROTECTION=false: acceptable on a machine with no patient data, never in production)."
                                + " Confirm the key is on a plan that excludes training on submitted content and that a DPA is in place,"
                                + " then set orthoflow.voice.providers.{}.data-protected=true (VOICE_{}_DATA_PROTECTED).",
                        vendor, String.join(", ", stages), vendor, vendor.toUpperCase());
            }
        });
    }
}
