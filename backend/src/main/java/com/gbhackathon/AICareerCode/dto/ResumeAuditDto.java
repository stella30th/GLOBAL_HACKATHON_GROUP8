package com.gbhackathon.AICareerCode.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Tolerates the naming variations a language model produces. Without the aliases below a Gemini
 * response that used "originalBullet" instead of "original" failed to deserialize entirely, and the
 * whole audit silently fell back to canned text.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResumeAuditDto {
    private int healthScore; // 0 - 100
    private String verdict;   // "Cần cải thiện", "Khá tốt", "Xuất sắc chuẩn quốc tế"
    private String summary;
    private List<String> strengths;
    private List<String> weaknesses;
    @JsonAlias({"keywordsPresent", "presentKeywords"})
    private List<String> atsKeywordsPresent;
    @JsonAlias({"keywordsMissing", "missingKeywords"})
    private List<String> atsKeywordsMissing;
    private List<BulletImprovement> bulletImprovements;
    @JsonAlias({"roadmap", "progressionRoadmap"})
    private CareerRoadmapDto careerRoadmap;

    // Provenance, set by the server rather than the model: "gemini" or "offline". The UI used to
    // claim every audit was "Powered by Gemini 3.5 Flash" even when it was rule-based placeholder text.
    private String generatedBy;
    private String model;
    private String offlineReason;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BulletImprovement {
        @JsonAlias({"originalBullet", "before", "weakBullet"})
        private String original;
        @JsonAlias({"improvedBullet", "after", "rewrittenBullet"})
        private String improved;
        @JsonAlias({"explanation", "why", "reason"})
        private String rationale;

        public BulletImprovement() {}

        public BulletImprovement(String original, String improved, String rationale) {
            this.original = original;
            this.improved = improved;
            this.rationale = rationale;
        }

        public String getOriginal() {
            return original;
        }

        public void setOriginal(String original) {
            this.original = original;
        }

        public String getImproved() {
            return improved;
        }

        public void setImproved(String improved) {
            this.improved = improved;
        }

        public String getRationale() {
            return rationale;
        }

        public void setRationale(String rationale) {
            this.rationale = rationale;
        }
    }

    public int getHealthScore() {
        return healthScore;
    }

    public void setHealthScore(int healthScore) {
        this.healthScore = healthScore;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getStrengths() {
        return strengths;
    }

    public void setStrengths(List<String> strengths) {
        this.strengths = strengths;
    }

    public List<String> getWeaknesses() {
        return weaknesses;
    }

    public void setWeaknesses(List<String> weaknesses) {
        this.weaknesses = weaknesses;
    }

    public List<String> getAtsKeywordsPresent() {
        return atsKeywordsPresent;
    }

    public void setAtsKeywordsPresent(List<String> atsKeywordsPresent) {
        this.atsKeywordsPresent = atsKeywordsPresent;
    }

    public List<String> getAtsKeywordsMissing() {
        return atsKeywordsMissing;
    }

    public void setAtsKeywordsMissing(List<String> atsKeywordsMissing) {
        this.atsKeywordsMissing = atsKeywordsMissing;
    }

    public List<BulletImprovement> getBulletImprovements() {
        return bulletImprovements;
    }

    public void setBulletImprovements(List<BulletImprovement> bulletImprovements) {
        this.bulletImprovements = bulletImprovements;
    }

    public String getGeneratedBy() {
        return generatedBy;
    }

    public void setGeneratedBy(String generatedBy) {
        this.generatedBy = generatedBy;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getOfflineReason() {
        return offlineReason;
    }

    public void setOfflineReason(String offlineReason) {
        this.offlineReason = offlineReason;
    }

    public CareerRoadmapDto getCareerRoadmap() {
        return careerRoadmap;
    }

    public void setCareerRoadmap(CareerRoadmapDto careerRoadmap) {
        this.careerRoadmap = careerRoadmap;
    }
}
