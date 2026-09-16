package com.gbhackathon.AICareerCode.dto;

import java.time.LocalDateTime;
import java.util.List;

public class ProfileDto {
    private Long id;
    private String fullName;
    private String email;
    private String phone;
    private String currentTitle;
    private String industry;
    private Double yearsOfExperience;
    private String bio;
    private List<String> skills;
    private String education;
    private String languages;
    private List<String> targetRoles;
    private List<String> targetLocations;
    private Boolean willingToRelocate;
    private String targetWorkType;
    private String rawCvText;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getCurrentTitle() {
        return currentTitle;
    }

    public void setCurrentTitle(String currentTitle) {
        this.currentTitle = currentTitle;
    }

    public Double getYearsOfExperience() {
        return yearsOfExperience;
    }

    public void setYearsOfExperience(Double yearsOfExperience) {
        this.yearsOfExperience = yearsOfExperience;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public List<String> getSkills() {
        return skills;
    }

    public void setSkills(List<String> skills) {
        this.skills = skills;
    }

    public String getEducation() {
        return education;
    }

    public void setEducation(String education) {
        this.education = education;
    }

    public String getLanguages() {
        return languages;
    }

    public void setLanguages(String languages) {
        this.languages = languages;
    }

    public List<String> getTargetRoles() {
        return targetRoles;
    }

    public void setTargetRoles(List<String> targetRoles) {
        this.targetRoles = targetRoles;
    }

    public List<String> getTargetLocations() {
        return targetLocations;
    }

    public void setTargetLocations(List<String> targetLocations) {
        this.targetLocations = targetLocations;
    }

    public Boolean getWillingToRelocate() {
        return willingToRelocate;
    }

    public void setWillingToRelocate(Boolean willingToRelocate) {
        this.willingToRelocate = willingToRelocate;
    }

    public String getTargetWorkType() {
        return targetWorkType;
    }

    public void setTargetWorkType(String targetWorkType) {
        this.targetWorkType = targetWorkType;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public String getRawCvText() {
        return rawCvText;
    }

    public void setRawCvText(String rawCvText) {
        this.rawCvText = rawCvText;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
