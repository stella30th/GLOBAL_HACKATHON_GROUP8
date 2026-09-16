package com.gbhackathon.AICareerCode.service;

import com.gbhackathon.AICareerCode.dto.CareerRoadmapDto;
import com.gbhackathon.AICareerCode.dto.ResumeAuditDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Owns the stored audit + roadmap ("learning snapshot") and the self-reported progress on it.
 *
 * <p>The audit used to exist only in an in-memory cache keyed by profile revision. That was enough
 * to stop repeated Gemini calls, but not enough to make a milestone something you can tick: a
 * restart or an eviction produced a differently worded roadmap in different positions, and any
 * progress recorded against the old one pointed at nothing. The snapshot is therefore written to
 * the profile row with server-issued UUIDs assigned once, and it is the source of truth; the
 * caches in {@link AiCoachService} remain a pure optimisation in front of it.
 */
@Service
public class LearningSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(LearningSnapshotService.class);

    /**
     * Bumped whenever the stored shape or the prompt contract changes in a way that makes an older
     * snapshot misleading. A snapshot from another version is discarded and regenerated.
     */
    public static final int SNAPSHOT_VERSION = 1;

    private final LearningSnapshotStore store;
    private final AiCoachService aiCoachService;

    public LearningSnapshotService(LearningSnapshotStore store, AiCoachService aiCoachService) {
        this.store = store;
        this.aiCoachService = aiCoachService;
    }

    /** Raised when the profile changed while an analysis was being generated. Maps to HTTP 409. */
    public static class StaleProfileRevisionException extends RuntimeException {
        public StaleProfileRevisionException(String message) {
            super(message);
        }
    }

    /** Raised when a progress update names a roadmap that is no longer current. HTTP 409. */
    public static class StaleRoadmapException extends RuntimeException {
        public StaleRoadmapException(String message) {
            super(message);
        }
    }

    /** Raised when a milestone id is not part of the current roadmap. HTTP 404. */
    public static class MilestoneNotFoundException extends RuntimeException {
        public MilestoneNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * The audit for this profile revision: the stored one while it is still valid, otherwise a
     * freshly generated one, stored before it is returned.
     */
    public ResumeAuditDto getOrCreateAudit(UserProfile profile) {
        String revision = ProfileService.profileKey(profile);

        Optional<ResumeAuditDto> stored = store.read(profile, revision);
        if (stored.isPresent()) {
            return stored.get();
        }
        if (profile.getLearningSnapshotJson() != null && !profile.getLearningSnapshotJson().isBlank()) {
            // Present but unusable: wrong revision, wrong version, missing ids or unparseable.
            // Clear it so a legacy row cannot keep failing every request it touches.
            store.invalidate(profile.getId());
        }

        // Generated outside any transaction: a Gemini round trip takes tens of seconds and must
        // never hold a database lock.
        ResumeAuditDto generated = aiCoachService.auditProfile(profile);
        assignIds(generated);
        log.info("Stored a new learning snapshot for profile {} (revision {})", profile.getId(), revision);

        return store.store(profile.getId(), revision, generated);
    }

    /** The roadmap belonging to the current snapshot. Never generates a second, separate one. */
    public CareerRoadmapDto getOrCreateRoadmap(UserProfile profile) {
        return getOrCreateAudit(profile).getCareerRoadmap();
    }

    /** Ticks or unticks one milestone. See {@link LearningSnapshotStore#updateMilestone}. */
    public UserProfile updateMilestoneProgress(Long profileId, String roadmapId, String milestoneId, boolean completed) {
        return store.updateMilestone(profileId, roadmapId, milestoneId, completed);
    }

    /**
     * Assigns the roadmap and milestone ids. The model is never asked to produce them: it repeats
     * and reuses them across generations. An array index or the title is not an identifier either,
     * since both change the moment the wording does.
     */
    private void assignIds(ResumeAuditDto audit) {
        if (audit == null || audit.getCareerRoadmap() == null) {
            return;
        }
        CareerRoadmapDto roadmap = audit.getCareerRoadmap();
        roadmap.setRoadmapId(UUID.randomUUID().toString());
        for (CareerRoadmapDto.RoadmapMilestone milestone : roadmap.allMilestones()) {
            milestone.setId(UUID.randomUUID().toString());
        }
    }
}
