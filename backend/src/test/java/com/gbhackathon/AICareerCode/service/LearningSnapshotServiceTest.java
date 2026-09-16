package com.gbhackathon.AICareerCode.service;

import com.gbhackathon.AICareerCode.dto.CareerRoadmapDto;
import com.gbhackathon.AICareerCode.dto.ProfileDto;
import com.gbhackathon.AICareerCode.dto.ResumeAuditDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The invariants that make a milestone something you can tick: the ids do not move unless the
 * profile does, both endpoints see the same ones, and recording progress is not an edit.
 *
 * <p>No Gemini key is configured in the test profile, so the coach takes its offline path. That is
 * deliberate rather than a limitation: the storage behaviour must hold whichever engine produced
 * the content, and a unit test should not depend on a live model.
 */
@DataJpaTest
@Import({ProfileService.class, LearningSnapshotStore.class})
class LearningSnapshotServiceTest {

    @Autowired
    private ProfileService profileService;

    @Autowired
    private LearningSnapshotStore store;

    @Autowired
    private UserProfileRepository profileRepository;

    /** Counts how often an analysis was actually generated, so "reused" can be asserted. */
    static class CountingCoach extends AiCoachService {
        final AtomicInteger generations = new AtomicInteger();

        CountingCoach() {
            super(new GeminiClient());
        }

        @Override
        public ResumeAuditDto auditProfile(UserProfile profile) {
            generations.incrementAndGet();
            return super.auditProfile(profile);
        }
    }

    private CountingCoach coach;
    private LearningSnapshotService snapshots;

    @BeforeEach
    void setUp() {
        coach = new CountingCoach();
        snapshots = new LearningSnapshotService(store, coach);

        ProfileDto dto = new ProfileDto();
        dto.setFullName("Mai Tran");
        dto.setCurrentTitle("Software Engineering Student");
        dto.setIndustry("Software Engineering");
        dto.setYearOfStudy("Year 2");
        dto.setYearsOfExperience(0.0);
        dto.setSkills(List.of("Java", "Git"));
        profileService.saveOrUpdateProfile(dto);
    }

    private UserProfile current() {
        return profileService.getCurrentOrCreateProfile();
    }

    @Test
    void theAnalysisIsGeneratedOnceAndThenReadBackFromTheDatabase() {
        ResumeAuditDto first = snapshots.getOrCreateAudit(current());
        String roadmapId = first.getCareerRoadmap().getRoadmapId();
        assertNotNull(roadmapId);
        assertTrue(first.getCareerRoadmap().allMilestones().stream()
                .allMatch(m -> m.getId() != null && !m.getId().isBlank()));

        // A brand-new service and coach, as after a restart: the ids must survive, not be reissued.
        CountingCoach afterRestart = new CountingCoach();
        LearningSnapshotService restarted = new LearningSnapshotService(store, afterRestart);
        ResumeAuditDto second = restarted.getOrCreateAudit(current());

        assertEquals(roadmapId, second.getCareerRoadmap().getRoadmapId());
        assertEquals(idsOf(first.getCareerRoadmap()), idsOf(second.getCareerRoadmap()));
        assertEquals(0, afterRestart.generations.get(), "a stored snapshot must not be regenerated");
    }

    @Test
    void theAuditAndTheRoadmapEndpointsAgreeOnTheIds() {
        ResumeAuditDto audit = snapshots.getOrCreateAudit(current());
        CareerRoadmapDto roadmap = snapshots.getOrCreateRoadmap(current());

        assertEquals(audit.getCareerRoadmap().getRoadmapId(), roadmap.getRoadmapId());
        assertEquals(idsOf(audit.getCareerRoadmap()), idsOf(roadmap));
        assertEquals(1, coach.generations.get(), "the roadmap must reuse the audit, not run a second analysis");
    }

    @Test
    void theOfflineRoadmapAlwaysCarriesAnAiFluencyMilestone() {
        CareerRoadmapDto roadmap = snapshots.getOrCreateRoadmap(current());
        assertTrue(roadmap.allMilestones().stream()
                        .anyMatch(m -> "AI_FLUENCY".equalsIgnoreCase(m.getCategory())),
                "the product's central promise cannot depend on the model remembering it");
    }

    @Test
    void tickingAMilestoneIsIdempotentAndIsNotAProfileEdit() {
        CareerRoadmapDto roadmap = snapshots.getOrCreateRoadmap(current());
        String milestoneId = roadmap.allMilestones().get(0).getId();
        var revisionBefore = current().getUpdatedAt();
        int generationsBefore = coach.generations.get();

        snapshots.updateMilestoneProgress(current().getId(), roadmap.getRoadmapId(), milestoneId, true);
        snapshots.updateMilestoneProgress(current().getId(), roadmap.getRoadmapId(), milestoneId, true);

        UserProfile after = current();
        assertEquals(List.of(milestoneId), profileService.readCompletedMilestones(after));
        assertEquals(revisionBefore, after.getUpdatedAt(), "a checkbox must not move the profile revision");
        assertEquals(generationsBefore, coach.generations.get(), "a checkbox must not call the model");

        snapshots.updateMilestoneProgress(current().getId(), roadmap.getRoadmapId(), milestoneId, false);
        snapshots.updateMilestoneProgress(current().getId(), roadmap.getRoadmapId(), milestoneId, false);
        assertTrue(profileService.readCompletedMilestones(current()).isEmpty());

        // And the roadmap itself is untouched by any of it.
        assertEquals(roadmap.getRoadmapId(), snapshots.getOrCreateRoadmap(current()).getRoadmapId());
    }

    @Test
    void progressCannotBeRecordedAgainstAnotherRoadmapOrAnUnknownMilestone() {
        CareerRoadmapDto roadmap = snapshots.getOrCreateRoadmap(current());
        String milestoneId = roadmap.allMilestones().get(0).getId();
        Long id = current().getId();

        assertThrows(LearningSnapshotService.StaleRoadmapException.class, () ->
                snapshots.updateMilestoneProgress(id, "some-other-roadmap", milestoneId, true));

        assertThrows(LearningSnapshotService.MilestoneNotFoundException.class, () ->
                snapshots.updateMilestoneProgress(id, roadmap.getRoadmapId(), "not-a-milestone", true));

        assertTrue(profileService.readCompletedMilestones(current()).isEmpty());
    }

    @Test
    void anAnalysisGeneratedForAnOlderProfileIsNotAllowedToOverwriteTheCurrentOne() {
        UserProfile stale = current();
        String staleRevision = ProfileService.profileKey(stale);

        // The user edits their profile while the analysis is still being generated.
        ProfileDto edit = new ProfileDto();
        edit.setBio("Now studying embedded systems too.");
        profileService.saveOrUpdateProfile(edit);

        ResumeAuditDto generatedForTheOldProfile = coach.auditProfile(stale);
        assertThrows(LearningSnapshotService.StaleProfileRevisionException.class, () ->
                store.store(stale.getId(), staleRevision, generatedForTheOldProfile));

        assertNull(current().getLearningSnapshotJson());
    }

    @Test
    void aSnapshotFromAnIncompatibleVersionIsDiscardedRatherThanServed() {
        snapshots.getOrCreateAudit(current());
        UserProfile profile = current();
        profile.setLearningSnapshotVersion(LearningSnapshotService.SNAPSHOT_VERSION + 1);
        profileRepository.save(profile);

        int before = coach.generations.get();
        ResumeAuditDto regenerated = snapshots.getOrCreateAudit(current());

        assertNotNull(regenerated.getCareerRoadmap().getRoadmapId());
        assertEquals(before + 1, coach.generations.get());
        assertEquals(LearningSnapshotService.SNAPSHOT_VERSION, current().getLearningSnapshotVersion());
    }

    @Test
    void anUnreadableSnapshotIsRegeneratedInsteadOfFailingTheRequest() {
        UserProfile profile = current();
        profile.setLearningSnapshotJson("{ this is not json");
        profile.setLearningSnapshotVersion(LearningSnapshotService.SNAPSHOT_VERSION);
        profile.setLearningSnapshotProfileKey(ProfileService.profileKey(profile));
        profileRepository.save(profile);

        ResumeAuditDto audit = snapshots.getOrCreateAudit(current());
        assertNotNull(audit.getCareerRoadmap().getRoadmapId());
    }

    private List<String> idsOf(CareerRoadmapDto roadmap) {
        return roadmap.allMilestones().stream().map(CareerRoadmapDto.RoadmapMilestone::getId).toList();
    }
}
