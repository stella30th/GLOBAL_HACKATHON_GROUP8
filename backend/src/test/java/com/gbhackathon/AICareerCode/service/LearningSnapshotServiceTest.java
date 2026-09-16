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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    void issuingIdsNeverTouchesTheAuditInTheSharedCache() {
        // Exactly what two requests share: AiCoachService caches per revision and hands the same
        // instance to everyone, so the ids must be stamped on a copy, never on this.
        ResumeAuditDto shared = coach.auditProfile(current());
        assertNull(shared.getCareerRoadmap().getRoadmapId(), "the cached audit starts with no ids");

        ResumeAuditDto served = snapshots.getOrCreateAudit(current());

        assertNotNull(served.getCareerRoadmap().getRoadmapId());
        assertNotSame(shared.getCareerRoadmap(), served.getCareerRoadmap());
        assertNull(shared.getCareerRoadmap().getRoadmapId(), "the shared cached audit must stay unstamped");
    }

    @Test
    void asecondGenerationCannotRewriteTheIdsAlreadyReturnedToSomeoneElse() {
        ResumeAuditDto first = snapshots.getOrCreateAudit(current());
        String firstRoadmapId = first.getCareerRoadmap().getRoadmapId();
        List<String> firstIds = idsOf(first.getCareerRoadmap());

        // Force a second generation from the same cached audit, as an overlapping request would.
        store.invalidate(current().getId(), current().getLearningSnapshotJson());
        ResumeAuditDto second = snapshots.getOrCreateAudit(current());

        assertNotEquals(firstRoadmapId, second.getCareerRoadmap().getRoadmapId());
        assertEquals(firstRoadmapId, first.getCareerRoadmap().getRoadmapId(),
                "the response already sent to the first caller must not be rewritten underneath it");
        assertEquals(firstIds, idsOf(first.getCareerRoadmap()));
    }

    @Test
    void concurrentIdIssuanceKeepsEachRequestsIdsToItself() throws Exception {
        // No database here on purpose: the defect was in memory, in the step between "the cache
        // handed me the audit" and "the snapshot was serialised", which is where two real threads
        // used to stamp over each other.
        ResumeAuditDto shared = coach.auditProfile(current());
        int callers = 8;

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResumeAuditDto>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    ResumeAuditDto mine = snapshots.copyWithNewIds(shared);
                    // Hold it, exactly as a request does while it waits for the storage lock, then
                    // read the ids back and check nobody else has been writing into them.
                    Thread.sleep(5);
                    return mine;
                }));
            }
            start.countDown();

            Set<String> everyId = new HashSet<>();
            int expected = 0;
            for (Future<ResumeAuditDto> future : futures) {
                CareerRoadmapDto roadmap = future.get(10, TimeUnit.SECONDS).getCareerRoadmap();
                List<String> ids = idsOf(roadmap);
                assertFalse(ids.contains(null));
                everyId.add(roadmap.getRoadmapId());
                everyId.addAll(ids);
                expected += ids.size() + 1;
            }
            assertEquals(expected, everyId.size(), "each caller must own a distinct set of ids");
            assertNull(shared.getCareerRoadmap().getRoadmapId(), "the shared audit must be untouched");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aStoredRoadmapMissingItsLaterStagesIsRegeneratedRatherThanServed() {
        // What an earlier build could leave behind: right version, right revision, ids present,
        // but only one stage. Structural validation has to happen on read, not just on write.
        ResumeAuditDto audit = snapshots.getOrCreateAudit(current());
        String oldRoadmapId = audit.getCareerRoadmap().getRoadmapId();
        snapshots.updateMilestoneProgress(current().getId(), oldRoadmapId,
                audit.getCareerRoadmap().allMilestones().get(0).getId(), true);

        audit.getCareerRoadmap().setMonths6(List.of());
        audit.getCareerRoadmap().setMonths12(null);
        UserProfile profile = current();
        profile.setLearningSnapshotJson(writeJson(audit));
        profileRepository.save(profile);

        CareerRoadmapDto served = snapshots.getOrCreateRoadmap(current());

        assertFalse(served.getMonths6().isEmpty(), "a truncated stored roadmap must not be served");
        assertFalse(served.getMonths12().isEmpty());
        assertNotEquals(oldRoadmapId, served.getRoadmapId());
        assertTrue(profileService.readCompletedMilestones(current()).isEmpty(),
                "a regenerated roadmap has new ids, so the old progress goes with the old roadmap");
    }

    @Test
    void aStoredMilestoneWithNoTitleIsRegeneratedRatherThanRenderedBlank() {
        ResumeAuditDto audit = snapshots.getOrCreateAudit(current());
        String oldRoadmapId = audit.getCareerRoadmap().getRoadmapId();
        audit.getCareerRoadmap().getMonths3().get(0).setTitle("  ");

        UserProfile profile = current();
        profile.setLearningSnapshotJson(writeJson(audit));
        profileRepository.save(profile);

        CareerRoadmapDto served = snapshots.getOrCreateRoadmap(current());

        assertNotEquals(oldRoadmapId, served.getRoadmapId());
        assertTrue(served.allMilestones().stream()
                .allMatch(m -> m.getTitle() != null && !m.getTitle().isBlank()));
    }

    private String writeJson(ResumeAuditDto audit) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(audit);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
    void aLateRequestCannotDeleteASnapshotThatSomeoneElseAlreadyRepaired() {
        // Two requests both read the same corrupt snapshot.
        UserProfile profile = current();
        String corrupt = "{ this is not json";
        profile.setLearningSnapshotJson(corrupt);
        profile.setLearningSnapshotVersion(LearningSnapshotService.SNAPSHOT_VERSION);
        profile.setLearningSnapshotProfileKey(ProfileService.profileKey(profile));
        profileRepository.save(profile);

        // The first one regenerates and stores a good snapshot, with progress recorded against it.
        ResumeAuditDto repaired = snapshots.getOrCreateAudit(current());
        String roadmapId = repaired.getCareerRoadmap().getRoadmapId();
        String milestoneId = repaired.getCareerRoadmap().allMilestones().get(0).getId();
        snapshots.updateMilestoneProgress(current().getId(), roadmapId, milestoneId, true);

        // The second one arrives late, still holding its view of the corrupt content.
        boolean cleared = store.invalidate(current().getId(), corrupt);

        assertFalse(cleared, "a stale view must not delete the snapshot that replaced it");
        assertEquals(roadmapId, snapshots.getOrCreateRoadmap(current()).getRoadmapId());
        assertEquals(List.of(milestoneId), profileService.readCompletedMilestones(current()));
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
