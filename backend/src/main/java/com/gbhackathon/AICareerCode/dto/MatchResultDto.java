package com.gbhackathon.AICareerCode.dto;

import java.util.List;

public class MatchResultDto {
    private JobDto job;
    private int overallScore;      // 0 - 100%
    private int skillsScore;       // 0 - 100%
    private int experienceScore;   // 0 - 100%
    private int relocationScore;   // 0 - 100%
    private int domainScore;       // 0 - 100%: how close the role is to the candidate's profession
    private List<String> matchedSkills;
    private List<String> missingSkills;
    private String visaSuitability; // e.g. "Remote role - no visa required", "Visa sponsorship stated"
    private String aiSummary;
    private List<String> actionItems;

    public JobDto getJob() {
        return job;
    }

    public void setJob(JobDto job) {
        this.job = job;
    }

    public int getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(int overallScore) {
        this.overallScore = overallScore;
    }

    public int getSkillsScore() {
        return skillsScore;
    }

    public void setSkillsScore(int skillsScore) {
        this.skillsScore = skillsScore;
    }

    public int getExperienceScore() {
        return experienceScore;
    }

    public void setExperienceScore(int experienceScore) {
        this.experienceScore = experienceScore;
    }

    public int getRelocationScore() {
        return relocationScore;
    }

    public int getDomainScore() {
        return domainScore;
    }

    public void setDomainScore(int domainScore) {
        this.domainScore = domainScore;
    }

    public void setRelocationScore(int relocationScore) {
        this.relocationScore = relocationScore;
    }

    public List<String> getMatchedSkills() {
        return matchedSkills;
    }

    public void setMatchedSkills(List<String> matchedSkills) {
        this.matchedSkills = matchedSkills;
    }

    public List<String> getMissingSkills() {
        return missingSkills;
    }

    public void setMissingSkills(List<String> missingSkills) {
        this.missingSkills = missingSkills;
    }

    public String getVisaSuitability() {
        return visaSuitability;
    }

    public void setVisaSuitability(String visaSuitability) {
        this.visaSuitability = visaSuitability;
    }

    public String getAiSummary() {
        return aiSummary;
    }

    public void setAiSummary(String aiSummary) {
        this.aiSummary = aiSummary;
    }

    public List<String> getActionItems() {
        return actionItems;
    }

    public void setActionItems(List<String> actionItems) {
        this.actionItems = actionItems;
    }
}
