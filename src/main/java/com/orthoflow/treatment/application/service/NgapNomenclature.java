package com.orthoflow.treatment.application.service;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Moroccan NGAP acts a dental clinic writes on a care form (V57): national
 * reference data, the same for every clinic. A clinic links its own treatment
 * catalogue to it through the treatment's act code.
 */
@Service
public class NgapNomenclature {

    /** One act of the nomenclature. A null coefficient is one the text does not give. */
    public record NgapAct(String code, String keyLetter, BigDecimal coefficient, BigDecimal anesthesiaCoefficient,
                          String label, String chapter, String section, String notes, boolean priorAgreement,
                          String assimilatedTo) {

        /** What the care form shows: the key letter and the coefficient ("D 15"), or null without a coefficient. */
        public String cotation(BigDecimal coefficientOverride) {
            BigDecimal c = coefficientOverride != null ? coefficientOverride : coefficient;
            return c == null ? null : keyLetter + " " + c.stripTrailingZeros().toPlainString();
        }
    }

    private static final String COLUMNS = """
            SELECT code, key_letter, coefficient, anesthesia_coefficient, label, chapter, section, notes,
                   prior_agreement, assimilated_to FROM ngap_acts
            """;

    private static final RowMapper<NgapAct> ROW = (rs, i) -> new NgapAct(rs.getString("code"), rs.getString("key_letter"),
            rs.getBigDecimal("coefficient"), rs.getBigDecimal("anesthesia_coefficient"), rs.getString("label"),
            rs.getString("chapter"), rs.getString("section"), rs.getString("notes"), rs.getBoolean("prior_agreement"),
            rs.getString("assimilated_to"));

    private final NamedParameterJdbcTemplate jdbc;

    public NgapNomenclature(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String ACCENTED = "àâäáéèêëíîïóôöúùûüç";
    private static final String PLAIN = "aaaaeeeeiiiooouuuuc";

    /**
     * Acts whose code starts with, or whose label contains, the search, accents ignored
     * ("detartrage" finds "Détartrage"); in the text's order.
     */
    public List<NgapAct> search(String query, String chapter, int limit) {
        String q = query == null ? "" : plain(query.trim().toLowerCase(Locale.ROOT));
        return jdbc.query(COLUMNS + """
                        WHERE (:q = '' OR lower(code) LIKE :prefix
                               OR translate(lower(label), '%s', '%s') LIKE :contains)
                          AND (CAST(:chapter AS TEXT) IS NULL OR chapter = :chapter)
                        ORDER BY sort_order LIMIT :limit
                        """.formatted(ACCENTED, PLAIN),
                new MapSqlParameterSource().addValue("q", q).addValue("prefix", escape(q) + "%")
                        .addValue("contains", "%" + escape(q) + "%").addValue("chapter", chapter)
                        .addValue("limit", Math.min(Math.max(limit, 1), 300)), ROW);
    }

    public Optional<NgapAct> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(COLUMNS + " WHERE code = :code", Map.of("code", normalise(code)), ROW).stream().findFirst();
    }

    public Map<String, NgapAct> byCodes(Collection<String> codes) {
        List<String> wanted = codes.stream().filter(c -> c != null && !c.isBlank()).map(NgapNomenclature::normalise).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        return jdbc.query(COLUMNS + " WHERE code IN (:codes)", Map.of("codes", wanted), ROW).stream()
                .collect(Collectors.toMap(NgapAct::code, Function.identity()));
    }

    public List<String> chapters() {
        return jdbc.queryForList("SELECT chapter FROM ngap_acts GROUP BY chapter ORDER BY min(sort_order)", Map.of(), String.class);
    }

    /** Codes are stored upper-case ("d700" is D700). */
    public static String normalise(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String plain(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            int i = ACCENTED.indexOf(c);
            out.append(i < 0 ? c : PLAIN.charAt(i));
        }
        return out.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
