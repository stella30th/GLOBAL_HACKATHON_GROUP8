package com.gbhackathon.AICareerCode.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CareerRoadmapDto {
    /**
     * Server-issued UUID identifying this exact roadmap. The model never supplies it: the backend
     * assigns ids after generation and before the snapshot is stored, so a milestone id means the
     * same thing across a reload, a cache eviction and a restart.
     */
    private String roadmapId;
    private String targetGoal;
    @JsonAlias({"month3", "first3Months", "months_3"})
    private List<RoadmapMilestone> months3;
    @JsonAlias({"month6", "first6Months", "months_6"})
    private List<RoadmapMilestone> months6;
    @JsonAlias({"month12", "first12Months", "months_12"})
    private List<RoadmapMilestone> months12;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RoadmapMilestone {
        /** Server-issued UUID. Never an array index or the title, both of which change wording. */
        private String id;
        private String title;
        private String description;
        @JsonAlias({"type", "milestoneType"})
        private String category; // "SKILL", "CERTIFICATION", "PROJECT", "NETWORKING"
        @JsonAlias({"hours", "effort", "estimated_hours"})
        private String estimatedHours;

        public RoadmapMilestone() {}

        public RoadmapMilestone(String title, String description, String category, String estimatedHours) {
            this.title = title;
            this.description = description;
            this.category = category;
            this.estimatedHours = estimatedHours;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getEstimatedHours() {
            return estimatedHours;
        }

        public void setEstimatedHours(String estimatedHours) {
            this.estimatedHours = estimatedHours;
        }
    }

    public String getRoadmapId() {
        return roadmapId;
    }

    public void setRoadmapId(String roadmapId) {
        this.roadmapId = roadmapId;
    }

    /** Every milestone across the three stages, in display order. */
    public List<RoadmapMilestone> allMilestones() {
        List<RoadmapMilestone> all = new java.util.ArrayList<>();
        for (List<RoadmapMilestone> stage : List.of(
                months3 != null ? months3 : List.<RoadmapMilestone>of(),
                months6 != null ? months6 : List.<RoadmapMilestone>of(),
                months12 != null ? months12 : List.<RoadmapMilestone>of())) {
            all.addAll(stage);
        }
        return all;
    }

    public String getTargetGoal() {
        return targetGoal;
    }

    public void setTargetGoal(String targetGoal) {
        this.targetGoal = targetGoal;
    }

    public List<RoadmapMilestone> getMonths3() {
        return months3;
    }

    public void setMonths3(List<RoadmapMilestone> months3) {
        this.months3 = months3;
    }

    public List<RoadmapMilestone> getMonths6() {
        return months6;
    }

    public void setMonths6(List<RoadmapMilestone> months6) {
        this.months6 = months6;
    }

    public List<RoadmapMilestone> getMonths12() {
        return months12;
    }

    public void setMonths12(List<RoadmapMilestone> months12) {
        this.months12 = months12;
    }
}
