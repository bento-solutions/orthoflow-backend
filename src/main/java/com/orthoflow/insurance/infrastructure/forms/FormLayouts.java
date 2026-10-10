package com.orthoflow.insurance.infrastructure.forms;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The insurer forms OrthoFlow knows how to fill: every {@code insurance-forms/<code>/}
 * folder on the classpath holding a {@code layout.json} and the blank form it describes.
 * Adding an insurer's form is adding a folder; nothing here names one.
 */
@Component
public class FormLayouts {

    /** The form code that means "no insurer form: print OrthoFlow's statement of acts". */
    public static final String GENERIC = "generic";

    private static final String ROOT = "insurance-forms/";

    private final String root;
    private final Map<String, FormLayout> layouts = new LinkedHashMap<>();
    private final Map<String, byte[]> templates = new ConcurrentHashMap<>();

    public FormLayouts() {
        this(ROOT);
    }

    FormLayouts(String root) {
        this.root = root;
        ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            Resource[] found = new PathMatchingResourcePatternResolver().getResources("classpath*:" + root + "*/layout.json");
            List<FormLayout> read = new java.util.ArrayList<>();
            for (Resource r : found) {
                try (InputStream in = r.getInputStream()) {
                    read.add(json.readValue(in, FormLayout.class));
                }
            }
            read.sort(Comparator.comparing(FormLayout::name));
            read.forEach(l -> layouts.put(l.code(), l));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the insurance form layouts", e);
        }
    }

    public List<FormLayout> all() {
        return List.copyOf(layouts.values());
    }

    public Optional<FormLayout> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(layouts.get(code));
    }

    /**
     * The form an insurer's patients need: the clinic's choice when it made one,
     * otherwise the form that lists the insurer's code. Empty means the generic
     * statement of acts, which is also what an unknown override falls back to.
     */
    public Optional<FormLayout> forInsurer(String insurerCode, String override) {
        if (override != null && !override.isBlank()) {
            return GENERIC.equals(override) ? Optional.empty() : find(override);
        }
        if (insurerCode == null) {
            return Optional.empty();
        }
        String code = insurerCode.trim().toUpperCase(Locale.ROOT);
        return layouts.values().stream()
                .filter(l -> l.insurerCodes() != null && l.insurerCodes().contains(code))
                .findFirst();
    }

    /** The blank form, read once. */
    public byte[] template(FormLayout layout) {
        return templates.computeIfAbsent(layout.code(), code -> {
            String path = root + code + "/" + layout.template();
            try (InputStream in = FormLayouts.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("The blank form " + path + " is missing from the classpath");
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + path, e);
            }
        });
    }
}
