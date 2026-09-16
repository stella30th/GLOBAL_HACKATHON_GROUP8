package com.gbhackathon.AICareerCode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbhackathon.AICareerCode.dto.plan.CareerGoalDto;
import com.gbhackathon.AICareerCode.dto.plan.LearningPathDto;
import com.gbhackathon.AICareerCode.dto.plan.LearningPlanDto;
import com.gbhackathon.AICareerCode.dto.plan.SkillGapDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deciding whether a stored plan still answers the question being asked.
 *
 * <p>These read a profile row directly rather than going through the database, because every rule
 * here is a pure function of what is on the row: the plan JSON, the revision it was generated
 * from, the goal it was generated for and the pipeline version that produced it.
 */
class LearningSnapshotStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProfileService profileService = new ProfileService(null);
    private final LearningSnapshotStore store = new LearningSnapshotStore(null, profileService);

    private static CareerGoalDto goal(String role, int months, int hours) {
        CareerGoalDto goal = new CareerGoalDto();
        goal.targetRole = role;
        goal.targetSeniority = "JUNIOR";
        goal.durationMonths = months;
        goal.hoursPerWeek = hours;
        return goal;
    }

    private LearningPlanDto plan(CareerGoalDto goal) {
        LearningPlanDto plan = new LearningPlanDto();
        plan.planId = UUID.randomUUID().toString();
        plan.goal = goal;

        SkillGapDto gap = new SkillGapDto();
        gap.id = UUID.randomUUID().toString();
        gap.skillLabel = "SQL";
        plan.skillGaps = new ArrayList<>(List.of(gap));

        LearningPathDto.Phase phase = new LearningPathDto.Phase();
        phase.id = UUID.randomUUID().toString();
        phase.title = "Foundations";
        phase.order = 1;

        LearningPathDto path = new LearningPathDto();
        path.phases = new ArrayList<>(List.of(phase));
        plan.learningPath = path;
        return plan;
    }

    /** A profile row carrying a stored plan, as the database would hand it back. */
    private UserProfile profileWith(LearningPlanDto plan, String goalKey, int version) {
        UserProfile profile = new UserProfile();
        profile.setId(1L);
        profile.setUpdatedAt(LocalDateTime.of(2026, 9, 16, 12, 0));
        try {
            profile.setLearningSnapshotJson(objectMapper.writeValueAsString(plan));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        profile.setLearningSnapshotProfileKey(ProfileService.profileKey(profile));
        profile.setLearningSnapshotGoalKey(goalKey);
        profile.setLearningSnapshotVersion(version);
        return profile;
    }

    @Test
    void servesAPlanThatMatchesTheProfileAndTheGoal() {
        CareerGoalDto goal = goal("Backend Developer", 3, 8);
        UserProfile profile = profileWith(plan(goal), goal.key(), LearningSnapshotService.SNAPSHOT_VERSION);

        Optional<LearningPlanDto> read =
                store.read(profile, ProfileService.profileKey(profile), goal.key());

        assertTrue(read.isPresent());
        assertEquals("Backend Developer", read.get().goal.targetRole);
    }

    /**
     * The most misleading failure available here: the student changes what they are aiming at and
     * is shown the previous plan as though it answered the new question.
     */
    @Test
    void refusesAPlanBuiltForADifferentRole() {
        CareerGoalDto stored = goal("Backend Developer", 3, 8);
        UserProfile profile = profileWith(plan(stored), stored.key(), LearningSnapshotService.SNAPSHOT_VERSION);

        CareerGoalDto asked = goal("Data Analyst", 3, 8);

        assertTrue(store.read(profile, ProfileService.profileKey(profile), asked.key()).isEmpty());
    }

    @Test
    void refusesAPlanBuiltForADifferentTimeBudget() {
        CareerGoalDto stored = goal("Backend Developer", 3, 8);
        UserProfile profile = profileWith(plan(stored), stored.key(), LearningSnapshotService.SNAPSHOT_VERSION);

        assertTrue(store.read(profile, ProfileService.profileKey(profile),
                goal("Backend Developer", 6, 8).key()).isEmpty());
        assertTrue(store.read(profile, ProfileService.profileKey(profile),
                goal("Backend Developer", 3, 20).key()).isEmpty());
    }

    @Test
    void refusesAPlanBuiltFromAnEarlierProfileRevision() {
        CareerGoalDto goal = goal("Backend Developer", 3, 8);
        UserProfile profile = profileWith(plan(goal), goal.key(), LearningSnapshotService.SNAPSHOT_VERSION);
        profile.setUpdatedAt(profile.getUpdatedAt().plusSeconds(1));

        assertTrue(store.read(profile, ProfileService.profileKey(profile), goal.key()).isEmpty());
    }

    /**
     * A version 1 snapshot was produced before there was a taxonomy, retrieval or a graph. Showing
     * it under this version would present it as output of a pipeline that never touched it.
     */
    @Test
    void refusesASnapshotFromAnEarlierPipelineVersion() {
        CareerGoalDto goal = goal("Backend Developer", 3, 8);
        UserProfile profile = profileWith(plan(goal), goal.key(), 1);

        assertTrue(store.read(profile, ProfileService.profileKey(profile), goal.key()).isEmpty());
    }

    /**
     * Validation happens on the way out, not only on the way in: a plan an earlier build stored
     * without phase ids would otherwise stay on screen with checkboxes attached to nothing.
     */
    @Test
    void refusesAStoredPlanWhosePhasesLostTheirIds() {
        CareerGoalDto goal = goal("Backend Developer", 3, 8);
        LearningPlanDto broken = plan(goal);
        broken.learningPath.phases.get(0).id = null;
        UserProfile profile = profileWith(broken, goal.key(), LearningSnapshotService.SNAPSHOT_VERSION);

        assertTrue(store.read(profile, ProfileService.profileKey(profile), goal.key()).isEmpty());
    }

    @Test
    void refusesUnparseableStoredContentWithoutThrowing() {
        UserProfile profile = new UserProfile();
        profile.setId(1L);
        profile.setUpdatedAt(LocalDateTime.of(2026, 9, 16, 12, 0));
        profile.setLearningSnapshotJson("{ this is not json");
        profile.setLearningSnapshotProfileKey(ProfileService.profileKey(profile));
        profile.setLearningSnapshotGoalKey("k");
        profile.setLearningSnapshotVersion(LearningSnapshotService.SNAPSHOT_VERSION);

        assertTrue(store.read(profile, ProfileService.profileKey(profile), "k").isEmpty());
    }

    // ---- goal identity ----

    @Test
    void reTypingTheSameGoalIsNotAChange() {
        assertEquals(goal("Backend Developer", 3, 8).key(),
                goal("  backend   developer ", 3, 8).key());
    }

    @Test
    void editingThePastedJobDescriptionIsAChange() {
        CareerGoalDto before = goal("Backend Developer", 3, 8);
        before.jobDescription = "We need someone who knows SQL.";
        CareerGoalDto after = goal("Backend Developer", 3, 8);
        after.jobDescription = "We need someone who knows Kubernetes.";

        assertFalse(before.key().equals(after.key()));
    }

    @Test
    void computesTheStudyBudgetFromMonthsAndWeeklyHours() {
        assertEquals(130, goal("x", 3, 10).budgetHours(), "3 months at 10h/week");
        assertEquals(43, goal("x", 1, 10).budgetHours(), "1 month at 10h/week");
        assertEquals(260, goal("x", 6, 10).budgetHours(), "6 months at 10h/week");
    }
}
