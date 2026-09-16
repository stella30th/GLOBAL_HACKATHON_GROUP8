package com.gbhackathon.AICareerCode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbhackathon.AICareerCode.dto.plan.LearningPathDto;
import com.gbhackathon.AICareerCode.dto.plan.LearningPlanDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Every database read and write of the stored plan and the progress attached to it.
 *
 * <p>Kept as its own bean rather than as private methods on {@link LearningSnapshotService}
 * because each of these is a short transaction that takes a row lock, and a {@code @Transactional}
 * method called from inside the same bean bypasses the proxy and therefore runs with no
 * transaction and no lock at all.
 */
@Component
public class LearningSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(LearningSnapshotStore.class);

    private final UserProfileRepository profileRepository;
    private final ProfileService profileService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LearningSnapshotStore(UserProfileRepository profileRepository, ProfileService profileService) {
        this.profileRepository = profileRepository;
        this.profileService = profileService;
    }

    /**
     * The stored plan, if it belongs to this profile revision AND this goal, is on the current
     * pipeline version, and still carries its server-issued ids. Anything else reads as "no plan".
     *
     * <p>The goal check is what stops the most misleading failure available here: a student changes
     * the target role from six months of data engineering to one month of frontend work, and is
     * shown the previous plan as though it answered the new question.
     */
    public Optional<LearningPlanDto> read(UserProfile profile, String revision, String goalKey) {
        String json = profile.getLearningSnapshotJson();
        if (json == null || json.isBlank()
                || !Objects.equals(revision, profile.getLearningSnapshotProfileKey())
                || !Objects.equals(goalKey, profile.getLearningSnapshotGoalKey())
                || profile.getLearningSnapshotVersion() == null
                || profile.getLearningSnapshotVersion() != LearningSnapshotService.SNAPSHOT_VERSION) {
            return Optional.empty();
        }
        try {
            LearningPlanDto plan = objectMapper.readValue(json, LearningPlanDto.class);
            if (plan != null && isServable(plan)) {
                return Optional.of(plan);
            }
            log.info("Stored plan for profile {} is structurally incomplete; it will not be served",
                    profile.getId());
            return Optional.empty();
        } catch (Exception e) {
            // Never log the body: it is derived from the student's CV.
            log.warn("Stored plan for profile {} could not be read ({}); it will not be served",
                    profile.getId(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Whether a stored plan can still be put in front of a student.
     *
     * <p>Validation happens on the way out, not only on the way in. Repairing at generation time
     * fixes what this build writes; it does nothing for a plan an earlier build already stored, and
     * a plan lives until the student next changes their profile or their goal. A plan saved with a
     * phase that lost its id would otherwise stay on screen indefinitely, looking complete, with a
     * checkbox attached to nothing.
     */
    private boolean isServable(LearningPlanDto plan) {
        if (plan.planId == null || plan.planId.isBlank()
                || plan.learningPath == null
                || plan.learningPath.phases == null || plan.learningPath.phases.isEmpty()
                || plan.skillGaps == null || plan.skillGaps.isEmpty()) {
            return false;
        }
        for (LearningPathDto.Phase phase : plan.learningPath.phases) {
            if (phase == null || phase.id == null || phase.id.isBlank()
                    || phase.title == null || phase.title.isBlank()) {
                return false;
            }
        }
        return plan.skillGaps.stream().allMatch(gap -> gap != null && gap.id != null && !gap.id.isBlank());
    }

    /**
     * Drops one specific unusable snapshot, and the progress that pointed into it.
     *
     * <p>{@code observedJson} is the exact content the caller read and rejected. Under the lock it
     * must still be what is stored, otherwise this does nothing. Without that check the method was
     * "clear whatever is there now", and two requests hitting the same corrupt snapshot would race:
     * the first regenerates and stores a good plan, the second then arrives with its own stale view
     * and deletes it, taking the phase ids and any ticks with it.
     *
     * @return true when this call is the one that cleared it
     */
    @Transactional
    public boolean invalidate(Long profileId, String observedJson) {
        return profileRepository.findByIdForUpdate(profileId).map(p -> {
            if (!Objects.equals(observedJson, p.getLearningSnapshotJson())) {
                log.debug("Snapshot for profile {} already moved on; leaving it alone", profileId);
                return false;
            }
            profileService.clearLearningState(p);
            profileRepository.save(p);
            return true;
        }).orElse(false);
    }

    /**
     * Stores a freshly generated plan under a short lock.
     *
     * @return the plan that is now current: the one just written, or the one a concurrent request
     *         had already committed for the same revision and goal, so both callers converge on the
     *         same phase ids.
     * @throws LearningSnapshotService.StaleProfileRevisionException if the profile changed while
     *         the plan was being generated
     */
    @Transactional
    public LearningPlanDto store(Long profileId, String revision, String goalKey,
                                 LearningPlanDto plan, boolean force) {
        UserProfile fresh = profileRepository.findByIdForUpdate(profileId).orElseThrow(() ->
                new LearningSnapshotService.StaleProfileRevisionException("The profile no longer exists."));

        if (!ProfileService.profileKey(fresh).equals(revision)) {
            throw new LearningSnapshotService.StaleProfileRevisionException(
                    "Your profile changed while the plan was being prepared. Generate it again to "
                            + "get a plan for the profile you have now.");
        }

        // Converging on an existing snapshot is right for two tabs generating at once: both end up
        // with the same phase ids and the second run's result is redundant. It is wrong when the
        // student pressed "generate again" deliberately - four model calls were spent and the new
        // plan would be silently discarded in favour of the one they were trying to replace.
        // `force` is what tells the two situations apart.
        if (!force) {
            Optional<LearningPlanDto> alreadyStored = read(fresh, revision, goalKey);
            if (alreadyStored.isPresent()) {
                log.info("A plan for this revision and goal already exists; keeping it and "
                        + "discarding the concurrently generated one");
                return alreadyStored.get();
            }
        }

        try {
            fresh.setLearningSnapshotJson(objectMapper.writeValueAsString(plan));
            fresh.setLearningSnapshotVersion(LearningSnapshotService.SNAPSHOT_VERSION);
            fresh.setLearningSnapshotProfileKey(revision);
            fresh.setLearningSnapshotGoalKey(goalKey);
            // A new plan means new ids, so progress recorded against the previous one cannot carry.
            fresh.setCompletedMilestones(null);
            profileRepository.save(fresh);
        } catch (Exception e) {
            // Failing to store is not a reason to fail the request: the student still gets their
            // plan, they just cannot tick it off until a later attempt stores successfully. It is
            // logged rather than swallowed because it means the next reload will show nothing.
            log.warn("Could not store the plan for profile {}: {}", profileId, e.getMessage());
        }
        return plan;
    }

    /**
     * Ticks or unticks one checkable item inside a locked read-modify-write, so a fast double click
     * cannot lose an update. {@code updatedAt} is deliberately untouched: a checkbox is not an edit
     * to the profile, and moving the revision would delete the plan being ticked.
     */
    @Transactional
    public UserProfile updateProgress(Long profileId, String planId, String itemId, boolean completed) {
        UserProfile profile = profileRepository.findByIdForUpdate(profileId).orElseThrow(() ->
                new LearningSnapshotService.StalePlanException("The profile no longer exists."));

        LearningPlanDto plan = read(profile, ProfileService.profileKey(profile),
                profile.getLearningSnapshotGoalKey()).orElseThrow(() ->
                new LearningSnapshotService.StalePlanException(
                        "There is no current plan to record progress against. Generate one first."));

        if (!plan.planId.equals(planId)) {
            throw new LearningSnapshotService.StalePlanException(
                    "This plan is no longer the current one. Reload the page.");
        }
        if (!plan.learningPath.allCheckableIds().contains(itemId)) {
            throw new LearningSnapshotService.CheckableNotFoundException(
                    "That item is not part of your current plan.");
        }

        Set<String> ids = new LinkedHashSet<>(profileService.readCompletedMilestones(profile));
        boolean mutated = completed ? ids.add(itemId) : ids.remove(itemId);
        if (mutated) {
            profile.setCompletedMilestones(profileService.writeCompletedMilestones(new ArrayList<>(ids)));
            profileRepository.save(profile);
        }
        return profile;
    }

    /** Raw stored JSON, used by {@link LearningSnapshotService} to invalidate exactly what it read. */
    public String rawSnapshot(UserProfile profile) {
        return profile.getLearningSnapshotJson();
    }

    /** Every checkable id in the stored plan, or an empty list when there is none. */
    public List<String> checkableIds(UserProfile profile) {
        return read(profile, ProfileService.profileKey(profile), profile.getLearningSnapshotGoalKey())
                .map(plan -> plan.learningPath.allCheckableIds())
                .orElseGet(List::of);
    }
}
