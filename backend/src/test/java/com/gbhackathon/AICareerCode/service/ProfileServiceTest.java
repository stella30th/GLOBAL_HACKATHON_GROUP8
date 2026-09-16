package com.gbhackathon.AICareerCode.service;

import com.gbhackathon.AICareerCode.dto.ProfileDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The profile contract the rest of the product depends on: which values a year of study may take,
 * and exactly when a save is allowed to throw away the analysis and the progress built on it.
 */
@DataJpaTest
@Import(ProfileService.class)
class ProfileServiceTest {

    @Autowired
    private ProfileService profileService;

    @Autowired
    private UserProfileRepository profileRepository;

    private ProfileDto baseDto() {
        ProfileDto dto = new ProfileDto();
        dto.setFullName("Mai Tran");
        dto.setEmail("mai.tran.student@example.com");
        dto.setCurrentTitle("Software Engineering Student");
        dto.setIndustry("Software Engineering");
        dto.setYearOfStudy("Year 2");
        dto.setYearsOfExperience(0.0);
        dto.setSkills(List.of("Java", "Git"));
        dto.setTargetRoles(List.of("Software Engineering Intern"));
        dto.setTargetLocations(List.of("Vietnam"));
        dto.setWillingToRelocate(false);
        dto.setTargetWorkType("ANY");
        dto.setBio("Second-year student.");
        return dto;
    }

    /** Pretends an analysis has already been generated and ticked, so resets are observable. */
    private void giveItASnapshot(UserProfile profile) {
        profile.setLearningSnapshotJson("{\"careerRoadmap\":{\"roadmapId\":\"rm-1\"}}");
        profile.setLearningSnapshotVersion(LearningSnapshotService.SNAPSHOT_VERSION);
        profile.setLearningSnapshotProfileKey(ProfileService.profileKey(profile));
        profile.setCompletedMilestones("[\"m-1\"]");
        profileRepository.save(profile);
    }

    @Test
    void yearOfStudyRoundTripsAndCanBeCleared() {
        UserProfile saved = profileService.saveOrUpdateProfile(baseDto());
        assertEquals("Year 2", saved.getYearOfStudy());
        assertEquals("Year 2", profileService.toDto(saved).getYearOfStudy());

        // An omitted field keeps the stored value; the form posts partial objects.
        ProfileDto omitted = new ProfileDto();
        omitted.setBio("Updated bio");
        assertEquals("Year 2", profileService.saveOrUpdateProfile(omitted).getYearOfStudy());

        // An empty string is the "Not specified" option, and it clears the stored year.
        ProfileDto cleared = new ProfileDto();
        cleared.setYearOfStudy("");
        assertNull(profileService.saveOrUpdateProfile(cleared).getYearOfStudy());
    }

    @Test
    void yearOfStudyOutsideTheCanonicalListIsRejected() {
        ProfileDto dto = baseDto();
        dto.setYearOfStudy("third year");
        assertThrows(ProfileValidationException.class, () -> profileService.saveOrUpdateProfile(dto));

        // The same value from a model is dropped instead, so one bad field cannot fail a CV upload.
        assertNull(ProfileService.normalizeYearOfStudyLenient("third year"));
        assertEquals("Year 3", ProfileService.normalizeYearOfStudyLenient("year 3"));
    }

    @Test
    void savingTheSameValuesTwiceKeepsTheAnalysisAndTheProgress() {
        UserProfile saved = profileService.saveOrUpdateProfile(baseDto());
        giveItASnapshot(saved);
        var revisionBefore = saved.getUpdatedAt();

        UserProfile again = profileService.saveOrUpdateProfile(baseDto());

        assertEquals(revisionBefore, again.getUpdatedAt(), "a no-op save must not move the revision");
        assertNotNull(again.getLearningSnapshotJson());
        assertEquals(List.of("m-1"), profileService.readCompletedMilestones(again));
    }

    @Test
    void changingAnyEditableFieldResetsTheAnalysisAndTheProgress() {
        UserProfile saved = profileService.saveOrUpdateProfile(baseDto());
        giveItASnapshot(saved);
        var revisionBefore = saved.getUpdatedAt();

        // Even a contact field counts: the MVP invalidates on any real content change.
        ProfileDto edit = new ProfileDto();
        edit.setPhone("+84 900 000 002");
        UserProfile after = profileService.saveOrUpdateProfile(edit);

        assertTrue(after.getUpdatedAt().isAfter(revisionBefore));
        assertNull(after.getLearningSnapshotJson());
        assertNull(after.getLearningSnapshotProfileKey());
        assertTrue(profileService.readCompletedMilestones(after).isEmpty());
    }

    @Test
    void progressFieldsOnTheIncomingDtoAreIgnored() {
        UserProfile saved = profileService.saveOrUpdateProfile(baseDto());
        giveItASnapshot(saved);

        ProfileDto forged = baseDto();
        forged.setCompletedMilestones(List.of("m-1", "m-2", "not-a-real-milestone"));
        forged.setRoadmapId("rm-forged");
        UserProfile after = profileService.saveOrUpdateProfile(forged);

        assertEquals(List.of("m-1"), profileService.readCompletedMilestones(after));
        assertEquals("rm-1", profileService.readSnapshotRoadmapId(after));
    }

    @Test
    void loadingASampleReplacesEverythingIncludingThePreviousCv() {
        ProfileDto withCv = baseDto();
        withCv.setRawCvText("A previous candidate's CV text, long enough to matter.");
        profileService.replaceProfileFromCv(withCv);

        ProfileDto sample = new ProfileDto();
        sample.setFullName("Khoa Pham");
        sample.setIndustry("Software Engineering");
        sample.setYearOfStudy("Year 4");
        sample.setSkills(List.of("Spring Boot"));
        UserProfile after = profileService.replaceProfileFromSample(sample);

        assertNull(after.getRawCvText(), "a sample must not inherit the previous person's CV");
        assertEquals("Khoa Pham", after.getFullName());
        assertEquals("Year 4", after.getYearOfStudy());
        assertTrue(after.getTargetRoleList().isEmpty(), "unset fields are cleared, not merged");
    }

    @Test
    void aCvWithoutAYearClearsThePreviousOne() {
        profileService.saveOrUpdateProfile(baseDto());

        ProfileDto parsed = baseDto();
        parsed.setYearOfStudy(null);
        parsed.setRawCvText("Some CV text");
        UserProfile after = profileService.replaceProfileFromCv(parsed);

        assertNull(after.getYearOfStudy(), "a silent CV must not keep the year from the old profile");
    }

    @Test
    void aProfileWithNoStoredProgressReadsAsEmptyRatherThanFailing() {
        UserProfile saved = profileService.saveOrUpdateProfile(baseDto());
        saved.setCompletedMilestones(null);
        assertTrue(profileService.readCompletedMilestones(saved).isEmpty());

        // A legacy row could hold anything; it must not break every profile read.
        saved.setCompletedMilestones("Close the skill gaps, Rewrite the CV");
        assertTrue(profileService.readCompletedMilestones(saved).isEmpty());
        assertNull(profileService.readSnapshotRoadmapId(saved));
    }
}
