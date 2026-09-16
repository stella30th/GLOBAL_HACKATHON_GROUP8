package com.gbhackathon.AICareerCode.dto.plan;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One skill the target role requires, and where that requirement came from.
 *
 * <p>{@link #sourceType} is not decoration. A requirement lifted from a job description the
 * student pasted stands on different ground from one the model inferred, and a student deciding
 * how to spend six months deserves to see which is which.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TargetRequirementDto {

    @JsonAlias({"skill", "label"})
    public String skillLabel;

    @JsonAlias({"code", "sfiaCode"})
    public String taxonomyCode;

    /** Filled in by the server from the taxonomy row. */
    public String taxonomySource;
    public String taxonomyName;

    /** SFIA responsibility level the role needs, 1-7, where the source supports naming one. */
    public Integer requiredLevel;

    /** ESSENTIAL, IMPORTANT or NICE_TO_HAVE. */
    public String importance;

    /** JOB_DESCRIPTION, TAXONOMY or AI_JUDGEMENT. */
    public String sourceType;

    /** The quotation or taxonomy row this requirement rests on. */
    @JsonAlias({"source", "sourceNote", "evidence"})
    public String sourceNote;

    public static final String SOURCE_JOB_DESCRIPTION = "JOB_DESCRIPTION";
    public static final String SOURCE_TAXONOMY = "TAXONOMY";
    public static final String SOURCE_AI_JUDGEMENT = "AI_JUDGEMENT";
}
