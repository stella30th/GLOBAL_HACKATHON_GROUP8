package com.gbhackathon.AICareerCode.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The one shared-secret check behind every operator action that writes reference data other
 * people's requests read from: replacing the SFIA workbook, and forcing a reload of it.
 *
 * <p>Pulled out of {@code AdminController} so {@code PlanController}'s
 * {@code POST /api/plan/data-status/reload} - previously unauthenticated, even though it can
 * trigger the same fetch-and-replace as the admin endpoints - can require the same
 * {@code ADMIN_TOKEN} without a second secret or a second auth system to configure and keep in
 * sync. One token, one header, checked the same way everywhere it is needed.
 *
 * <p>Unset means refuse everything, not fall open: a deployment that forgets to set
 * {@code ADMIN_TOKEN} gets a working product with these few endpoints disabled, rather than an
 * unlocked one nobody noticed.
 */
@Component
public class AdminTokenGuard {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenGuard.class);

    public static final String HEADER = "X-Admin-Token";

    @Value("${app.admin.token:}")
    private String adminToken;

    public boolean isConfigured() {
        return adminToken != null && !adminToken.isBlank();
    }

    /** @return true when {@code presented} is the configured token; always false if unconfigured */
    public boolean matches(String presented) {
        if (!isConfigured() || presented == null) {
            return false;
        }
        return constantTimeEquals(adminToken, presented);
    }

    /**
     * @return why the caller was refused, or null when {@code presented} may proceed
     */
    public String denyReason(String presented) {
        if (!isConfigured()) {
            return "Admin endpoints are disabled because ADMIN_TOKEN is not configured.";
        }
        if (!matches(presented)) {
            log.warn("Rejected an admin request with a missing or wrong token");
            return "A valid " + HEADER + " header is required.";
        }
        return null;
    }

    /**
     * Compares without leaking where the two strings first differ.
     *
     * <p>{@code String.equals} returns as soon as it finds a mismatch, and the timing difference
     * is measurable over enough requests. It is a small risk for a shared operator token, and it
     * costs four lines to remove.
     */
    private static boolean constantTimeEquals(String expected, String presented) {
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
