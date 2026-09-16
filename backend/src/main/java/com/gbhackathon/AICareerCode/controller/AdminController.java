package com.gbhackathon.AICareerCode.controller;

import com.gbhackathon.AICareerCode.security.AdminTokenGuard;
import com.gbhackathon.AICareerCode.service.taxonomy.SfiaTaxonomyLoader;
import com.gbhackathon.AICareerCode.service.taxonomy.TaxonomyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Operator endpoints, behind a shared token.
 *
 * <p>Separate from {@code PlanController} because the access rule is different. Everything on the
 * plan endpoints is scoped to the caller's own session and safe to expose; this one writes a file
 * the whole deployment reads, so an unauthenticated version would let anyone replace the reference
 * framework every analysis is built on. {@code POST /api/plan/data-status/reload} triggers the
 * same kind of change and is guarded by the same {@link AdminTokenGuard}, so there is one shared
 * secret behind every such action, not one per endpoint.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    /** Largest workbook accepted. The published SFIA file is a few megabytes. */
    private static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;

    private final AdminTokenGuard tokenGuard;
    private final SfiaTaxonomyLoader sfiaLoader;
    private final TaxonomyService taxonomyService;

    public AdminController(AdminTokenGuard tokenGuard, SfiaTaxonomyLoader sfiaLoader,
                           TaxonomyService taxonomyService) {
        this.tokenGuard = tokenGuard;
        this.sfiaLoader = sfiaLoader;
        this.taxonomyService = taxonomyService;
    }

    /**
     * Uploads a SFIA workbook and loads it immediately.
     *
     * <p>Validated before it replaces anything - see
     * {@link SfiaTaxonomyLoader#storeUploadedWorkbook(byte[])} - so a bad upload cannot destroy a
     * workbook that was already working. It deliberately does not touch
     * {@code SFIA_SOURCE_URL}: this method loads exactly the file just received, never a remote
     * fetch layered on top of it. The next reload - manual, or the next cold start when a source
     * is configured - is what may replace it with the remote copy.
     *
     * <p>Useful for a machine with a persistent filesystem, and for correcting a bad file without
     * a redeploy. It is not the durable path on an ephemeral container: the file is gone at the
     * next restart, redeploy, or cold start after the service sleeps, and {@code SFIA_SOURCE_URL}
     * is what survives one. The response says which of the two situations the caller is in rather
     * than leaving them to find out at 3am.
     */
    @PostMapping("/sfia/upload")
    public ResponseEntity<Map<String, Object>> uploadSfiaWorkbook(
            @RequestHeader(value = AdminTokenGuard.HEADER, required = false) String token,
            @RequestParam("file") MultipartFile file) {

        String denial = tokenGuard.denyReason(token);
        if (denial != null) {
            return ResponseEntity.status(tokenGuard.isConfigured() ? HttpStatus.UNAUTHORIZED : HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", denial));
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
            int loaded = sfiaLoader.storeUploadedWorkbook(file.getBytes());
            taxonomyService.loadExtensions();
            taxonomyService.rebuildIndex();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("skillsLoaded", loaded);
            body.put("taxonomy", taxonomyService.status());
            body.put("note", "Loaded, but stored only on this instance's own filesystem. Treat it as "
                    + "temporary: on a container with an ephemeral filesystem it can be lost at the "
                    + "next restart, redeploy, or cold start after the service sleeps. This is a "
                    + "dev/demo convenience, not a durable store for production - set "
                    + "SFIA_SOURCE_URL for that, and the next reload from it will replace this upload.");
            log.info("SFIA workbook uploaded through the admin endpoint; {} skills loaded", loaded);
            return ResponseEntity.ok(body);
        } catch (IllegalStateException busy) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", busy.getMessage()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage()));
        } catch (Exception e) {
            log.warn("Could not store the uploaded SFIA workbook: {}", e.toString());
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Could not store the file: " + e.getMessage()));
        }
    }

    /**
     * Re-reads the SFIA workbook, always fetching fresh from {@code SFIA_SOURCE_URL} first when
     * one is configured - see {@link TaxonomyService#reloadSfia()} - and refreshes the technology
     * extensions and the retrieval index the same way a start-up does.
     */
    @PostMapping("/sfia/reload")
    public ResponseEntity<Map<String, Object>> reloadSfia(
            @RequestHeader(value = AdminTokenGuard.HEADER, required = false) String token) {

        String denial = tokenGuard.denyReason(token);
        if (denial != null) {
            return ResponseEntity.status(tokenGuard.isConfigured() ? HttpStatus.UNAUTHORIZED : HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", denial));
        }

        int loaded = taxonomyService.reloadSfia();
        if (loaded < 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "A SFIA refresh is already in progress; try again shortly."));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillsLoaded", loaded);
        body.put("taxonomy", taxonomyService.status());
        return ResponseEntity.ok(body);
    }
}
