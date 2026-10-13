package com.orthoflow.audit.infrastructure;

import com.orthoflow.audit.application.service.AuditCategory;
import com.orthoflow.audit.application.service.AuditLog;
import com.orthoflow.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records, once a request has been answered, that a signed-in user touched a patient's record
 * (viewed it, changed it) or changed an administrative setting. One place instead of a call in
 * every controller, so a new endpoint under one of the listed areas is covered without anyone
 * remembering to.
 *
 * <p>What counts: a request under a patient-data area that names one record, either as a path
 * variable ({@code /patients/{id}}, {@code /prescriptions/{id}}, {@code .../{patientId}/...}) or
 * with a {@code patientId} query parameter; and any change made under a patient-data area. Lists
 * that name no record (the diary, the patient search) are not logged row by row. Stored: the route
 * <em>template</em>, never the query string or the response, so the log itself holds no health data.
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    /** Areas whose records are patient data. A request under one of them naming a record is audited. */
    static final List<String> PATIENT_DATA = List.of("/patients", "/files", "/prescriptions", "/insurance-forms", "/invoices",
            "/consultations", "/lab-orders", "/appointments", "/tax-documents", "/cheques", "/recalls", "/stock/treatments",
            "/stock/treatment-invoices", "/clinical", "/front-desk");

    /** Areas whose changes are administrative. */
    static final List<String> ADMIN = List.of("/settings", "/public-links", "/booking", "/practitioners", "/scheduling/chairs");

    private final AuditLog audit;

    public AuditInterceptor(AuditLog audit) {
        this.audit = audit;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String method = request.getMethod();
        if ("OPTIONS".equals(method) || !(SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof AuthenticatedUser)) {
            return;
        }
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            return;
        }
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        vars = vars == null ? Map.of() : vars;
        int status = response.getStatus();

        String area = areaOf(pattern, PATIENT_DATA);
        if (area != null) {
            UUID patientId = uuid(vars.get("patientId"));
            if (patientId == null) {
                patientId = uuid(request.getParameter("patientId"));
            }
            UUID recordId = uuid(vars.get("id"));
            if (patientId == null && "/patients".equals(area)) {
                patientId = recordId;
            }
            // A read must name a record; a change is recorded even when it names none (a new patient).
            if (!read || patientId != null || recordId != null) {
                audit.record(AuditLog.Event.builder().category(read ? AuditCategory.PHI_READ : AuditCategory.PHI_WRITE)
                        .action(read ? "READ" : "WRITE").patientId(patientId).targetType(area.substring(1)).targetId(recordId)
                        .method(method).route(pattern).status(status).build());
            }
            return;
        }
        if (!read && areaOf(pattern, ADMIN) != null) {
            audit.record(AuditLog.Event.builder().category(AuditCategory.ADMIN).action("CHANGE").method(method).route(pattern)
                    .targetId(uuid(vars.get("id"))).status(status).build());
        }
    }

    private static String areaOf(String pattern, List<String> areas) {
        for (String area : areas) {
            if (pattern.equals(area) || pattern.startsWith(area + "/")) {
                return area;
            }
        }
        return null;
    }

    private static UUID uuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
