package com.gbhackathon.AICareerCode.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The profile and career goal as the browser sees them.
 *
 * <p>{@code rawCvText} travels with it so the extraction screen can show the student what the
 * model actually read and let them correct it. {@code completedMilestones} and {@code planId} are
 * read-only here: progress moves only through the dedicated endpoint, which validates each tick
 * against the current plan.
 */
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
    /** Roles the CV named as an objective; offered as suggestions for {@link #targetRole}. */
    private List<String> targetRoles;
    private String rawCvText;
    private LocalDateTime updatedAt;

    // ---- career goal ----
    private String targetRole;
    private String targetSeniority;
    private String targetJobDescription;
    private Integer planDurationMonths;
    private Integer planHoursPerWeek;

    // ---- read-only, set by the server ----

    /** Checkable ids the student has ticked. Ignored on the way in. */
    private List<String> completedMilestones;

    /** Id of the stored plan these ticks belong to, or null when there is no current plan. */
    private String planId;

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

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
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

    public List<String> getCompletedMilestones() {
        return completedMilestones;
    }

    public void setCompletedMilestones(List<String> completedMilestones) {
        this.completedMilestones = completedMilestones;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }
}
