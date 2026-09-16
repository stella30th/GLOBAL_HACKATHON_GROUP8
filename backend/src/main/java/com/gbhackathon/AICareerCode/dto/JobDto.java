package com.gbhackathon.AICareerCode.dto;

import java.time.LocalDateTime;
import java.util.List;

public class JobDto {
    private Long id;
    private String title;
    private String company;
    private String companyLogo;
    private String location;
    private String country;
    private Boolean isOverseas;
    private String workType;
    private String salaryRange;
    private String experienceLevel;
    private Integer minYearsExp;
    private List<String> requiredSkills;
    private List<String> preferredSkills;
    private Boolean visaSponsorship;
    private Boolean relocationAssistance;
    private String languageRequirements;
    private String description;
    private String requirements;
    private String benefits;
    private String applyUrl;
    private String source;
    private String category;
    private LocalDateTime postedAt;

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

    public List<String> getRequiredSkills() {
        return requiredSkills;
    }

    public void setRequiredSkills(List<String> requiredSkills) {
        this.requiredSkills = requiredSkills;
    }

    public List<String> getPreferredSkills() {
        return preferredSkills;
    }

    public void setPreferredSkills(List<String> preferredSkills) {
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public LocalDateTime getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(LocalDateTime postedAt) {
        this.postedAt = postedAt;
    }
}
