package com.gbhackathon.AICareerCode.controller;

import com.gbhackathon.AICareerCode.service.taxonomy.SfiaTaxonomyLoader;
import com.gbhackathon.AICareerCode.service.taxonomy.TaxonomyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Operator endpoints, behind a shared token.
 *
 * <p>Separate from {@code PlanController} because the access rule is different. Everything on the
 * plan endpoints is scoped to the caller's own session and safe to expose; this one writes a file
 * the whole deployment reads, so an unauthenticated version would let anyone replace the reference
 * framework every analysis is built on.
 *
 * <p>The token is a single shared secret compared in constant time. That is proportionate to what
 * is behind it - reference data, not user records - and it is deliberately not a login: when
 * {@code ADMIN_TOKEN} is unset the endpoints refuse everything rather than falling open.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private static final String TOKEN_HEADER = "X-Admin-Token";

    /** Largest workbook accepted. The published SFIA file is a few megabytes. */
    private static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;

    @Value("${app.admin.token:}")
    private String adminToken;

    private final SfiaTaxonomyLoader sfiaLoader;
    private final TaxonomyService taxonomyService;

    public AdminController(SfiaTaxonomyLoader sfiaLoader, TaxonomyService taxonomyService) {
        this.sfiaLoader = sfiaLoader;
        this.taxonomyService = taxonomyService;
    }

    /**
     * Uploads a SFIA workbook and loads it immediately.
     *
     * <p>Useful for a machine with a persistent filesystem, and for correcting a bad file without
     * a redeploy. It is not the durable path on an ephemeral container: the file is gone at the
     * next cold start, and {@code SFIA_SOURCE_URL} is what survives one. The response says which
     * of the two situations the caller is in rather than leaving them to find out at 3am.
     */
    @PostMapping("/sfia/upload")
    public ResponseEntity<Map<String, Object>> uploadSfiaWorkbook(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestParam("file") MultipartFile file) {

        ResponseEntity<Map<String, Object>> denial = checkToken(token);
        if (denial != null) {
            return denial;
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Choose a .xlsx file to upload."));
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "That file is larger than the " + (MAX_UPLOAD_BYTES / 1024 / 1024)
                            + " MB limit for a SFIA workbook."));
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "The SFIA framework is read from the .xlsx workbook, not " + name + "."));
        }

        try {
            Path stored = sfiaLoader.storeUploadedWorkbook(file.getBytes());
            int loaded = taxonomyService.reloadSfia();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("storedAt", stored.toAbsolutePath().toString());
            body.put("skillsLoaded", loaded);
            body.put("taxonomy", taxonomyService.status());
            body.put("note", loaded > 0
                    ? "Loaded. On a container with an ephemeral filesystem this is lost at the next "
                    + "cold start; set SFIA_SOURCE_URL for a copy that survives one."
                    : "The file was stored but no skills were read from it. See sfiaUnavailableReason.");
            log.info("SFIA workbook uploaded through the admin endpoint; {} skills loaded", loaded);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.warn("Could not store the uploaded SFIA workbook: {}", e.toString());
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Could not store the file: " + e.getMessage()));
        }
    }

    /** Re-reads the workbook from disk, or fetches it from the configured source if none is there. */
    @PostMapping("/sfia/reload")
    public ResponseEntity<Map<String, Object>> reloadSfia(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {

        ResponseEntity<Map<String, Object>> denial = checkToken(token);
        if (denial != null) {
            return denial;
        }

        int loaded = taxonomyService.reloadSfia();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillsLoaded", loaded);
        body.put("taxonomy", taxonomyService.status());
        return ResponseEntity.ok(body);
    }

    /**
     * @return a refusal to return, or null when the caller may proceed
     */
    private ResponseEntity<Map<String, Object>> checkToken(String presented) {
        if (adminToken == null || adminToken.isBlank()) {
            // Refusing rather than allowing: an unconfigured secret must not mean "no secret".
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Admin endpoints are disabled because ADMIN_TOKEN is not configured."));
        }
        if (presented == null || !constantTimeEquals(adminToken, presented)) {
            log.warn("Rejected an admin request with a missing or wrong token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "A valid " + TOKEN_HEADER + " header is required."));
        }
        return null;
    }

    /**
     * Compares without leaking where the two strings first differ.
     *
     * <p>{@code String.equals} returns as soon as it finds a mismatch, and the timing difference is
     * measurable over enough requests. It is a small risk for a shared operator token, and it costs
     * four lines to remove.
     */
    private static boolean constantTimeEquals(String expected, String presented) {
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = presented.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }
}
