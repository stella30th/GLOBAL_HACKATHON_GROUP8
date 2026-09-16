package com.gbhackathon.AICareerCode.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Entity
@Table(name = "job_opportunities")
public class JobOpportunity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String company;
    private String companyLogo;
    private String location; // e.g. "Singapore", "Berlin, Germany", "Ho Chi Minh City"
    private String country;  // e.g. "Singapore", "Germany", "Vietnam", "United States", "Japan", "Global"
    
    private Boolean isOverseas = false;
    private String workType = "REMOTE"; // REMOTE, HYBRID, ONSITE
    private String salaryRange;
    private String experienceLevel; // Junior, Mid-level, Senior, Lead
    private Integer minYearsExp = 0;

    @Column(columnDefinition = "TEXT")
    private String requiredSkills; // Comma-separated

    @Column(columnDefinition = "TEXT")
    private String preferredSkills; // Comma-separated

    private Boolean visaSponsorship = false;
    private Boolean relocationAssistance = false;
    private String languageRequirements; // e.g. "English (Fluent)", "Japanese (N2)"

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String requirements;

    @Column(columnDefinition = "TEXT")
    private String benefits;

    private String applyUrl;
    private String source; // e.g. "Remotive", "Jobicy", "The Muse", "Arbeitnow"
    private String category; // Industry taxonomy from the source board, e.g. "Healthcare"

    private LocalDateTime postedAt;

    @PrePersist
    public void prePersist() {
        if (postedAt == null) {
            postedAt = LocalDateTime.now();
        }
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<String> getRequiredSkillList() {
        if (requiredSkills == null || requiredSkills.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(requiredSkills.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public void setRequiredSkillList(List<String> list) {
        if (list == null || list.isEmpty()) {
            this.requiredSkills = "";
        } else {
            this.requiredSkills = String.join(", ", list);
        }
    }

    public List<String> getPreferredSkillList() {
        if (preferredSkills == null || preferredSkills.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(preferredSkills.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public void setPreferredSkillList(List<String> list) {
        if (list == null || list.isEmpty()) {
            this.preferredSkills = "";
        } else {
            this.preferredSkills = String.join(", ", list);
        }
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getCompanyLogo() {
        return companyLogo;
    }

    public void setCompanyLogo(String companyLogo) {
        this.companyLogo = companyLogo;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public Boolean getIsOverseas() {
        return isOverseas;
    }

    public void setIsOverseas(Boolean isOverseas) {
        this.isOverseas = isOverseas;
    }

    public String getWorkType() {
        return workType;
    }

    public void setWorkType(String workType) {
        this.workType = workType;
    }

    public String getSalaryRange() {
        return salaryRange;
    }

    public void setSalaryRange(String salaryRange) {
        this.salaryRange = salaryRange;
    }

    public String getExperienceLevel() {
        return experienceLevel;
    }

    public void setExperienceLevel(String experienceLevel) {
        this.experienceLevel = experienceLevel;
    }

    public Integer getMinYearsExp() {
        return minYearsExp;
    }

    public void setMinYearsExp(Integer minYearsExp) {
        this.minYearsExp = minYearsExp;
    }

    public String getRequiredSkills() {
        return requiredSkills;
    }

    public void setRequiredSkills(String requiredSkills) {
        this.requiredSkills = requiredSkills;
    }

    public String getPreferredSkills() {
        return preferredSkills;
    }

    public void setPreferredSkills(String preferredSkills) {
        this.preferredSkills = preferredSkills;
    }

    public Boolean getVisaSponsorship() {
        return visaSponsorship;
    }

    public void setVisaSponsorship(Boolean visaSponsorship) {
        this.visaSponsorship = visaSponsorship;
    }

    public Boolean getRelocationAssistance() {
        return relocationAssistance;
    }

    public void setRelocationAssistance(Boolean relocationAssistance) {
        this.relocationAssistance = relocationAssistance;
    }

    public String getLanguageRequirements() {
        return languageRequirements;
    }

    public void setLanguageRequirements(String languageRequirements) {
        this.languageRequirements = languageRequirements;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRequirements() {
        return requirements;
    }

    public void setRequirements(String requirements) {
        this.requirements = requirements;
    }

    public String getBenefits() {
        return benefits;
    }

    public void setBenefits(String benefits) {
        this.benefits = benefits;
    }

    public String getApplyUrl() {
        return applyUrl;
    }

    public void setApplyUrl(String applyUrl) {
        this.applyUrl = applyUrl;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(LocalDateTime postedAt) {
        this.postedAt = postedAt;
    }
}
