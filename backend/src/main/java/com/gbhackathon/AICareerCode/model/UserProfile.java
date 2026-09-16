package com.gbhackathon.AICareerCode.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The student's profile, their career goal, and the plan generated from the two.
 *
 * <p>Columns from the job-matching feature - target locations, relocation preference, work type -
 * and the year-of-study field are no longer mapped. Removing a field from an entity does not drop
 * a column under {@code ddl-auto: update}, so existing rows keep their data and can be read by
 * anything that still wants it; the application simply stops using them.
 */
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

    /** Roles the CV names as an objective. Offered as suggestions for the goal below. */
    @Column(columnDefinition = "TEXT")
    private String targetRoles;

    @Column(columnDefinition = "TEXT")
    private String rawCvText;

    // ------------------------------------------------------------------
    // Career goal.
    //
    // Stored on the profile rather than passed per request because a plan is only meaningful
    // against the goal it was built for. Keeping the goal here lets a reload reproduce exactly
    // which question the stored plan answers, and lets the snapshot key notice when it changes.
    // ------------------------------------------------------------------

    private String targetRole;

    /** INTERN, JUNIOR, MID or SENIOR. */
    private String targetSeniority;

    /** A job advert the student pasted. Data for the analysis, never treated as instructions. */
    @Column(columnDefinition = "TEXT")
    private String targetJobDescription;

    /** 1, 3 or 6. */
    private Integer planDurationMonths;

    private Integer planHoursPerWeek;

    // ------------------------------------------------------------------
    // Generated plan and self-reported progress.
    //
    // Stored so phase and activity ids survive a reload, a cache eviction and a restart. Without
    // it the plan was regenerated whenever memory lost it, and every tick pointed at an id that
    // no longer existed.
    // ------------------------------------------------------------------

    /** Serialised {@code LearningPlanDto}. Never exposed through ProfileDto. */
    @Column(columnDefinition = "TEXT")
    private String learningSnapshotJson;

    /** Pipeline / storage contract version, so an incompatible older plan can be discarded. */
    private Integer learningSnapshotVersion;

    /** The profile revision ({@code id@updatedAt}) the plan was generated from. */
    private String learningSnapshotProfileKey;

    /**
     * The goal the plan was generated for. A plan for a three-month backend path is not an answer
     * to a six-month data path, so a changed goal invalidates it exactly as a changed CV does.
     */
    private String learningSnapshotGoalKey;

    /** JSON array of checkable ids the user self-reported as done. Null reads as an empty list. */
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
    // the stored plan and the chat session, so it must change only when the profile content really
    // changes. A lifecycle hook would also bump it when the only thing written was a ticked
    // checkbox, silently discarding the plan that tick belonged to. ProfileService sets updatedAt
    // explicitly on the paths that are real content edits.

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

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public String getTargetRole() {
        return targetRole;
    }

    public void setTargetRole(String targetRole) {
        this.targetRole = targetRole;
    }

    public String getTargetSeniority() {
        return targetSeniority;
    }

    public void setTargetSeniority(String targetSeniority) {
        this.targetSeniority = targetSeniority;
    }

    public String getTargetJobDescription() {
        return targetJobDescription;
    }

    public void setTargetJobDescription(String targetJobDescription) {
        this.targetJobDescription = targetJobDescription;
    }

    public Integer getPlanDurationMonths() {
        return planDurationMonths;
    }

    public void setPlanDurationMonths(Integer planDurationMonths) {
        this.planDurationMonths = planDurationMonths;
    }

    public Integer getPlanHoursPerWeek() {
        return planHoursPerWeek;
    }

    public void setPlanHoursPerWeek(Integer planHoursPerWeek) {
        this.planHoursPerWeek = planHoursPerWeek;
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

    public String getLearningSnapshotGoalKey() {
        return learningSnapshotGoalKey;
    }

    public void setLearningSnapshotGoalKey(String learningSnapshotGoalKey) {
        this.learningSnapshotGoalKey = learningSnapshotGoalKey;
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
