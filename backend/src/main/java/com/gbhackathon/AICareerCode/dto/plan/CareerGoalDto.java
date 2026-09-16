package com.gbhackathon.AICareerCode.dto.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Locale;
import java.util.Objects;

/**
 * What the student is aiming at and how much time they have.
 *
 * <p>Every field here is part of the snapshot key. A plan built for "Backend Developer, 3 months,
 * 8 hours a week" is not an answer to "Data Analyst, 6 months, 20 hours a week", and showing the
 * first when the second was asked for is the failure this type exists to prevent.
 *
 * <p>The plan DTOs use public fields rather than accessor pairs. They are pure data crossing the
 * boundary between the model's JSON and the browser's JSON; Jackson populates them either way, and
 * a hundred getters would hide the schema rather than protect it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CareerGoalDto {

    /** The role being aimed at, for example "Backend Developer". Required. */
    public String targetRole;

    /** INTERN, JUNIOR, MID or SENIOR. Required, because it changes every required level. */
    public String targetSeniority;

    /** An optional job advert the student pasted. Treated as data, never as instructions. */
    public String jobDescription;

    /** 1, 3 or 6. Anything else is rejected before generation starts. */
    public Integer durationMonths;

    /** Realistic study hours per week. The plan is sized against this, not against a wish. */
    public Integer hoursPerWeek;

    public CareerGoalDto() {}

    /**
     * A stable identity for this goal, used together with the profile revision to decide whether a
     * stored plan is still the current one. Case and surrounding whitespace do not count as a
     * change, so re-typing the same role does not throw away a plan the student is working through.
     */
    public String key() {
        return String.join("|",
                normalise(targetRole),
                normalise(targetSeniority),
                String.valueOf(durationMonths),
                String.valueOf(hoursPerWeek),
                // The description is hashed rather than included: it can be thousands of characters
                // and only whether it changed matters here.
                String.valueOf(Objects.hashCode(normalise(jobDescription))));
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Total study hours the plan may spend, from the duration and the weekly budget. */
    public int budgetHours() {
        if (durationMonths == null || hoursPerWeek == null) {
            return 0;
        }
        // 4.33 weeks per month rather than 4: over six months the difference is two whole weeks,
        // which is enough to make a feasible plan look infeasible.
        return (int) Math.round(durationMonths * 4.33 * hoursPerWeek);
    }
}
