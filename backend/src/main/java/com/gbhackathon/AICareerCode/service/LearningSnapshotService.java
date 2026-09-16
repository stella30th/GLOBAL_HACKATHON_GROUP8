package com.gbhackathon.AICareerCode.service;

import com.gbhackathon.AICareerCode.dto.plan.CareerGoalDto;
import com.gbhackathon.AICareerCode.dto.plan.LearningPlanDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.service.pipeline.LearningPlanPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Owns the stored plan and the self-reported progress on it.
 *
 * <p>Generation is explicit. An earlier design generated on read, which meant opening the page
 * spent four model calls and a minute of waiting whether or not anything had changed - and on a
 * per-day quota, a handful of page loads locked every later user out. Here a plan is produced only
 * when the student asks for one, and a read serves what is stored or reports that there is nothing
 * stored yet.
 *
 * <p>There is no in-memory cache in front of this. The stored snapshot is the cache: it survives a
 * restart, which is what the phase ids need in order to mean the same thing tomorrow.
 */
@Service
public class LearningSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(LearningSnapshotService.class);

    /**
     * Bumped when the stored shape or the pipeline contract changes in a way that makes an older
     * plan misleading. Version 1 was the pre-pipeline audit-and-roadmap snapshot; those are not
     * upgraded in place, because they were produced without taxonomy, retrieval or a graph and
     * presenting them as output of this pipeline would be a lie about where they came from.
     */
    public static final int SNAPSHOT_VERSION = 2;

    private final LearningSnapshotStore store;
    private final LearningPlanPipeline pipeline;
    private final ProfileService profileService;

    public LearningSnapshotService(LearningSnapshotStore store,
                                   LearningPlanPipeline pipeline,
                                   ProfileService profileService) {
        this.store = store;
        this.pipeline = pipeline;
        this.profileService = profileService;
    }

    /** Raised when the profile changed while a plan was being generated. Maps to HTTP 409. */
    public static class StaleProfileRevisionException extends RuntimeException {
        public StaleProfileRevisionException(String message) {
            super(message);
        }
    }

    /** Raised when a progress update names a plan that is no longer current. HTTP 409. */
    public static class StalePlanException extends RuntimeException {
        public StalePlanException(String message) {
            super(message);
        }
    }

    /** Raised when an item id is not part of the current plan. HTTP 404. */
    public static class CheckableNotFoundException extends RuntimeException {
        public CheckableNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * The stored plan for this profile and its current goal, if there is one.
     *
     * <p>Returns empty rather than generating. A caller that wants a plan built asks for it; a
     * caller that is only rendering the page gets an honest "nothing yet", which is what the empty
     * state on the roadmap panel is for.
     */
    public Optional<LearningPlanDto> currentPlan(UserProfile profile) {
        CareerGoalDto goal = profileService.goalOf(profile);
        if (goal == null) {
            return Optional.empty();
        }
        return store.read(profile, ProfileService.profileKey(profile), goal.key());
    }

    /**
     * Whether a snapshot is present but belongs to an older pipeline or a different goal.
     *
     * <p>Worth distinguishing from "no plan at all": the student may remember generating one, and
     * "your inputs changed, generate again" is a different message from "you have not made one yet".
     */
    public boolean hasSupersededSnapshot(UserProfile profile) {
        String raw = store.rawSnapshot(profile);
        return raw != null && !raw.isBlank() && currentPlan(profile).isEmpty();
    }

    /**
     * Generates a plan and stores it.
     *
     * <p>The pipeline runs outside any transaction - four model calls take tens of seconds and must
     * never hold a row lock - and the store then checks, under a lock, that the profile has not
     * moved underneath it.
     *
     * @throws com.gbhackathon.AICareerCode.service.ai.AiUnavailableException     if neither model answers
     * @throws com.gbhackathon.AICareerCode.service.ai.AiInvalidResponseException if the model could
     *         not produce a valid result even after correction
     */
    public LearningPlanDto generate(UserProfile profile, boolean force) {
        CareerGoalDto goal = profileService.goalOf(profile);
        if (goal == null) {
            throw new IllegalStateException("A career goal is required before a plan can be generated.");
        }
        String revision = ProfileService.profileKey(profile);
        String goalKey = goal.key();

        // Nothing is cleared before the pipeline runs. An earlier version invalidated the stored
        // snapshot first, so a pipeline failure destroyed a plan the student was working through -
        // and it was not even necessary: store.read() already refuses to serve a snapshot whose
        // revision or goal no longer matches, so an out-of-date row is invisible without being
        // deleted. Leaving it also means switching back to a previous goal finds the plan that was
        // built for it. A successful run overwrites it in store().

        LearningPlanDto plan = pipeline.generate(profile, goal);
        log.info("Storing a new plan for profile {} (revision {}, force={})",
                profile.getId(), revision, force);
        return store.store(profile.getId(), revision, goalKey, plan, force);
    }

    /** Ticks or unticks one phase, activity or completion criterion. */
    public UserProfile updateProgress(Long profileId, String planId, String itemId, boolean completed) {
        return store.updateProgress(profileId, planId, itemId, completed);
    }
}
