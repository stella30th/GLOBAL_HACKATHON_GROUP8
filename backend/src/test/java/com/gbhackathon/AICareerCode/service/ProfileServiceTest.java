package com.gbhackathon.AICareerCode.service;

import com.gbhackathon.AICareerCode.dto.plan.CareerGoalDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation of the goal, and the "what is still missing" message the generate button shows.
 *
 * <p>The repository is never touched by anything under test here, so it is left null rather than
 * mocked - a stub would only make it harder to see that these are pure functions.
 */
class ProfileServiceTest {

    private final ProfileService service = new ProfileService(null);

    @Test
    void acceptsTheCanonicalSeniorityValuesInAnyCase() {
        assertEquals("JUNIOR", ProfileService.normalizeSeniority("junior"));
        assertEquals("MID", ProfileService.normalizeSeniority("  Mid "));
        assertNull(ProfileService.normalizeSeniority(""));
        assertNull(ProfileService.normalizeSeniority(null));
    }

    /**
     * Rejected rather than stored and silently ignored later. A seniority the pipeline does not
     * understand changes every required level in the analysis.
     */
    @Test
    void rejectsASeniorityOutsideTheList() {
        assertThrows(ProfileValidationException.class, () -> ProfileService.normalizeSeniority("PRINCIPAL"));
    }

    @Test
    void acceptsOnlyTheOfferedPlanLengths() {
        assertEquals(1, ProfileService.normalizeDuration(1));
        assertEquals(3, ProfileService.normalizeDuration(3));
        assertEquals(6, ProfileService.normalizeDuration(6));
        assertNull(ProfileService.normalizeDuration(null));
        assertThrows(ProfileValidationException.class, () -> ProfileService.normalizeDuration(12));
        assertThrows(ProfileValidationException.class, () -> ProfileService.normalizeDuration(0));
    }

    /**
     * Not a judgement about how hard anyone works: a plan generated against 80 hours a week is a
     * plan for a situation that will not hold, and the hours it promises are the one number a
     * student actually relies on.
     */
    @Test
    void boundsTheWeeklyStudyBudget() {
        assertEquals(1, ProfileService.normalizeHoursPerWeek(1));
        assertEquals(ProfileService.MAX_HOURS_PER_WEEK,
                ProfileService.normalizeHoursPerWeek(ProfileService.MAX_HOURS_PER_WEEK));
        assertThrows(ProfileValidationException.class, () -> ProfileService.normalizeHoursPerWeek(0));
        assertThrows(ProfileValidationException.class,
                () -> ProfileService.normalizeHoursPerWeek(ProfileService.MAX_HOURS_PER_WEEK + 1));
    }

    @Test
    void noGoalUntilATargetRoleIsSet() {
        UserProfile profile = new UserProfile();
        assertNull(service.goalOf(profile));

        profile.setTargetRole("Backend Developer");
        profile.setTargetSeniority("JUNIOR");
        profile.setPlanDurationMonths(3);
        profile.setPlanHoursPerWeek(8);

        CareerGoalDto goal = service.goalOf(profile);
        assertEquals("Backend Developer", goal.targetRole);
        assertEquals(104, goal.budgetHours());
    }

    @Test
    void namesEveryMissingInputOnAnEmptyProfile() {
        List<String> missing = service.missingPlanInputs(new UserProfile());

        assertEquals(5, missing.size(), missing.toString());
        assertTrue(missing.stream().anyMatch(m -> m.contains("upload a CV")), missing.toString());
        assertTrue(missing.stream().anyMatch(m -> m.contains("role")), missing.toString());
        assertTrue(missing.stream().anyMatch(m -> m.contains("hours a week")), missing.toString());
    }

    /** Raw CV text counts as a profile even before the skill list has been filled in. */
    @Test
    void acceptsRawCvTextAsProfileContent() {
        UserProfile profile = new UserProfile();
        profile.setRawCvText("Nguyen Van A, final year, built a booking system ...");
        profile.setTargetRole("Backend Developer");
        profile.setTargetSeniority("JUNIOR");
        profile.setPlanDurationMonths(3);
        profile.setPlanHoursPerWeek(8);

        assertTrue(service.missingPlanInputs(profile).isEmpty());
    }

    /**
     * A tick writes only the progress list. If it moved the revision it would invalidate the very
     * plan it was recording progress against.
     */
    @Test
    void roundTripsTheProgressList() {
        UserProfile profile = new UserProfile();
        assertTrue(service.readCompletedMilestones(profile).isEmpty());

        profile.setCompletedMilestones(service.writeCompletedMilestones(List.of("a", "b")));
        assertEquals(List.of("a", "b"), service.readCompletedMilestones(profile));
    }

    @Test
    void treatsCorruptedProgressAsEmptyRatherThanFailingTheProfileRead() {
        UserProfile profile = new UserProfile();
        profile.setCompletedMilestones("not json at all");

        assertTrue(service.readCompletedMilestones(profile).isEmpty());
    }

    /**
     * The revision is the key that decides whether a stored plan still belongs to this profile, so
     * two different states must never produce the same one.
     */
    @Test
    void profileKeyChangesWithTheUpdatedTimestamp() {
        UserProfile profile = new UserProfile();
        profile.setId(7L);
        profile.setUpdatedAt(java.time.LocalDateTime.of(2026, 9, 16, 12, 0));
        String before = ProfileService.profileKey(profile);

        profile.setUpdatedAt(profile.getUpdatedAt().plusNanos(1000));

        assertTrue(!before.equals(ProfileService.profileKey(profile)));
    }
}
