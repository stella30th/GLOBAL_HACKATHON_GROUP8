package com.gbhackathon.AICareerCode.dto.plan;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * The learning path: ordered phases, each with skills, work to do, hours and sources.
 *
 * <p>The path is sized against a real budget. {@link #budgetHours} is the duration multiplied by
 * the weekly hours the student said they have; {@link #plannedHours} is what the phases add up to.
 * When the second exceeds the first the plan is not quietly trimmed and it is not presented as
 * achievable - {@link #feasibility} says what fits and what does not, because a plan that promises
 * a career-level outcome in a fixed number of weeks is the most damaging thing this product could
 * produce.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LearningPathDto {

    public Integer durationMonths;
    public Integer hoursPerWeek;

    /** Study hours available, computed by the server from duration and weekly hours. */
    public Integer budgetHours;

    /** Study hours the phases add up to, summed by the server rather than trusted from the model. */
    public Integer plannedHours;

    public Feasibility feasibility;

    /** What the student should realistically be able to show at the end. Not a job guarantee. */
    @JsonAlias({"outcome", "targetOutcome"})
    public String expectedOutcome;

    public List<Phase> phases;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Feasibility {
        /** FITS, TIGHT or NOT_ACHIEVABLE. */
        public String verdict;
        /** Plain-language explanation, including what was left out when it does not fit. */
        public String note;
        /** Skills the model judged cannot be reached in this budget, named rather than dropped. */
        public List<String> outOfScope;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Phase {
        /** Server-issued UUID. Progress is recorded against this, so the model never sets it. */
        public String id;
        public Integer order;
        public String title;
        /** What the student will be able to do by the end of this phase. */
        @JsonAlias({"objective", "aim"})
        public String goal;
        public Integer startWeek;
        public Integer endWeek;
        public Integer estimatedHours;

        /** Skill labels taught in this phase, in the wording shown to the student. */
        public List<String> skills;

        /**
         * Ids of the knowledge-graph nodes this phase teaches.
         *
         * <p>Ids rather than labels, for the same reason {@link #addressesGapIds} uses ids. The
         * graph is produced by one model call and the phases by another; matching them on skill
         * names means comparing two independently written strings, and "REST API design" against
         * "Designing REST APIs" silently defeats the prerequisite check that the whole graph step
         * exists to enable. An id either matches a node or is rejected.
         */
        @JsonAlias({"nodeIds", "graphNodeIds"})
        public List<String> skillNodeIds;

        /** Skills that must already be in place, drawn from the graph's prerequisite edges. */
        @JsonAlias({"prerequisites"})
        public List<String> prerequisiteSkills;

        /** Ids of the {@link SkillGapDto} entries this phase closes. Validated against the gaps. */
        @JsonAlias({"gapIds", "addressesGaps"})
        public List<String> addressesGapIds;

        /** Why these skills come at this point rather than earlier or later. */
        @JsonAlias({"reason", "priorityRationale"})
        public String orderingRationale;

        public List<Activity> activities;

        /** The thing that exists at the end and can be looked at by someone else. */
        public Project project;

        public List<CompletionCriterion> completionCriteria;

        /** Citations into the retrieved shortlist. A key outside it fails validation. */
        public List<ResourceRef> resources;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Activity {
        /** Server-issued. Tickable. */
        public String id;
        public String title;
        public String description;
        public Integer estimatedHours;
        /** STUDY, PRACTICE, BUILD or REVIEW. */
        public String type;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Project {
        public String title;
        public String description;
        /** What exists afterwards: a repository, a report, a board layout, a dashboard. */
        public String deliverable;
        public Integer estimatedHours;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompletionCriterion {
        /** Server-issued. Tickable. */
        public String id;
        /** A check the student can apply to their own work without needing a grader. */
        public String text;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceRef {
        /** Must be a resourceKey from the shortlist the model was given. */
        @JsonAlias({"key", "id"})
        public String resourceKey;

        /** Which skill in this phase the resource is for. */
        public String forSkill;

        /** Why this one rather than another from the shortlist. */
        @JsonAlias({"reason", "why"})
        public String whyChosen;

        // Everything below is filled in by the server from the catalogue row, never by the model,
        // so a plausible-looking but invented URL cannot reach the student.
        public String title;
        public String provider;
        public String url;
        public String type;
        public String level;
        public String cost;
        public Integer approxHours;
        public Boolean urlReachable;
    }

    /** Every server-issued id in the path, in display order. Used to validate progress ticks. */
    public List<String> allCheckableIds() {
        List<String> ids = new ArrayList<>();
        if (phases == null) {
            return ids;
        }
        for (Phase phase : phases) {
            if (phase == null) {
                continue;
            }
            if (phase.id != null) {
                ids.add(phase.id);
            }
            if (phase.activities != null) {
                phase.activities.stream().filter(a -> a != null && a.id != null).forEach(a -> ids.add(a.id));
            }
            if (phase.completionCriteria != null) {
                phase.completionCriteria.stream().filter(c -> c != null && c.id != null)
                        .forEach(c -> ids.add(c.id));
            }
        }
        return ids;
    }
}
