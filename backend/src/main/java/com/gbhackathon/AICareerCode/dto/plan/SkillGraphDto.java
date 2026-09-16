package com.gbhackathon.AICareerCode.dto.plan;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The knowledge graph: skills as nodes, the relationships between them as edges.
 *
 * <p>This is not a picture. {@link #learningOrder} is computed from the edges by a topological
 * sort in {@link com.gbhackathon.AICareerCode.service.pipeline.KnowledgeGraphStep}, and the
 * learning path is required to respect it - a phase may not teach a skill before its prerequisites.
 * If the graph were only drawn, removing it would change nothing about the plan; removing it here
 * changes the order every phase is built in.
 *
 * <p>{@link Edge#basis} is the honest part. SFIA describes professional skills and their levels;
 * it is not a claim that React must be learned before Next.js. Where a prerequisite comes from a
 * retrieved document, the document is named. Where it is the model's own judgement, it says so,
 * and the UI marks it as a suggestion rather than a fact.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillGraphDto {

    public List<Node> nodes;
    public List<Edge> edges;

    /**
     * Node ids in an order that satisfies every prerequisite edge. Computed by the server, never
     * supplied by the model.
     */
    public List<String> learningOrder;

    /**
     * Prerequisite edges the server had to drop because they formed a cycle, described in words.
     * Shown in the data panel rather than silently discarded: a cycle means the model contradicted
     * itself about what comes first, and that is worth knowing.
     */
    public List<String> brokenCycles;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Node {
        /** Stable within this graph. The model supplies it; the server checks edges resolve to it. */
        public String id;
        public String label;
        @JsonAlias({"code", "sfiaCode"})
        public String taxonomyCode;
        /** Filled in by the server from the taxonomy row when the code is known. */
        public String taxonomySource;
        /** CURRENT (the profile evidences it), GAP (it must be learned) or TARGET (the role itself). */
        public String kind;
        /** Id of the gap this node addresses, when kind is GAP. */
        public String gapId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Edge {
        @JsonAlias({"source"})
        public String from;
        @JsonAlias({"target"})
        public String to;
        /** PREREQUISITE_OF, COMPONENT_OF, RELATED_TO or REQUIRED_FOR_ROLE. */
        public String relation;
        /** TAXONOMY, RETRIEVED_DOCUMENT or AI_SUGGESTED. */
        public String basis;
        /** The taxonomy row or resourceKey behind it, or the reasoning when AI_SUGGESTED. */
        @JsonAlias({"note", "justification", "rationale"})
        public String basisNote;
    }

    public static final String RELATION_PREREQUISITE = "PREREQUISITE_OF";
    public static final String RELATION_COMPONENT = "COMPONENT_OF";
    public static final String RELATION_RELATED = "RELATED_TO";
    public static final String RELATION_REQUIRED_FOR_ROLE = "REQUIRED_FOR_ROLE";

    public static final String BASIS_TAXONOMY = "TAXONOMY";
    public static final String BASIS_RETRIEVED = "RETRIEVED_DOCUMENT";
    public static final String BASIS_AI = "AI_SUGGESTED";

    public static final String KIND_CURRENT = "CURRENT";
    public static final String KIND_GAP = "GAP";
    public static final String KIND_TARGET = "TARGET";
}
