package com.orthoflow.insurance.application;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "type de soins" boxes the dental forms share (soins, prothèse, orthodontie/ODF,
 * autres), worked out from the act's NGAP number: Titre III chapter VI article 5 is
 * orthopédie dento-faciale (D626-D641), chapter VII section 3 and chapter VIII are
 * prosthesis (D748-D816), the rest of the dental acts and the consultation letters are
 * care. The assimilations (ASD..) and anything uncoded are "autres".
 */
public enum CareType {
    SOINS, PROTHESE, ODF, AUTRES;

    private static final Pattern NGAP_D = Pattern.compile("^D(\\d{3})$");

    public static CareType of(String actCode) {
        if (actCode == null || actCode.isBlank()) {
            return AUTRES;
        }
        String code = actCode.trim().toUpperCase(Locale.ROOT);
        if (code.equals("C") || code.equals("V")) {
            return SOINS;
        }
        Matcher m = NGAP_D.matcher(code);
        if (!m.matches()) {
            return AUTRES;
        }
        int n = Integer.parseInt(m.group(1));
        if (n >= 626 && n <= 641) return ODF;
        if (n >= 748 && n <= 816) return PROTHESE;
        if (n >= 500 && n <= 747) return SOINS;
        return AUTRES;
    }

    /**
     * Whether a planned act goes on a prior-agreement request rather than waiting to be
     * reported once done: the NGAP says so for orthodontic treatment, and the insurers
     * (CNOPS, CNSS, RMA, AXA...) all ask it for prostheses as well.
     */
    public static boolean needsPriorAgreement(String actCode, boolean ngapPriorAgreement) {
        CareType type = of(actCode);
        return ngapPriorAgreement || type == ODF || type == PROTHESE;
    }
}
