package com.gbhackathon.AICareerCode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbhackathon.AICareerCode.dto.CareerRoadmapDto;
import com.gbhackathon.AICareerCode.dto.ResumeAuditDto;
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
 * Every database read and write of the learning snapshot and the progress attached to it.
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
     * The stored audit, if it belongs to this revision, is on the current version and still
     * carries its server-issued ids. Anything else reads as "no snapshot".
     */
    public Optional<ResumeAuditDto> read(UserProfile profile, String revision) {
        String json = profile.getLearningSnapshotJson();
        if (json == null || json.isBlank()
                || !Objects.equals(revision, profile.getLearningSnapshotProfileKey())
                || profile.getLearningSnapshotVersion() == null
                || profile.getLearningSnapshotVersion() != LearningSnapshotService.SNAPSHOT_VERSION) {
            return Optional.empty();
        }
        try {
            ResumeAuditDto audit = objectMapper.readValue(json, ResumeAuditDto.class);
            if (audit != null && audit.getCareerRoadmap() != null && hasIds(audit.getCareerRoadmap())) {
                return Optional.of(audit);
            }
            return Optional.empty();
        } catch (Exception e) {
            // Never log the body: it is derived from the user's CV.
            log.warn("Learning snapshot for profile {} could not be read ({}); it will be regenerated",
                    profile.getId(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Drops an unusable or outdated snapshot, and the progress that pointed into it. */
    @Transactional
    public void invalidate(Long profileId) {
        profileRepository.findByIdForUpdate(profileId).ifPresent(p -> {
            profileService.clearLearningState(p);
            profileRepository.save(p);
        });
    }

    /**
     * Stores a freshly generated audit under a short lock.
     *
     * @return the snapshot that is now current: the one just written, or the one a concurrent
     *         request had already committed for the same revision, so both callers converge on
     *         the same milestone ids.
     * @throws LearningSnapshotService.StaleProfileRevisionException if the profile changed while
     *         the analysis was being generated
     */
    @Transactional
    public ResumeAuditDto store(Long profileId, String revision, ResumeAuditDto audit) {
        UserProfile fresh = profileRepository.findByIdForUpdate(profileId).orElseThrow(() ->
                new LearningSnapshotService.StaleProfileRevisionException("The profile no longer exists."));

        if (!ProfileService.profileKey(fresh).equals(revision)) {
            throw new LearningSnapshotService.StaleProfileRevisionException(
                    "Your profile changed while the analysis was being prepared. Reload to see the new one.");
        }

        Optional<ResumeAuditDto> alreadyStored = read(fresh, revision);
        if (alreadyStored.isPresent()) {
            return alreadyStored.get();
        }

        try {
            fresh.setLearningSnapshotJson(objectMapper.writeValueAsString(audit));
            fresh.setLearningSnapshotVersion(LearningSnapshotService.SNAPSHOT_VERSION);
            fresh.setLearningSnapshotProfileKey(revision);
            // New roadmap means new ids, so progress recorded against the previous one cannot carry.
            fresh.setCompletedMilestones(null);
            profileRepository.save(fresh);
        } catch (Exception e) {
            // Failing to store is not a reason to fail the request: the user still gets their
            // analysis, they just cannot tick it off until a later attempt stores successfully.
            log.warn("Could not store the learning snapshot for profile {}: {}", profileId, e.getMessage());
        }
        return audit;
    }

    /**
     * Ticks or unticks one milestone inside a locked read-modify-write, so a fast double click
     * cannot lose an update. {@code updatedAt} is deliberately untouched.
     */
    @Transactional
    public UserProfile updateMilestone(Long profileId, String roadmapId, String milestoneId, boolean completed) {
        UserProfile profile = profileRepository.findByIdForUpdate(profileId).orElseThrow(() ->
                new LearningSnapshotService.StaleRoadmapException("The profile no longer exists."));

        ResumeAuditDto audit = read(profile, ProfileService.profileKey(profile)).orElseThrow(() ->
                new LearningSnapshotService.StaleRoadmapException(
                        "There is no current analysis to record progress against. Reload your roadmap."));

        CareerRoadmapDto roadmap = audit.getCareerRoadmap();
        if (!roadmap.getRoadmapId().equals(roadmapId)) {
            throw new LearningSnapshotService.StaleRoadmapException(
                    "This roadmap is no longer the current one. Reload your roadmap.");
        }
        boolean known = roadmap.allMilestones().stream().anyMatch(m -> milestoneId.equals(m.getId()));
        if (!known) {
            throw new LearningSnapshotService.MilestoneNotFoundException(
                    "That milestone is not part of your current roadmap.");
        }

        Set<String> ids = new LinkedHashSet<>(profileService.readCompletedMilestones(profile));
        boolean mutated = completed ? ids.add(milestoneId) : ids.remove(milestoneId);
        if (mutated) {
            profile.setCompletedMilestones(profileService.writeCompletedMilestones(new ArrayList<>(ids)));
            profileRepository.save(profile);
        }
        return profile;
    }

    private boolean hasIds(CareerRoadmapDto roadmap) {
        if (roadmap.getRoadmapId() == null || roadmap.getRoadmapId().isBlank()) {
            return false;
        }
        List<CareerRoadmapDto.RoadmapMilestone> milestones = roadmap.allMilestones();
        return !milestones.isEmpty()
                && milestones.stream().allMatch(m -> m.getId() != null && !m.getId().isBlank());
    }
}
