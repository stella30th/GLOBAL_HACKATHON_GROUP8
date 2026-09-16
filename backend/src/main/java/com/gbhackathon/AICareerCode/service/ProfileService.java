package com.gbhackathon.AICareerCode.service;

import com.gbhackathon.AICareerCode.dto.ProfileDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.repository.UserProfileRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ProfileService {

    private final UserProfileRepository profileRepository;

    public ProfileService(UserProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    public List<UserProfile> getAllProfiles() {
        return profileRepository.findAll();
    }

    public Optional<UserProfile> getProfileById(Long id) {
        return profileRepository.findById(id);
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
        // Create a default initial profile
        UserProfile p = new UserProfile();
        p.setFullName("New Candidate");
        p.setEmail("nguyenvana.tech@example.com");
        p.setPhone("+84 912 345 678");
        p.setCurrentTitle("Backend Engineer (Java / Spring)");
        p.setYearsOfExperience(3.0);
        p.setEducation("B.Sc. in Computer Science");
        p.setLanguages("Vietnamese (Native), English (Professional working)");
        p.setSkills("Java, Spring Boot, MySQL, Docker, Redis, RESTful API, Microservices, Git, AWS");
        p.setTargetRoles("Senior Backend Engineer, Cloud Specialist");
        p.setTargetLocations("Vietnam, Singapore, Remote Worldwide, Germany");
        p.setWillingToRelocate(true);
        p.setTargetWorkType("ANY");
        p.setBio("Upload your CV to replace this placeholder profile with your own details.");
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        return profileRepository.save(p);
    }

    @Transactional
    public UserProfile saveOrUpdateProfile(ProfileDto dto) {
        UserProfile profile = getCurrentOrCreateProfile();

        if (dto.getFullName() != null) profile.setFullName(dto.getFullName());
        if (dto.getEmail() != null) profile.setEmail(dto.getEmail());
        if (dto.getPhone() != null) profile.setPhone(dto.getPhone());
        if (dto.getCurrentTitle() != null) profile.setCurrentTitle(dto.getCurrentTitle());
        if (dto.getIndustry() != null) profile.setIndustry(dto.getIndustry());
        if (dto.getYearsOfExperience() != null) profile.setYearsOfExperience(dto.getYearsOfExperience());
        if (dto.getBio() != null) profile.setBio(dto.getBio());
        if (dto.getEducation() != null) profile.setEducation(dto.getEducation());
        if (dto.getLanguages() != null) profile.setLanguages(dto.getLanguages());
        if (dto.getSkills() != null) profile.setSkillList(dto.getSkills());
        if (dto.getTargetRoles() != null) profile.setTargetRoles(String.join(", ", dto.getTargetRoles()));
        if (dto.getTargetLocations() != null) profile.setTargetLocations(String.join(", ", dto.getTargetLocations()));
        if (dto.getWillingToRelocate() != null) profile.setWillingToRelocate(dto.getWillingToRelocate());
        if (dto.getTargetWorkType() != null) profile.setTargetWorkType(dto.getTargetWorkType());
        if (dto.getRawCvText() != null) profile.setRawCvText(dto.getRawCvText());
        profile.setUpdatedAt(LocalDateTime.now());

        return profileRepository.save(profile);
    }

    /**
     * Replaces the stored profile with the result of a CV upload.
     *
     * <p>{@link #saveOrUpdateProfile} merges field by field, which is right for manual edits but
     * wrong here: uploading a semiconductor CV over a previous software profile would keep the old
     * skills, industry and target roles wherever the new CV had nothing to say. A CV upload
     * describes a whole person, so every extracted field is written, including the empty ones.
     */
    @Transactional
    public UserProfile replaceProfileFromCv(ProfileDto dto) {
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
        profile.setTargetLocations(dto.getTargetLocations() != null ? String.join(", ", dto.getTargetLocations()) : "");
        if (dto.getWillingToRelocate() != null) profile.setWillingToRelocate(dto.getWillingToRelocate());
        if (dto.getTargetWorkType() != null) profile.setTargetWorkType(dto.getTargetWorkType());
        profile.setRawCvText(dto.getRawCvText());
        profile.setUpdatedAt(LocalDateTime.now());

        return profileRepository.save(profile);
    }

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
        dto.setTargetLocations(entity.getTargetLocationList());
        dto.setWillingToRelocate(entity.getWillingToRelocate());
        dto.setTargetWorkType(entity.getTargetWorkType());
        dto.setRawCvText(entity.getRawCvText());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
