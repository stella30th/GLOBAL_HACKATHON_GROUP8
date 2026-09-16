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

    /**
     * Canonical values only: "Year 1" ... "Year 5+", or null when it is not known.
     * Nullable on purpose - the product serves students of every year and people who are not
     * students at all, so an unknown year stays unknown rather than being guessed.
     */
    private String yearOfStudy;

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

    // ------------------------------------------------------------------
    // Learning snapshot and self-reported progress.
    //
    // The audit and its embedded roadmap are stored here so milestone ids survive a reload, a
    // cache eviction and a backend restart. Without a stored snapshot the roadmap was regenerated
    // whenever the in-memory cache lost it, and every previously ticked milestone pointed at an
    // id that no longer existed.
    // ------------------------------------------------------------------

    /** Serialised {@code ResumeAuditDto}, roadmap included. Never exposed through ProfileDto. */
    @Column(columnDefinition = "TEXT")
    private String learningSnapshotJson;

    /** Serialisation / prompt contract version, so an incompatible old snapshot can be discarded. */
    private Integer learningSnapshotVersion;

    /** The profile revision ({@code id@updatedAt}) the snapshot was generated from. */
    private String learningSnapshotProfileKey;

    /** JSON array of milestone ids the user self-reported as done. Null reads as an empty list. */
    @Column(columnDefinition = "TEXT")
    private String completedMilestones;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    // There is deliberately no @PreUpdate hook. updatedAt is the profile revision that invalidates
    // the learning snapshot, the AI caches and the chat session, so it must change only when the
    // profile content really changes. A lifecycle hook would also bump it when the only thing
    // written was a ticked checkbox, silently discarding the roadmap that tick belonged to.
    // ProfileService sets updatedAt explicitly on the paths that are real content edits.

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

    public String getYearOfStudy() {
        return yearOfStudy;
    }

    public void setYearOfStudy(String yearOfStudy) {
        this.yearOfStudy = yearOfStudy;
    }

    public String getLearningSnapshotJson() {
        return learningSnapshotJson;
    }

    public void setLearningSnapshotJson(String learningSnapshotJson) {
        this.learningSnapshotJson = learningSnapshotJson;
    }

    public Integer getLearningSnapshotVersion() {
        return learningSnapshotVersion;
    }

    public void setLearningSnapshotVersion(Integer learningSnapshotVersion) {
        this.learningSnapshotVersion = learningSnapshotVersion;
    }

    public String getLearningSnapshotProfileKey() {
        return learningSnapshotProfileKey;
    }

    public void setLearningSnapshotProfileKey(String learningSnapshotProfileKey) {
        this.learningSnapshotProfileKey = learningSnapshotProfileKey;
    }

    public String getCompletedMilestones() {
        return completedMilestones;
    }

    public void setCompletedMilestones(String completedMilestones) {
        this.completedMilestones = completedMilestones;
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
