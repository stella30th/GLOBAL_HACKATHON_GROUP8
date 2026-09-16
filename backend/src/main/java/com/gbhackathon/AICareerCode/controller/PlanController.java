package com.gbhackathon.AICareerCode.controller;

import com.gbhackathon.AICareerCode.dto.plan.LearningPlanDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.service.LearningSnapshotService;
import com.gbhackathon.AICareerCode.service.ProfileService;
import com.gbhackathon.AICareerCode.service.ai.AiInvalidResponseException;
import com.gbhackathon.AICareerCode.service.ai.AiUnavailableException;
import com.gbhackathon.AICareerCode.service.ai.GeminiClient;
import com.gbhackathon.AICareerCode.security.AdminTokenGuard;
import com.gbhackathon.AICareerCode.service.retrieval.ResourceRetrievalService;
import com.gbhackathon.AICareerCode.service.taxonomy.TaxonomyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.gbhackathon.AICareerCode.config.SessionIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The learning plan: reading the stored one, generating a new one, and recording progress.
 *
 * <p>Reading and generating are separate endpoints on purpose. Generation costs four model calls
 * and close to a minute; making a page load trigger it silently spent a per-day quota on people
 * who only wanted to look at what they already had.
 */
@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private static final Logger log = LoggerFactory.getLogger(PlanController.class);

    private final ProfileService profileService;
    private final LearningSnapshotService snapshotService;
    private final TaxonomyService taxonomyService;
    private final ResourceRetrievalService retrievalService;
    private final GeminiClient gemini;
    private final AdminTokenGuard tokenGuard;

    public PlanController(ProfileService profileService,
                          LearningSnapshotService snapshotService,
                          TaxonomyService taxonomyService,
                          ResourceRetrievalService retrievalService,
                          GeminiClient gemini,
                          AdminTokenGuard tokenGuard) {
        this.profileService = profileService;
        this.snapshotService = snapshotService;
        this.taxonomyService = taxonomyService;
        this.retrievalService = retrievalService;
        this.gemini = gemini;
        this.tokenGuard = tokenGuard;
    }

    /**
     * The stored plan for the current profile and goal.
     *
     * <p>Answers 200 with {@code {plan: null, ...}} rather than 404 when there is nothing stored:
     * "you have not generated one yet" is a normal state of this page, not an error, and the body
     * carries what the empty state needs to say - what inputs are still missing, and whether an
     * older plan exists that no longer matches the inputs.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getCurrentPlan(HttpServletRequest request) {
        UserProfile profile = profileService.getCurrentOrCreateProfile(SessionIdFilter.require(request));
        Optional<LearningPlanDto> plan = snapshotService.currentPlan(profile);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", plan.orElse(null));
        body.put("completedItems", profileService.readCompletedMilestones(profile));
        body.put("missingInputs", profileService.missingPlanInputs(profile));
        // Distinguishes "never generated" from "generated, then you changed something": the second
        // needs a different message, because the student remembers making one.
        body.put("supersededSnapshot", plan.isEmpty() && snapshotService.hasSupersededSnapshot(profile));
        return ResponseEntity.ok(body);
    }

    /**
     * Runs the pipeline and stores the result.
     *
     * <p>Every failure path returns an explanation and nothing else. There is no written-in-advance
     * plan behind this endpoint to fall back on, which is the point: content on this page is the
     * model's work grounded in retrieved data, or it is absent and the student is told why. A
     * failure also leaves any previously stored plan exactly where it was.
     *
     * <p>{@code force=true} means the student asked for a rebuild of something that already
     * exists. Without it, a second run for an unchanged profile and goal converges on the stored
     * plan - correct when two tabs generate at once, wrong when someone deliberately pressed
     * "generate again" and would have four model calls thrown away. It costs real quota, so the
     * caller sets it only on an explicit rebuild.
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generatePlan(HttpServletRequest request,
                                          @RequestParam(name = "force", defaultValue = "false") boolean force) {
        UserProfile profile = profileService.getCurrentOrCreateProfile(SessionIdFilter.require(request));

        List<String> missing = profileService.missingPlanInputs(profile);
        if (!missing.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Before a plan can be built, please provide " + String.join(", ", missing) + ".",
                    "missingInputs", missing));
        }

        try {
            LearningPlanDto plan = snapshotService.generate(profile, force);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("plan", plan);
            body.put("completedItems", List.of());
            return ResponseEntity.ok(body);
        } catch (AiUnavailableException e) {
            log.warn("Plan generation failed - no model answered: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "The AI service could not be reached, so no plan was generated. "
                            + "Nothing has been written in its place. Please try again.",
                    "detail", String.valueOf(e.getDetail()),
                    "retryable", true));
        } catch (AiInvalidResponseException e) {
            log.warn("Plan generation failed - model could not produce a valid plan: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "The AI returned a plan that did not meet the quality checks, and could "
                            + "not correct it. Nothing has been written in its place. Please try again.",
                    "detail", e.getMessage(),
                    "retryable", true));
        } catch (LearningSnapshotService.StaleProfileRevisionException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", e.getMessage(), "retryable", true));
        }
    }

    /**
     * Records that the student has, by their own judgement, finished a phase, an activity or a
     * completion criterion.
     *
     * <p>Deliberately not part of saving the profile: that moves the profile revision, which
     * discards the plan the item belongs to - a checkbox would have deleted the plan it was
     * ticking. This validates the item against the current plan, writes only the progress list,
     * and leaves {@code updatedAt} alone. No AI is called.
     */
    @PatchMapping("/progress/{itemId}")
    public ResponseEntity<?> updateProgress(HttpServletRequest httpRequest,
                                            @PathVariable("itemId") String itemId,
                                            @RequestBody ProgressRequest request) {
        if (request == null || request.getPlanId() == null || request.getPlanId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "planId is required."));
        }
        if (request.getCompleted() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "completed must be true or false."));
        }
        if (itemId == null || itemId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "itemId is required."));
        }

        UserProfile current = profileService.getCurrentOrCreateProfile(SessionIdFilter.require(httpRequest));
        try {
            UserProfile updated = snapshotService.updateProgress(
                    current.getId(), request.getPlanId(), itemId, request.getCompleted());
            return ResponseEntity.ok(Map.of(
                    "completedItems", profileService.readCompletedMilestones(updated),
                    "planId", request.getPlanId()));
        } catch (LearningSnapshotService.StalePlanException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (LearningSnapshotService.CheckableNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    public static class ProgressRequest {
        private String planId;
        /** Boxed so a missing field is distinguishable from an explicit false. */
        private Boolean completed;

        public String getPlanId() {
            return planId;
        }

        public void setPlanId(String planId) {
            this.planId = planId;
        }

        public Boolean getCompleted() {
            return completed;
        }

        public void setCompleted(Boolean completed) {
            this.completed = completed;
        }
    }

    /**
     * What reference data the system actually holds right now.
     *
     * <p>Exposed because the honest answer to "is your SFIA mapping real" is a number this endpoint
     * can produce, and because a missing SFIA file has to be visible rather than inferred from
     * skill codes that quietly stopped appearing.
     */
    @GetMapping("/data-status")
    public ResponseEntity<Map<String, Object>> getDataStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taxonomy", taxonomyService.status());
        body.put("retrieval", retrievalService.status());
        body.put("ai", gemini.status());
        return ResponseEntity.ok(body);
    }

    /**
     * Re-checks every catalogue URL. Slow by nature - one request per document - so it is manual
     * rather than part of start-up, where it would add a minute to every cold boot.
     */
    @PostMapping("/data-status/verify-urls")
    public ResponseEntity<Map<String, Object>> verifyUrls() {
        return ResponseEntity.ok(retrievalService.verifyAllUrls());
    }

    /**
     * Reloads the SFIA workbook (always re-fetching from {@code SFIA_SOURCE_URL} first when one
     * is configured, per {@link TaxonomyService#reloadSfia()}) and the bundled catalogues, all
     * without a restart.
     *
     * <p>This can replace the reference data every analysis on the site is built from, so it now
     * requires the same {@code X-Admin-Token} as the {@code /api/admin/**} endpoints - it was
     * previously reachable by anyone, which meant anyone could force a fetch against
     * {@code SFIA_SOURCE_URL} or overwrite a working taxonomy with whatever a broken source
     * returned. No new secret was introduced: it is the same {@code ADMIN_TOKEN} already used
     * for the admin upload and reload endpoints.
     */
    @PostMapping("/data-status/reload")
    public ResponseEntity<Map<String, Object>> reloadReferenceData(
            @RequestHeader(value = AdminTokenGuard.HEADER, required = false) String token) {
        String denial = tokenGuard.denyReason(token);
        if (denial != null) {
            return ResponseEntity.status(tokenGuard.isConfigured() ? HttpStatus.UNAUTHORIZED : HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", denial));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            int loaded = taxonomyService.reloadSfia();
            if (loaded < 0) {
                result.put("taxonomyError", "A SFIA refresh is already in progress; try again shortly.");
            } else {
                result.put("taxonomy", taxonomyService.status());
            }
        } catch (Exception e) {
            result.put("taxonomyError", e.getMessage());
        }
        try {
            retrievalService.loadCatalogue();
            retrievalService.rebuildIndex();
            result.put("retrieval", retrievalService.status());
        } catch (Exception e) {
            result.put("retrievalError", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }
}
