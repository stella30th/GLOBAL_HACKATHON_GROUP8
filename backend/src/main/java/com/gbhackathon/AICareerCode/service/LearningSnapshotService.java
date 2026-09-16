package com.gbhackathon.AICareerCode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        String unusable = profile.getLearningSnapshotJson();
        if (unusable != null && !unusable.isBlank()) {
            // Present but unusable: wrong revision, wrong version, missing ids or unparseable.
            // Clear it so a legacy row cannot keep failing every request that touches it — but
            // only the exact content this request read, never whatever happens to be there by the
            // time the lock is granted.
            store.invalidate(profile.getId(), unusable);
        }

        // Generated outside any transaction: a Gemini round trip takes tens of seconds and must
        // never hold a database lock.
        ResumeAuditDto generated = copyWithNewIds(aiCoachService.auditProfile(profile));
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
     * A private copy of the generated audit, carrying freshly issued ids.
     *
     * <p>The copy is the point. {@link AiCoachService#auditProfile} caches per profile revision and
     * hands the same instance to every caller, so writing ids into it wrote them into everyone's
     * copy: two requests generating at once would each stamp their own ids over the shared object,
     * and whichever finished second silently rewrote the ids the first had already serialised and
     * returned. The lock at the storage step cannot help with that — the damage happens before it,
     * in memory. Each request now stamps only its own object, and the lock decides which one wins.
     *
     * <p>The model is never asked to produce the ids: it repeats and reuses them across
     * generations. An array index or the title is not an identifier either, since both change the
     * moment the wording does.
     */
    ResumeAuditDto copyWithNewIds(ResumeAuditDto audit) {
        if (audit == null) {
            return null;
        }
        ResumeAuditDto copy;
        try {
            copy = objectMapper.readValue(objectMapper.writeValueAsBytes(audit), ResumeAuditDto.class);
        } catch (Exception e) {
            // Serialising it is also how it gets stored, so this should not happen; if it does, the
            // request still needs an answer and a unique set of ids is better than a shared one.
            log.warn("Could not copy the generated audit ({}); stamping ids on the original",
                    e.getClass().getSimpleName());
            copy = audit;
        }

        CareerRoadmapDto roadmap = copy.getCareerRoadmap();
        if (roadmap != null) {
            roadmap.setRoadmapId(UUID.randomUUID().toString());
            for (CareerRoadmapDto.RoadmapMilestone milestone : roadmap.allMilestones()) {
                milestone.setId(UUID.randomUUID().toString());
            }
        }
        return copy;
    }
}
