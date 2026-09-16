package com.gbhackathon.AICareerCode.service.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbhackathon.AICareerCode.dto.plan.CareerGoalDto;
import com.gbhackathon.AICareerCode.dto.plan.SkillEvidenceDto;
import com.gbhackathon.AICareerCode.dto.plan.SkillGapDto;
import com.gbhackathon.AICareerCode.dto.plan.SkillGraphDto;
import com.gbhackathon.AICareerCode.dto.plan.TargetRequirementDto;
import com.gbhackathon.AICareerCode.model.TaxonomySkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Step 3: the gap analysis and the knowledge graph, and then the part the model does not do -
 * turning the graph into an order.
 *
 * <p>Gaps and graph are produced together because the graph's whole purpose is to sequence the
 * gaps, and a graph generated from a separate call routinely refers to skills the gap list does
 * not contain. Generated together, every GAP node can be required to name a real gap id.
 *
 * <p>{@link #orderGraph} is where the graph stops being a drawing. It runs a topological sort over
 * the prerequisite edges, breaking any cycle it finds and recording that it did, and the resulting
 * order is passed into the learning-path step as a constraint. A model that says A comes before B
 * and also that B comes before A has contradicted itself; silently picking one would hide a real
 * defect in the analysis, so the break is reported in the plan's provenance.
 */
@Component
public class GapAndGraphStep {

    private static final Logger log = LoggerFactory.getLogger(GapAndGraphStep.class);

    private final AiStepRunner runner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GapAndGraphStep(AiStepRunner runner) {
        this.runner = runner;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        public List<SkillGapDto> skillGaps;
        public SkillGraphDto knowledgeGraph;
        /** The model's own account of what it could not ground. Surfaced, not swallowed. */
        public String analysisLimitations;
    }

    public AiStepRunner.StepResult<Result> run(CareerGoalDto goal,
                                               List<SkillEvidenceDto> evidence,
                                               List<TargetRequirementDto> requirements,
                                               List<TaxonomySkill> retrievedTaxonomy) {
        String evidenceJson = toJson(evidence);
        String requirementsJson = toJson(requirements);

        String prompt = """
                You are a skills analyst. Compare what this profile evidences against what the
                target role requires, then describe how the missing skills relate to each other.

                %s

                CAREER GOAL:
                %s

                WHAT THE PROFILE EVIDENCES (from the previous step):
                %s

                WHAT THE ROLE REQUIRES (from the previous step):
                %s

                %s

                Return STRICT JSON in exactly this shape:
                {
                  "skillGaps": [
                    {
                      "skillLabel": "skill that needs development",
                      "taxonomyCode": "a code from the taxonomy list, or null",
                      "evidenceStatus": "HAS_EVIDENCE | LIMITED_EVIDENCE | NO_DATA",
                      "currentLevel": null,
                      "targetLevel": 3,
                      "severity": "CRITICAL | SIGNIFICANT | MINOR",
                      "priority": 1,
                      "rationale": "what the requirement asks for and what the profile shows",
                      "confidence": "HIGH | MEDIUM | LOW",
                      "evidenceThatWouldSettleIt": "what in a CV would change this assessment",
                      "requirementEvidence": ["the requirement text this rests on"]
                    }
                  ],
                  "knowledgeGraph": {
                    "nodes": [
                      {"id": "n1", "label": "skill name", "taxonomyCode": "code or null",
                       "kind": "CURRENT | GAP | TARGET", "gapId": null}
                    ],
                    "edges": [
                      {"from": "n1", "to": "n2", "relation": "PREREQUISITE_OF | COMPONENT_OF | RELATED_TO | REQUIRED_FOR_ROLE",
                       "basis": "TAXONOMY | RETRIEVED_DOCUMENT | AI_SUGGESTED",
                       "basisNote": "what supports this relationship"}
                    ]
                  },
                  "analysisLimitations": "what you could not establish from the data given, or null"
                }

                RULES FOR THIS STEP

                Gaps
                - A gap is a statement about the PROFILE, not about the person. Where the profile is
                  silent, evidenceStatus is NO_DATA and the rationale says the profile does not
                  evidence it - never that the person lacks the ability.
                - Set currentLevel only where the previous step assessed one. Otherwise null.
                - priority is 1, 2, 3 ... with no duplicates and no gaps in the numbering.
                - 5 to 12 gaps. Fewer than five for a role change is not a serious analysis; more
                  than twelve cannot be addressed in the time available and will be trimmed anyway.
                - Do not list a skill as a gap when the evidence already meets the required level.

                Graph
                - Include a node for every gap (kind GAP, gapId matching that gap's position in your
                  skillGaps array as "gap-1", "gap-2", ... counting from 1), a node for the skills
                  the profile already evidences that the plan builds on (kind CURRENT), and exactly
                  one node for the target role itself (kind TARGET).
                - PREREQUISITE_OF means "from must be learned before to". Use it only where learning
                  order genuinely depends on it.
                - basis TAXONOMY is allowed only when a retrieved taxonomy row states the
                  relationship. The SFIA framework describes professional skills and levels; it does
                  NOT say which technology precedes another, so a technology prerequisite is
                  AI_SUGGESTED and basisNote must give your reasoning.
                - Do not create a cycle. If you write A PREREQUISITE_OF B, you may not also write B
                  PREREQUISITE_OF A, directly or through a chain.
                """.formatted(
                PromptSupport.GROUND_RULES,
                PromptSupport.describeGoal(goal),
                evidenceJson,
                requirementsJson,
                PromptSupport.describeTaxonomy(retrievedTaxonomy));

        Set<String> knownCodes = new HashSet<>();
        retrievedTaxonomy.forEach(s -> knownCodes.add(s.getCode().toUpperCase(Locale.ROOT)));

        return runner.run("gap-and-graph", prompt, Result.class, result -> validate(result, knownCodes));
    }

    // Package-private so the rules can be exercised directly in tests, without a model call.
    List<String> validate(Result result, Set<String> knownCodes) {
        List<String> problems = new ArrayList<>();

        if (result.skillGaps == null || result.skillGaps.size() < 3) {
            problems.add("skillGaps must contain at least 3 entries. Compare every requirement "
                    + "against the evidence and list the ones that need development.");
            return problems;
        }

        Set<Integer> priorities = new LinkedHashSet<>();
        for (SkillGapDto gap : result.skillGaps) {
            if (gap == null || isBlank(gap.skillLabel)) {
                problems.add("Every skillGaps entry needs a non-empty skillLabel.");
                continue;
            }
            if (!isBlank(gap.taxonomyCode) && !knownCodes.contains(gap.taxonomyCode.toUpperCase(Locale.ROOT))) {
                problems.add("taxonomyCode '" + gap.taxonomyCode + "' on gap '" + gap.skillLabel
                        + "' is not in the retrieved taxonomy list. Use a listed code or null.");
            }
            if (isBlank(gap.rationale)) {
                problems.add("Gap '" + gap.skillLabel + "' needs a rationale naming the requirement "
                        + "and what the profile shows.");
            }
            if (gap.priority == null) {
                problems.add("Gap '" + gap.skillLabel + "' has no priority. Number the gaps 1, 2, 3 ...");
            } else if (!priorities.add(gap.priority)) {
                problems.add("Priority " + gap.priority + " is used more than once. Each gap needs a "
                        + "distinct priority numbered from 1 upwards.");
            }
        }

        SkillGraphDto graph = result.knowledgeGraph;
        if (graph == null || graph.nodes == null || graph.nodes.isEmpty()) {
            problems.add("knowledgeGraph.nodes was empty. Every gap needs a node, plus the skills "
                    + "already evidenced that the plan builds on, plus one TARGET node for the role.");
            return problems;
        }

        Set<String> nodeIds = new LinkedHashSet<>();
        int gapNodes = 0;
        int targetNodes = 0;
        for (SkillGraphDto.Node node : graph.nodes) {
            if (node == null || isBlank(node.id) || isBlank(node.label)) {
                problems.add("Every graph node needs an id and a label.");
                continue;
            }
            if (!nodeIds.add(node.id)) {
                problems.add("Node id '" + node.id + "' appears more than once. Ids must be unique.");
            }
            if (SkillGraphDto.KIND_GAP.equals(node.kind)) {
                gapNodes++;
            }
            if (SkillGraphDto.KIND_TARGET.equals(node.kind)) {
                targetNodes++;
            }
            if (!isBlank(node.taxonomyCode) && !knownCodes.contains(node.taxonomyCode.toUpperCase(Locale.ROOT))) {
                problems.add("taxonomyCode '" + node.taxonomyCode + "' on node '" + node.label
                        + "' is not in the retrieved taxonomy list. Use a listed code or null.");
            }
        }
        if (gapNodes < result.skillGaps.size()) {
            problems.add("There are " + result.skillGaps.size() + " gaps but only " + gapNodes
                    + " nodes with kind GAP. Add a GAP node for every gap.");
        }
        if (targetNodes != 1) {
            problems.add("The graph needs exactly one node with kind TARGET representing the role "
                    + "itself; found " + targetNodes + ".");
        }

        if (graph.edges == null || graph.edges.isEmpty()) {
            problems.add("knowledgeGraph.edges was empty. The plan is ordered from these edges, so "
                    + "an empty list makes the graph useless. State how the skills relate.");
            return problems;
        }
        for (SkillGraphDto.Edge edge : graph.edges) {
            if (edge == null || isBlank(edge.from) || isBlank(edge.to)) {
                problems.add("Every edge needs a from and a to.");
                continue;
            }
            if (!nodeIds.contains(edge.from)) {
                problems.add("Edge references node id '" + edge.from + "', which is not in nodes.");
            }
            if (!nodeIds.contains(edge.to)) {
                problems.add("Edge references node id '" + edge.to + "', which is not in nodes.");
            }
            if (isBlank(edge.basis)) {
                problems.add("Edge " + edge.from + " -> " + edge.to + " needs a basis of TAXONOMY, "
                        + "RETRIEVED_DOCUMENT or AI_SUGGESTED.");
            }
            if (SkillGraphDto.BASIS_TAXONOMY.equals(edge.basis)
                    && SkillGraphDto.RELATION_PREREQUISITE.equals(edge.relation)
                    && isBlank(edge.basisNote)) {
                problems.add("Edge " + edge.from + " -> " + edge.to + " claims a TAXONOMY basis for a "
                        + "prerequisite. Name the taxonomy row that states it in basisNote, or change "
                        + "the basis to AI_SUGGESTED and give your reasoning.");
            }
        }
        return problems;
    }

    /**
     * Issues gap ids, resolves the model's {@code gap-N} references onto them, and computes the
     * learning order from the prerequisite edges.
     *
     * <p>The id mapping matters: the model is asked for positional references because it cannot be
     * trusted to echo a UUID back unchanged, and a mismatched id would silently detach a phase
     * from the gap it claims to close.
     */
    public void orderGraph(Result result) {
        Map<String, String> gapIdByPosition = new LinkedHashMap<>();
        for (int i = 0; i < result.skillGaps.size(); i++) {
            SkillGapDto gap = result.skillGaps.get(i);
            gap.id = UUID.randomUUID().toString();
            gapIdByPosition.put("gap-" + (i + 1), gap.id);
        }

        SkillGraphDto graph = result.knowledgeGraph;
        for (SkillGraphDto.Node node : graph.nodes) {
            if (node.gapId != null) {
                // Unresolvable positional references are cleared rather than kept: a node pointing
                // at "gap-9" when there are six gaps points at nothing, and leaving the string in
                // would let the UI render a link that goes nowhere.
                node.gapId = gapIdByPosition.get(node.gapId.trim().toLowerCase(Locale.ROOT));
            }
        }

        graph.learningOrder = topologicalOrder(graph);
    }

    /**
     * Kahn's algorithm over the prerequisite edges.
     *
     * <p>Nodes with no incoming prerequisite come first, in the order the model listed them, which
     * keeps its priority judgement where the graph does not contradict it. Anything still unvisited
     * when the queue empties is part of a cycle: those nodes are appended in their original order
     * and the cycle is recorded in {@code brokenCycles} rather than hidden.
     */
    private List<String> topologicalOrder(SkillGraphDto graph) {
        List<String> ids = new ArrayList<>();
        graph.nodes.forEach(n -> ids.add(n.id));

        Map<String, List<String>> successors = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        ids.forEach(id -> {
            successors.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        });

        Set<String> seen = new HashSet<>();
        for (SkillGraphDto.Edge edge : graph.edges) {
            if (!SkillGraphDto.RELATION_PREREQUISITE.equals(edge.relation)) {
                continue;
            }
            if (!inDegree.containsKey(edge.from) || !inDegree.containsKey(edge.to)) {
                continue;
            }
            // A duplicated edge would otherwise count twice towards the in-degree and leave the
            // target unreachable, which looks exactly like a cycle.
            if (!seen.add(edge.from + "->" + edge.to)) {
                continue;
            }
            successors.get(edge.from).add(edge.to);
            inDegree.merge(edge.to, 1, Integer::sum);
        }

        Deque<String> ready = new ArrayDeque<>();
        ids.stream().filter(id -> inDegree.get(id) == 0).forEach(ready::add);

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String next : successors.get(id)) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }

        if (order.size() < ids.size()) {
            List<String> stuck = new ArrayList<>(ids);
            stuck.removeAll(order);
            Map<String, String> labels = new LinkedHashMap<>();
            graph.nodes.forEach(n -> labels.put(n.id, n.label));
            List<String> broken = new ArrayList<>();
            stuck.forEach(id -> broken.add(labels.getOrDefault(id, id)));
            graph.brokenCycles = List.of("The model's prerequisites form a cycle among: "
                    + String.join(", ", broken)
                    + ". Their order below is the order they were listed in, not a derived one.");
            log.info("Knowledge graph contained a prerequisite cycle across {} nodes", stuck.size());
            order.addAll(stuck);
        } else {
            graph.brokenCycles = List.of();
        }
        return order;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            // This is our own data on its way back out to the model, so a failure here is a bug
            // rather than bad input; an empty array keeps the prompt valid and the step will fail
            // validation loudly instead of producing a plan from nothing.
            log.warn("Could not serialise pipeline data for the prompt: {}", e.getMessage());
            return "[]";
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
