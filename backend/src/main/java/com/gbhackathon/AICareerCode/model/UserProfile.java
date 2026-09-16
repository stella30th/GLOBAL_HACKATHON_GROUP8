package com.gbhackathon.AICareerCode.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fullName;
    private String email;
    private String phone;
    private String currentTitle;
    private String industry; // e.g. "Semiconductor / IC Design", "Finance & Accounting"
    private Double yearsOfExperience;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(columnDefinition = "TEXT")
    private String skills; // Comma separated: "Java, Spring Boot, MySQL, Docker"

    private String education;

    @Column(columnDefinition = "TEXT")
    private String languages; // e.g. "English (Fluent), Vietnamese (Native)"

    @Column(columnDefinition = "TEXT")
    private String targetRoles; // e.g. "Backend Engineer, Cloud Architect"

    @Column(columnDefinition = "TEXT")
    private String targetLocations; // e.g. "Vietnam, Singapore, Remote Worldwide, Germany"

    private Boolean willingToRelocate = false;
    private String targetWorkType = "ANY"; // ANY, REMOTE, HYBRID, ONSITE

    @Column(columnDefinition = "TEXT")
    private String rawCvText;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public List<String> getSkillList() {
        if (skills == null || skills.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(skills.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public void setSkillList(List<String> list) {
        if (list == null || list.isEmpty()) {
            this.skills = "";
        } else {
            this.skills = String.join(", ", list);
        }
    }

    public List<String> getTargetLocationList() {
        if (targetLocations == null || targetLocations.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(targetLocations.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public List<String> getTargetRoleList() {
        if (targetRoles == null || targetRoles.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(targetRoles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    // Getters and Setters
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

    public String getSkills() {
        return skills;
    }

    public void setSkills(String skills) {
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

    public String getTargetRoles() {
        return targetRoles;
    }

    public void setTargetRoles(String targetRoles) {
        this.targetRoles = targetRoles;
    }

    public String getTargetLocations() {
        return targetLocations;
    }

    public void setTargetLocations(String targetLocations) {
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
