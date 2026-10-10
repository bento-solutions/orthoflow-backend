package com.orthoflow.insurance.infrastructure.forms;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What to print, named in the vocabulary every layout shares ("insured.fullName",
 * "relation.CHILD", a row's "teeth"...). A layout places only the names it has room
 * for, so one set of values fills any insurer's form.
 *
 * @param text   a value per field name; blank or missing fields are left empty on the form
 * @param checks the boxes to tick
 * @param rows   the acts, in order, each a value per column name
 */
public record FormValues(Map<String, String> text, Set<String> checks, List<Map<String, String>> rows) {

    public String text(String key) {
        String v = text.get(key);
        return v == null || v.isBlank() ? null : v;
    }

    public boolean checked(String key) {
        return checks.contains(key);
    }
}
