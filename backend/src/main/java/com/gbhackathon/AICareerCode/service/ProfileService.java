package com.gbhackathon.AICareerCode.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbhackathon.AICareerCode.dto.ProfileDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    /**
     * The only values {@code yearOfStudy} may hold. A free-text year would break the roadmap
     * prompt, which branches on the study stage, so anything outside this list is rejected rather
     * than stored and silently ignored later.
     */
    public static final List<String> YEAR_OF_STUDY_VALUES =
            List.of("Year 1", "Year 2", "Year 3", "Year 4", "Year 5+");

    private final UserProfileRepository profileRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProfileService(UserProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    public List<UserProfile> getAllProfiles() {
        return profileRepository.findAll();
    }

    public Optional<UserProfile> getProfileById(Long id) {
        return profileRepository.findById(id);
    }

    /** Revision string used as the cache and snapshot key. Changes only when content changes. */
    public static String profileKey(UserProfile profile) {
        return profile.getId() + "@"
                + (profile.getUpdatedAt() != null ? profile.getUpdatedAt().toString() : "new");
    }

    @Transactional
    public UserProfile getCurrentOrCreateProfile() {
        List<UserProfile> list = profileRepository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt", "id"));
        if (!list.isEmpty()) {
            UserProfile latest = list.get(0);
            if (list.size() > 1) {
                for (int i = 1; i < list.size(); i++) {
                    try {
                        profileRepository.delete(list.get(i));
                    } catch (Exception ignored) {}
                }
            }
            return latest;
        }
        // A blank onboarding profile. The old default described a backend engineer with three years
        // of experience and senior-role goals, which is advice-shaping fiction for a first-year
        // student: the audit and roadmap were generated from a person who did not exist.
        UserProfile p = new UserProfile();
        p.setFullName("");
        p.setEmail("");
        p.setPhone("");
        p.setCurrentTitle("");
        p.setIndustry("");
        p.setYearsOfExperience(0.0);
        p.setEducation("");
        p.setLanguages("");
        p.setSkills("");
        p.setTargetRoles("");
        p.setTargetLocations("");
        p.setWillingToRelocate(false);
        p.setTargetWorkType("ANY");
        p.setBio("");
        p.setYearOfStudy(null);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        return profileRepository.save(p);
    }

    // ---------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------

    /**
     * Applies an edit from the profile form.
     *
     * <p>Fields absent from the request keep their stored value, which is what the form needs when
     * it posts a partial object. Two behaviours matter beyond that:
     *
     * <ul>
     *   <li>A save that changes nothing after normalisation is a no-op: {@code updatedAt} does not
     *       move and the learning snapshot and progress survive. Pressing Save twice used to throw
     *       away a roadmap the user had been ticking through.</li>
     *   <li>A save that does change something resets the snapshot and the progress, because the
     *       advice was generated from the old profile and the milestone ids belong to it.</li>
     * </ul>
     *
     * <p>{@code completedMilestones} and {@code roadmapId} on the incoming DTO are ignored here on
     * purpose; progress moves only through the dedicated milestone endpoint.
     */
    @Transactional
    public UserProfile saveOrUpdateProfile(ProfileDto dto) {
        UserProfile profile = getCurrentOrCreateProfile();
        boolean changed = applyEditableFields(profile, dto);

        if (!changed) {
            log.debug("Profile save was a no-op; keeping revision {}", profileKey(profile));
            return profile;
        }

        clearLearningState(profile);
        profile.setUpdatedAt(nextRevisionTimestamp(profile.getUpdatedAt()));
        return profileRepository.save(profile);
    }

    /**
     * The next revision timestamp, guaranteed to be strictly later than the previous one.
     *
     * <p>updatedAt is not decoration here, it is the key that decides whether a stored analysis
     * still belongs to this profile. Two edits inside the same clock tick would produce the same
     * key, and the second edit would then be served the first edit's roadmap. Timestamps are also
     * truncated to microseconds because that is what the database columns keep, so the value read
     * back matches the value that was compared against.
     */
    private static LocalDateTime nextRevisionTimestamp(LocalDateTime previous) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if (previous == null) {
            return now;
        }
        LocalDateTime floor = previous.truncatedTo(ChronoUnit.MICROS);
        return now.isAfter(floor) ? now : floor.plus(1, ChronoUnit.MICROS);
    }

    /**
     * Replaces the stored profile with the result of a CV upload.
     *
     * <p>{@link #saveOrUpdateProfile} merges field by field, which is right for manual edits but
     * wrong here: uploading a semiconductor CV over a previous software profile would keep the old
     * skills, industry and target roles wherever the new CV had nothing to say. A CV upload
     * describes a whole person, so every extracted field is written, including the empty ones —
     * including {@code yearOfStudy}, which falls back to null when the CV does not state a year.
     */
    @Transactional
    public UserProfile replaceProfileFromCv(ProfileDto dto) {
        return replaceProfile(dto, dto.getRawCvText());
    }

    /**
     * Replaces the stored profile with a demo sample. The previous candidate's raw CV text is
     * dropped: leaving it behind meant every prompt was still grounded in someone else's document.
     */
    @Transactional
    public UserProfile replaceProfileFromSample(ProfileDto dto) {
        return replaceProfile(dto, null);
    }

    private UserProfile replaceProfile(ProfileDto dto, String rawCvText) {
        UserProfile profile = getCurrentOrCreateProfile();

        profile.setFullName(dto.getFullName());
        profile.setEmail(dto.getEmail());
        profile.setPhone(dto.getPhone());
        profile.setCurrentTitle(dto.getCurrentTitle());
        profile.setIndustry(dto.getIndustry());
        profile.setYearOfStudy(normalizeYearOfStudy(dto.getYearOfStudy()));
        profile.setYearsOfExperience(dto.getYearsOfExperience() != null ? dto.getYearsOfExperience() : 0.0);
        profile.setBio(dto.getBio());
        profile.setEducation(dto.getEducation());
        profile.setLanguages(dto.getLanguages());
        profile.setSkillList(dto.getSkills() != null ? dto.getSkills() : List.of());
        profile.setTargetRoles(dto.getTargetRoles() != null ? String.join(", ", dto.getTargetRoles()) : "");
        profile.setTargetLocations(dto.getTargetLocations() != null ? String.join(", ", dto.getTargetLocations()) : "");
        profile.setWillingToRelocate(dto.getWillingToRelocate() != null ? dto.getWillingToRelocate() : false);
        profile.setTargetWorkType(dto.getTargetWorkType() != null ? dto.getTargetWorkType() : "ANY");
        profile.setRawCvText(rawCvText);

        clearLearningState(profile);
        profile.setUpdatedAt(nextRevisionTimestamp(profile.getUpdatedAt()));
        return profileRepository.save(profile);
    }

    /**
     * Writes the fields the profile form owns and reports whether anything actually moved.
     * Comparison happens after normalisation so re-saving the same form is recognised as a no-op.
     */
    private boolean applyEditableFields(UserProfile profile, ProfileDto dto) {
        boolean changed = false;

        changed |= setIfChanged(dto.getFullName(), profile.getFullName(), profile::setFullName);
        changed |= setIfChanged(dto.getEmail(), profile.getEmail(), profile::setEmail);
        changed |= setIfChanged(dto.getPhone(), profile.getPhone(), profile::setPhone);
        changed |= setIfChanged(dto.getCurrentTitle(), profile.getCurrentTitle(), profile::setCurrentTitle);
        changed |= setIfChanged(dto.getIndustry(), profile.getIndustry(), profile::setIndustry);
        changed |= setIfChanged(dto.getBio(), profile.getBio(), profile::setBio);
        changed |= setIfChanged(dto.getEducation(), profile.getEducation(), profile::setEducation);
        changed |= setIfChanged(dto.getLanguages(), profile.getLanguages(), profile::setLanguages);
        changed |= setIfChanged(dto.getTargetWorkType(), profile.getTargetWorkType(), profile::setTargetWorkType);
        changed |= setIfChanged(dto.getRawCvText(), profile.getRawCvText(), profile::setRawCvText);

        if (dto.getYearOfStudy() != null) {
            // "" means the user picked "Not specified" and wants the stored year removed; a value
            // outside the canonical list is a client bug and is rejected rather than stored.
            String normalized = normalizeYearOfStudy(dto.getYearOfStudy());
            if (!Objects.equals(normalized, profile.getYearOfStudy())) {
                profile.setYearOfStudy(normalized);
                changed = true;
            }
        }

        if (dto.getYearsOfExperience() != null
                && !Objects.equals(dto.getYearsOfExperience(), profile.getYearsOfExperience())) {
            profile.setYearsOfExperience(dto.getYearsOfExperience());
            changed = true;
        }

        if (dto.getWillingToRelocate() != null
                && !Objects.equals(dto.getWillingToRelocate(), profile.getWillingToRelocate())) {
            profile.setWillingToRelocate(dto.getWillingToRelocate());
            changed = true;
        }

        if (dto.getSkills() != null) {
            String joined = joinList(dto.getSkills());
            if (!Objects.equals(joined, joinList(profile.getSkillList()))) {
                profile.setSkills(joined);
                changed = true;
            }
        }
        if (dto.getTargetRoles() != null) {
            String joined = joinList(dto.getTargetRoles());
            if (!Objects.equals(joined, joinList(profile.getTargetRoleList()))) {
                profile.setTargetRoles(joined);
                changed = true;
            }
        }
        if (dto.getTargetLocations() != null) {
            String joined = joinList(dto.getTargetLocations());
            if (!Objects.equals(joined, joinList(profile.getTargetLocationList()))) {
                profile.setTargetLocations(joined);
                changed = true;
            }
        }

        return changed;
    }

    private boolean setIfChanged(String incoming, String current, java.util.function.Consumer<String> setter) {
        if (incoming == null) {
            return false;
        }
        String normalized = incoming.trim();
        String currentNormalized = current == null ? "" : current.trim();
        if (normalized.equals(currentNormalized)) {
            return false;
        }
        setter.accept(normalized);
        return true;
    }

    private static String joinList(List<String> values) {
        if (values == null) {
            return "";
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return String.join(", ", cleaned);
    }

    /**
     * Maps an incoming year to its canonical form.
     *
     * @return null for null, blank or "Not specified"; the canonical label otherwise
     * @throws ProfileValidationException when the value is outside the canonical list
     */
    public static String normalizeYearOfStudy(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("Not specified")) {
            return null;
        }
        for (String allowed : YEAR_OF_STUDY_VALUES) {
            if (allowed.equalsIgnoreCase(trimmed)) {
                return allowed;
            }
        }
        throw new ProfileValidationException(
                "yearOfStudy must be one of " + YEAR_OF_STUDY_VALUES + ", an empty string to clear it, or omitted.");
    }

    /**
     * Same mapping, but for values a language model produced. A model that answers "third year" or
     * "2024" has failed to follow the contract; that is not a reason to fail the whole CV upload,
     * so the year is simply dropped.
     */
    public static String normalizeYearOfStudyLenient(String raw) {
        try {
            return normalizeYearOfStudy(raw);
        } catch (ProfileValidationException e) {
            log.debug("Discarding unusable yearOfStudy value from an extraction");
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Learning snapshot and progress
    // ---------------------------------------------------------------------

    /** Drops the stored audit/roadmap and every tick that belonged to it. */
    public void clearLearningState(UserProfile profile) {
        profile.setLearningSnapshotJson(null);
        profile.setLearningSnapshotVersion(null);
        profile.setLearningSnapshotProfileKey(null);
        profile.setCompletedMilestones(null);
    }

    /** Self-reported milestone ids, in the order they were ticked. Never null. */
    public List<String> readCompletedMilestones(UserProfile profile) {
        String raw = profile.getCompletedMilestones();
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> parsed = objectMapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            return parsed != null ? new ArrayList<>(parsed) : new ArrayList<>();
        } catch (Exception e) {
            // Legacy or corrupted content. Losing ticks is preferable to failing every profile read.
            log.warn("Could not read completedMilestones for profile {}; treating as empty", profile.getId());
            return new ArrayList<>();
        }
    }

    public String writeCompletedMilestones(List<String> ids) {
        try {
            return objectMapper.writeValueAsString(ids != null ? ids : List.of());
        } catch (Exception e) {
            log.warn("Could not serialise completedMilestones: {}", e.getMessage());
            return "[]";
        }
    }

    /** roadmapId inside the stored snapshot, or null when there is no usable snapshot. */
    public String readSnapshotRoadmapId(UserProfile profile) {
        String raw = profile.getLearningSnapshotJson();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw).path("careerRoadmap").path("roadmapId");
            return node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------

    public ProfileDto toDto(UserProfile entity) {
        ProfileDto dto = new ProfileDto();
        dto.setId(entity.getId());
        dto.setFullName(entity.getFullName());
        dto.setEmail(entity.getEmail());
        dto.setPhone(entity.getPhone());
        dto.setCurrentTitle(entity.getCurrentTitle());
        dto.setIndustry(entity.getIndustry());
        dto.setYearOfStudy(entity.getYearOfStudy());
        dto.setYearsOfExperience(entity.getYearsOfExperience());
        dto.setBio(entity.getBio());
        dto.setSkills(entity.getSkillList());
        dto.setEducation(entity.getEducation());
        dto.setLanguages(entity.getLanguages());
        dto.setTargetRoles(entity.getTargetRoleList());
        dto.setTargetLocations(entity.getTargetLocationList());
        dto.setWillingToRelocate(entity.getWillingToRelocate());
        dto.setTargetWorkType(entity.getTargetWorkType());
        dto.setRawCvText(entity.getRawCvText());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCompletedMilestones(readCompletedMilestones(entity));
        dto.setRoadmapId(readSnapshotRoadmapId(entity));
        return dto;
    }
}
