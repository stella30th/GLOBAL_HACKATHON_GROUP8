package com.gbhackathon.AICareerCode.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbhackathon.AICareerCode.dto.ProfileDto;
import com.gbhackathon.AICareerCode.dto.plan.CareerGoalDto;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    /** The only seniority values a goal may carry; each one changes every required skill level. */
    public static final List<String> SENIORITY_VALUES = List.of("INTERN", "JUNIOR", "MID", "SENIOR");

    /** The only plan lengths offered. A free-text number would produce plans nobody can compare. */
    public static final List<Integer> DURATION_MONTHS_VALUES = List.of(1, 3, 6);

    /**
     * Upper bound on the weekly study budget. Not a judgement about how hard anyone works: a plan
     * generated against 80 hours a week is a plan for a situation that will not hold, and the
     * hours it promises are the one number a student will actually rely on.
     */
    public static final int MAX_HOURS_PER_WEEK = 40;

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

    /** Revision string used as part of the snapshot key. Changes only when content changes. */
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
                    } catch (Exception ignored) {
                        // A row that cannot be removed is harmless; only the newest is ever served.
                    }
                }
            }
            return latest;
        }
        // A blank onboarding profile. An invented default profile would be analysed as though it
        // described the person sitting in front of it, producing a plan for someone who does not exist.
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
        p.setBio("");
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        return profileRepository.save(p);
    }

    // ---------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------

    /**
     * Applies an edit from the input panel.
     *
     * <p>Fields absent from the request keep their stored value, which is what a partial form post
     * needs. Beyond that:
     *
     * <ul>
     *   <li>A save that changes nothing after normalisation is a no-op: {@code updatedAt} does not
     *       move and the stored plan and progress survive. Pressing Save twice used to throw away a
     *       plan the student was working through.</li>
     *   <li>A change to the PROFILE resets the plan, because the analysis was generated from the
     *       old profile and the phase ids belong to it.</li>
     *   <li>A change to the GOAL alone does not reset anything here. The stored plan simply stops
     *       matching the goal key and is no longer served - which means switching back to the
     *       previous goal still finds the plan that was built for it.</li>
     * </ul>
     */
    @Transactional
    public UserProfile saveOrUpdateProfile(ProfileDto dto) {
        UserProfile profile = getCurrentOrCreateProfile();
        boolean profileChanged = applyEditableFields(profile, dto);
        boolean goalChanged = applyGoalFields(profile, dto);

        if (!profileChanged && !goalChanged) {
            log.debug("Profile save was a no-op; keeping revision {}", profileKey(profile));
            return profile;
        }

        if (profileChanged) {
            clearLearningState(profile);
            profile.setUpdatedAt(nextRevisionTimestamp(profile.getUpdatedAt()));
        }
        return profileRepository.save(profile);
    }

    /**
     * The next revision timestamp, guaranteed to be strictly later than the previous one.
     *
     * <p>updatedAt is not decoration, it is the key that decides whether a stored plan still
     * belongs to this profile. Two edits inside the same clock tick would produce the same key and
     * the second edit would be served the first edit's plan. Timestamps are truncated to
     * microseconds because that is what the database columns keep, so the value read back matches
     * the value that was compared against.
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
     * skills, industry and target roles wherever the new CV had nothing to say. A CV describes a
     * whole person, so every extracted field is written, including the empty ones.
     *
     * <p>The goal is deliberately preserved. A student who has already said they are aiming at a
     * junior backend role in three months has not changed their mind by uploading a better CV.
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
        UserProfile saved = replaceProfile(dto, null);
        // A sample carries its own goal so the demo can be generated in one click.
        if (dto.getTargetRole() != null) {
            saved.setTargetRole(dto.getTargetRole());
            saved.setTargetSeniority(normalizeSeniority(dto.getTargetSeniority()));
            saved.setPlanDurationMonths(normalizeDuration(dto.getPlanDurationMonths()));
            saved.setPlanHoursPerWeek(normalizeHoursPerWeek(dto.getPlanHoursPerWeek()));
            saved = profileRepository.save(saved);
        }
        return saved;
    }

    private UserProfile replaceProfile(ProfileDto dto, String rawCvText) {
        UserProfile profile = getCurrentOrCreateProfile();

        profile.setFullName(dto.getFullName());
        profile.setEmail(dto.getEmail());
        profile.setPhone(dto.getPhone());
        profile.setCurrentTitle(dto.getCurrentTitle());
        profile.setIndustry(dto.getIndustry());
        profile.setYearsOfExperience(dto.getYearsOfExperience() != null ? dto.getYearsOfExperience() : 0.0);
        profile.setBio(dto.getBio());
        profile.setEducation(dto.getEducation());
        profile.setLanguages(dto.getLanguages());
        profile.setSkillList(dto.getSkills() != null ? dto.getSkills() : List.of());
        profile.setTargetRoles(dto.getTargetRoles() != null ? String.join(", ", dto.getTargetRoles()) : "");
        profile.setRawCvText(rawCvText);

        clearLearningState(profile);
        profile.setUpdatedAt(nextRevisionTimestamp(profile.getUpdatedAt()));
        return profileRepository.save(profile);
    }

    /**
     * Writes the profile fields the form owns and reports whether anything actually moved.
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
        changed |= setIfChanged(dto.getRawCvText(), profile.getRawCvText(), profile::setRawCvText);

        if (dto.getYearsOfExperience() != null
                && !Objects.equals(dto.getYearsOfExperience(), profile.getYearsOfExperience())) {
            profile.setYearsOfExperience(dto.getYearsOfExperience());
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

        return changed;
    }

    /** Writes the career-goal fields. Returns whether any of them moved. */
    private boolean applyGoalFields(UserProfile profile, ProfileDto dto) {
        boolean changed = false;

        changed |= setIfChanged(dto.getTargetRole(), profile.getTargetRole(), profile::setTargetRole);
        changed |= setIfChanged(dto.getTargetJobDescription(), profile.getTargetJobDescription(),
                profile::setTargetJobDescription);

        if (dto.getTargetSeniority() != null) {
            String normalized = normalizeSeniority(dto.getTargetSeniority());
            if (!Objects.equals(normalized, profile.getTargetSeniority())) {
                profile.setTargetSeniority(normalized);
                changed = true;
            }
        }
        if (dto.getPlanDurationMonths() != null) {
            Integer normalized = normalizeDuration(dto.getPlanDurationMonths());
            if (!Objects.equals(normalized, profile.getPlanDurationMonths())) {
                profile.setPlanDurationMonths(normalized);
                changed = true;
            }
        }
        if (dto.getPlanHoursPerWeek() != null) {
            Integer normalized = normalizeHoursPerWeek(dto.getPlanHoursPerWeek());
            if (!Objects.equals(normalized, profile.getPlanHoursPerWeek())) {
                profile.setPlanHoursPerWeek(normalized);
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

    // ---------------------------------------------------------------------
    // Goal
    // ---------------------------------------------------------------------

    /** @throws ProfileValidationException when the value is outside {@link #SENIORITY_VALUES} */
    public static String normalizeSeniority(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (SENIORITY_VALUES.contains(upper)) {
            return upper;
        }
        throw new ProfileValidationException("targetSeniority must be one of " + SENIORITY_VALUES + ".");
    }

    /** @throws ProfileValidationException when the value is not 1, 3 or 6 */
    public static Integer normalizeDuration(Integer raw) {
        if (raw == null) {
            return null;
        }
        if (DURATION_MONTHS_VALUES.contains(raw)) {
            return raw;
        }
        throw new ProfileValidationException(
                "planDurationMonths must be one of " + DURATION_MONTHS_VALUES + ".");
    }

    /** @throws ProfileValidationException when the value is outside 1..{@value #MAX_HOURS_PER_WEEK} */
    public static Integer normalizeHoursPerWeek(Integer raw) {
        if (raw == null) {
            return null;
        }
        if (raw >= 1 && raw <= MAX_HOURS_PER_WEEK) {
            return raw;
        }
        throw new ProfileValidationException(
                "planHoursPerWeek must be between 1 and " + MAX_HOURS_PER_WEEK + ".");
    }

    /** The stored goal as the pipeline wants it, or null when the student has not set one yet. */
    public CareerGoalDto goalOf(UserProfile profile) {
        if (profile.getTargetRole() == null || profile.getTargetRole().isBlank()) {
            return null;
        }
        CareerGoalDto goal = new CareerGoalDto();
        goal.targetRole = profile.getTargetRole();
        goal.targetSeniority = profile.getTargetSeniority();
        goal.jobDescription = profile.getTargetJobDescription();
        goal.durationMonths = profile.getPlanDurationMonths();
        goal.hoursPerWeek = profile.getPlanHoursPerWeek();
        return goal;
    }

    /**
     * What is still missing before a plan can be generated, in the words the UI shows.
     * An empty list means the inputs are complete.
     */
    public List<String> missingPlanInputs(UserProfile profile) {
        List<String> missing = new ArrayList<>();
        if (profile.getSkillList().isEmpty()
                && (profile.getRawCvText() == null || profile.getRawCvText().isBlank())) {
            missing.add("your profile: upload a CV or list your skills");
        }
        if (profile.getTargetRole() == null || profile.getTargetRole().isBlank()) {
            missing.add("the role you are aiming at");
        }
        if (profile.getTargetSeniority() == null) {
            missing.add("the level you are aiming at");
        }
        if (profile.getPlanDurationMonths() == null) {
            missing.add("how long the plan should be");
        }
        if (profile.getPlanHoursPerWeek() == null) {
            missing.add("how many hours a week you can study");
        }
        return missing;
    }

    // ---------------------------------------------------------------------
    // Plan snapshot and progress
    // ---------------------------------------------------------------------

    /** Drops the stored plan and every tick that belonged to it. */
    public void clearLearningState(UserProfile profile) {
        profile.setLearningSnapshotJson(null);
        profile.setLearningSnapshotVersion(null);
        profile.setLearningSnapshotProfileKey(null);
        profile.setLearningSnapshotGoalKey(null);
        profile.setCompletedMilestones(null);
    }

    /** Self-reported checkable ids, in the order they were ticked. Never null. */
    public List<String> readCompletedMilestones(UserProfile profile) {
        String raw = profile.getCompletedMilestones();
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> parsed = objectMapper.readValue(raw,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
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

    /** planId inside the stored snapshot, or null when there is no usable snapshot. */
    public String readSnapshotPlanId(UserProfile profile) {
        String raw = profile.getLearningSnapshotJson();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw).path("planId");
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
        dto.setYearsOfExperience(entity.getYearsOfExperience());
        dto.setBio(entity.getBio());
        dto.setSkills(entity.getSkillList());
        dto.setEducation(entity.getEducation());
        dto.setLanguages(entity.getLanguages());
        dto.setTargetRoles(entity.getTargetRoleList());
        dto.setRawCvText(entity.getRawCvText());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setTargetRole(entity.getTargetRole());
        dto.setTargetSeniority(entity.getTargetSeniority());
        dto.setTargetJobDescription(entity.getTargetJobDescription());
        dto.setPlanDurationMonths(entity.getPlanDurationMonths());
        dto.setPlanHoursPerWeek(entity.getPlanHoursPerWeek());
        dto.setCompletedMilestones(readCompletedMilestones(entity));
        dto.setPlanId(readSnapshotPlanId(entity));
        return dto;
    }
}
