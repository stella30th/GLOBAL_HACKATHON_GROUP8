package com.gbhackathon.AICareerCode.service.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbhackathon.AICareerCode.dto.plan.CareerGoalDto;
import com.gbhackathon.AICareerCode.dto.plan.LearningPathDto;
import com.gbhackathon.AICareerCode.dto.plan.SkillGapDto;
import com.gbhackathon.AICareerCode.dto.plan.SkillGraphDto;
import com.gbhackathon.AICareerCode.model.LearningResourceDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Step 4: the learning path itself - phases, work, hours and sources.
 *
 * <p>This is the step where every earlier one has to pay off. The gaps decide what is taught, the
 * graph decides the order, the retrieved documents decide what may be cited, and the student's
 * stated weekly hours decide how much fits. Each of those is enforced here rather than requested:
 * a phase citing a document that was not retrieved, teaching a skill before its prerequisite, or
 * summing to more hours than exist is sent back to the model with the specific violation.
 *
 * <p>The hours check is deliberately one-sided. A plan that overruns the budget is rejected; a
 * plan that comes in under it is not, because "you have 120 hours, here is 90 hours of work and
 * here is why the rest of the role does not fit" is an honest answer and padding it to 120 would
 * not be.
 */
@Component
public class LearningPathStep {

    private static final Logger log = LoggerFactory.getLogger(LearningPathStep.class);

    /** How far over the computed budget a plan may run before it is rejected. */
    private static final double HOURS_TOLERANCE = 1.15;

    private final AiStepRunner runner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LearningPathStep(AiStepRunner runner) {
        this.runner = runner;
    }

    public AiStepRunner.StepResult<LearningPathDto> run(CareerGoalDto goal,
                                                        List<SkillGapDto> gaps,
                                                        SkillGraphDto graph,
                                                        List<LearningResourceDoc> retrievedResources) {
        Map<String, String> gapLabelById = new LinkedHashMap<>();
        gaps.forEach(gap -> gapLabelById.put(gap.id, gap.skillLabel));

        Set<String> resourceKeys = new LinkedHashSet<>();
        retrievedResources.forEach(doc -> resourceKeys.add(doc.getResourceKey()));

        int budget = goal.budgetHours();
        int phaseTarget = switch (goal.durationMonths == null ? 3 : goal.durationMonths) {
            case 1 -> 2;
            case 6 -> 5;
            default -> 3;
        };

        String prompt = """
                You are building a study plan for one specific student. Everything you need has
                already been established: the gaps, the order they depend on each other in, and the
                sources you may cite. Your job is to turn those into phases of work.

                %s

                CAREER GOAL:
                %s

                SKILL GAPS TO ADDRESS (use these ids exactly when you reference them):
                %s

                REQUIRED LEARNING ORDER (derived from the prerequisite graph; earlier ids must not
                depend on later ones):
                %s

                %s

                Return STRICT JSON in exactly this shape:
                {
                  "expectedOutcome": "what the student will realistically be able to show at the end",
                  "feasibility": {
                    "verdict": "FITS | TIGHT | NOT_ACHIEVABLE",
                    "note": "plain explanation, including what had to be left out",
                    "outOfScope": ["skill that does not fit in this time budget"]
                  },
                  "phases": [
                    {
                      "order": 1,
                      "title": "short phase name",
                      "goal": "what the student can do by the end of this phase",
                      "startWeek": 1,
                      "endWeek": 4,
                      "estimatedHours": 32,
                      "skills": ["skill taught in this phase"],
                      "skillNodeIds": ["the graph node id for each skill taught, e.g. n3"],
                      "prerequisiteSkills": ["skill that must already be in place"],
                      "addressesGapIds": ["the exact gap id from the list above"],
                      "orderingRationale": "why these skills come now rather than earlier or later",
                      "activities": [
                        {"title": "...", "description": "concrete, checkable work",
                         "estimatedHours": 8, "type": "STUDY | PRACTICE | BUILD | REVIEW"}
                      ],
                      "project": {
                        "title": "...", "description": "...",
                        "deliverable": "the artefact that exists afterwards", "estimatedHours": 10
                      },
                      "completionCriteria": [
                        {"text": "a check the student can apply to their own work"}
                      ],
                      "resources": [
                        {"resourceKey": "a key from the retrieved list",
                         "forSkill": "which skill in this phase it is for",
                         "whyChosen": "why this one rather than another from the list"}
                      ]
                    }
                  ]
                }

                RULES FOR THIS STEP

                Time
                - The student has %d hours per week for %d months: about %d study hours in total.
                - The sum of every phase's estimatedHours must not exceed %d. Coming in under it is
                  fine and often correct; going over it is not, and will be rejected.
                - Each phase's estimatedHours must be consistent with its activities and project.
                - startWeek and endWeek must run consecutively from week 1 with no overlaps and no
                  gaps, ending no later than week %d.

                Scope and honesty
                - Produce about %d phases.
                - If the gaps cannot all be closed in this budget, say so: set the verdict to TIGHT
                  or NOT_ACHIEVABLE, name what is out of scope, and build a plan for what does fit.
                  Do not compress a year of work into the time available and present it as feasible.
                - Never promise employment, a salary, or that the student will "be a %s" at the end.
                  Describe what they will be able to demonstrate.

                Order
                - Respect the required learning order above. A phase may not teach a skill whose
                  prerequisite is taught in a later phase.
                - skillNodeIds must list the graph node id (n1, n2 ...) for every skill the phase
                  teaches, copied exactly from the list above. This is how the prerequisite check
                  is run, so a phase with no ids, or with an id that is not in the graph, is
                  rejected. A skill the graph has no node for is fine in "skills" and simply has
                  no id here.

                Sources
                - Cite only resourceKey values from the retrieved list. Any other key is rejected.
                - Do not write URLs. The server attaches them from its own catalogue.
                - Every phase needs at least one resource, and every skill in a phase should have a
                  resource for it where the list contains one. If the list has nothing for a skill,
                  say so in feasibility.note rather than citing something unrelated.
                - Do not state whether a resource is free or how long it takes; the server fills
                  that in from the catalogue where it is known.

                Work
                - Activities must be things the student does, with an output someone else could
                  look at. "Learn React" is not an activity; "build the three screens in the
                  attached sketch as React components and write a test for each" is.
                - completionCriteria must be checkable by the student alone, without a grader.
                """.formatted(
                PromptSupport.GROUND_RULES,
                PromptSupport.describeGoal(goal),
                toJson(gaps),
                describeOrder(graph),
                PromptSupport.describeResources(retrievedResources),
                goal.hoursPerWeek, goal.durationMonths, budget,
                budget,
                (int) Math.round((goal.durationMonths == null ? 3 : goal.durationMonths) * 4.33),
                phaseTarget,
                goal.targetRole == null ? "professional" : goal.targetRole);

        return runner.run("learning-path", prompt, LearningPathDto.class,
                path -> validate(path, gapLabelById.keySet(), resourceKeys, budget, graph));
    }

    /** The derived order, written out with labels so the model can act on it. */
    private String describeOrder(SkillGraphDto graph) {
        if (graph.learningOrder == null || graph.learningOrder.isEmpty()) {
            return "(no order could be derived; use the gap priorities instead)";
        }
        Map<String, SkillGraphDto.Node> byId = new HashMap<>();
        graph.nodes.forEach(node -> byId.put(node.id, node));

        StringBuilder sb = new StringBuilder();
        int position = 1;
        for (String id : graph.learningOrder) {
            SkillGraphDto.Node node = byId.get(id);
            if (node == null) {
                continue;
            }
            // The id is printed because the phases reference nodes by id, not by name: two model
            // calls writing the same skill in two different wordings is the failure this avoids.
            sb.append(position++).append(". [").append(node.id).append("] ").append(node.label)
                    .append(" (").append(node.kind == null ? "?" : node.kind).append(")\n");
        }
        if (graph.brokenCycles != null && !graph.brokenCycles.isEmpty()) {
            sb.append("\nNote: ").append(String.join(" ", graph.brokenCycles)).append('\n');
        }
        return sb.toString();
    }

    // Package-private so the rules can be exercised directly in tests, without a model call.
    List<String> validate(LearningPathDto path, Set<String> gapIds,
                          Set<String> resourceKeys, int budget, SkillGraphDto graph) {
        List<String> problems = new ArrayList<>();

        if (path.phases == null || path.phases.isEmpty()) {
            problems.add("phases was empty. Produce the phased plan.");
            return problems;
        }
        if (path.feasibility == null || isBlank(path.feasibility.verdict)) {
            problems.add("feasibility.verdict is required and must be FITS, TIGHT or NOT_ACHIEVABLE.");
        }
        if (isBlank(path.expectedOutcome)) {
            problems.add("expectedOutcome is required: say what the student will be able to show.");
        }

        int totalHours = 0;
        int expectedOrder = 1;
        int previousEndWeek = 0;
        Set<String> skillsTaughtSoFar = new LinkedHashSet<>();

        // The graph, indexed for the prerequisite check below. Until this existed the graph was
        // computed, printed into the prompt as a suggestion, and then never enforced - so a plan
        // that ignored it was accepted, and the ordering step was decoration.
        Map<String, SkillGraphDto.Node> nodesById = new HashMap<>();
        Map<String, List<String>> prerequisitesOf = new HashMap<>();
        if (graph != null && graph.nodes != null) {
            graph.nodes.forEach(node -> nodesById.put(node.id, node));
            if (graph.edges != null) {
                for (SkillGraphDto.Edge edge : graph.edges) {
                    if (SkillGraphDto.RELATION_PREREQUISITE.equals(edge.relation)) {
                        prerequisitesOf.computeIfAbsent(edge.to, key -> new ArrayList<>()).add(edge.from);
                    }
                }
            }
        }
        Set<String> nodesTaughtSoFar = new LinkedHashSet<>();

        for (LearningPathDto.Phase phase : path.phases) {
            if (phase == null || isBlank(phase.title)) {
                problems.add("Every phase needs a title.");
                continue;
            }
            if (phase.order == null || phase.order != expectedOrder) {
                problems.add("Phase '" + phase.title + "' has order " + phase.order + "; phases must "
                        + "be numbered consecutively from 1. Expected " + expectedOrder + ".");
            }
            expectedOrder++;

            if (isBlank(phase.goal)) {
                problems.add("Phase '" + phase.title + "' needs a goal saying what the student can do "
                        + "by the end of it.");
            }
            if (phase.estimatedHours == null || phase.estimatedHours <= 0) {
                problems.add("Phase '" + phase.title + "' needs a positive estimatedHours.");
            } else {
                totalHours += phase.estimatedHours;
            }
            if (phase.startWeek == null || phase.endWeek == null) {
                problems.add("Phase '" + phase.title + "' needs startWeek and endWeek.");
            } else {
                if (phase.startWeek != previousEndWeek + 1) {
                    problems.add("Phase '" + phase.title + "' starts at week " + phase.startWeek
                            + " but the previous phase ended at week " + previousEndWeek
                            + ". Phases must run consecutively with no gap or overlap.");
                }
                if (phase.endWeek < phase.startWeek) {
                    problems.add("Phase '" + phase.title + "' ends before it starts.");
                }
                previousEndWeek = phase.endWeek;
            }

            if (phase.activities == null || phase.activities.isEmpty()) {
                problems.add("Phase '" + phase.title + "' has no activities. Give the student "
                        + "concrete work with an output.");
            }
            if (phase.completionCriteria == null || phase.completionCriteria.isEmpty()) {
                problems.add("Phase '" + phase.title + "' has no completionCriteria. Give checks the "
                        + "student can apply to their own work.");
            }

            if (phase.addressesGapIds == null || phase.addressesGapIds.isEmpty()) {
                problems.add("Phase '" + phase.title + "' does not say which gaps it closes. Use the "
                        + "gap ids exactly as given.");
            } else {
                for (String gapId : phase.addressesGapIds) {
                    if (!gapIds.contains(gapId)) {
                        problems.add("Phase '" + phase.title + "' references gap id '" + gapId
                                + "', which is not one of the gaps you were given. Copy the ids exactly.");
                    }
                }
            }

            if (phase.resources == null || phase.resources.isEmpty()) {
                if (!resourceKeys.isEmpty()) {
                    problems.add("Phase '" + phase.title + "' cites no resources although the "
                            + "retrieved list is not empty. Cite the relevant ones by resourceKey.");
                }
            } else {
                for (LearningPathDto.ResourceRef ref : phase.resources) {
                    if (ref == null || isBlank(ref.resourceKey)) {
                        problems.add("Every resource in phase '" + phase.title + "' needs a resourceKey.");
                        continue;
                    }
                    if (!resourceKeys.contains(ref.resourceKey)) {
                        problems.add("Phase '" + phase.title + "' cites resourceKey '" + ref.resourceKey
                                + "', which was not in the retrieved list. Cite only the keys you were "
                                + "given - a source outside that list cannot be verified and is rejected.");
                    }
                    if (!isBlank(ref.url)) {
                        problems.add("Phase '" + phase.title + "' supplied a URL for '" + ref.resourceKey
                                + "'. Do not write URLs; cite the key only.");
                    }
                }
            }

            // The graph check. This is what turns the topological sort from a hint in the prompt
            // into a constraint the plan has to satisfy.
            if (!nodesById.isEmpty()) {
                if (phase.skillNodeIds == null || phase.skillNodeIds.isEmpty()) {
                    problems.add("Phase '" + phase.title + "' has no skillNodeIds. List the graph "
                            + "node id for every skill it teaches, copied exactly from the learning "
                            + "order above; the prerequisite check cannot run without them.");
                } else {
                    for (String nodeId : phase.skillNodeIds) {
                        SkillGraphDto.Node node = nodesById.get(nodeId);
                        if (node == null) {
                            problems.add("Phase '" + phase.title + "' references graph node '" + nodeId
                                    + "', which does not exist. Use the ids shown in the learning order.");
                            continue;
                        }
                        for (String prerequisiteId : prerequisitesOf.getOrDefault(nodeId, List.of())) {
                            SkillGraphDto.Node prerequisite = nodesById.get(prerequisiteId);
                            if (prerequisite == null
                                    // A CURRENT node is something the profile already evidences, so
                                    // the plan is not expected to teach it and its absence here is
                                    // correct rather than an ordering mistake.
                                    || SkillGraphDto.KIND_CURRENT.equals(prerequisite.kind)
                                    || nodesTaughtSoFar.contains(prerequisiteId)) {
                                continue;
                            }
                            problems.add("Phase '" + phase.title + "' teaches '" + node.label
                                    + "', but the graph says '" + prerequisite.label + "' must come "
                                    + "first and no earlier phase teaches it. Move '" + prerequisite.label
                                    + "' into an earlier phase, or teach it in this one.");
                        }
                    }
                    nodesTaughtSoFar.addAll(phase.skillNodeIds);
                }
            }

            // Prerequisite ordering by label, kept alongside the id check above because a phase may
            // legitimately name a prerequisite the graph has no node for. A skill the student is
            // assumed to already have cannot be checked from here, so only a prerequisite that IS
            // taught later is an error.
            if (phase.prerequisiteSkills != null) {
                for (String prerequisite : phase.prerequisiteSkills) {
                    if (prerequisite == null || skillsTaughtSoFar.contains(normalise(prerequisite))) {
                        continue;
                    }
                    boolean taughtLater = path.phases.stream()
                            .filter(p -> p != null && p.order != null && phase.order != null
                                    && p.order > phase.order && p.skills != null)
                            .flatMap(p -> p.skills.stream())
                            .anyMatch(s -> normalise(s).equals(normalise(prerequisite)));
                    if (taughtLater) {
                        problems.add("Phase '" + phase.title + "' needs '" + prerequisite
                                + "' beforehand, but a later phase is where it is taught. Move it earlier.");
                    }
                }
            }
            if (phase.skills != null) {
                phase.skills.forEach(s -> skillsTaughtSoFar.add(normalise(s)));
            }
        }

        if (budget > 0 && totalHours > budget * HOURS_TOLERANCE) {
            problems.add("The phases add up to " + totalHours + " hours but the student only has about "
                    + budget + ". Cut scope to fit, name what you cut in feasibility.outOfScope, and "
                    + "set the verdict accordingly. Do not simply relabel the hours.");
        }
        return problems;
    }

    /**
     * Issues the server-side ids, sums the hours from the phases rather than trusting a total, and
     * attaches the catalogue's own record of each cited resource.
     *
     * <p>Attaching rather than accepting is the point: the title, provider, URL, cost and duration
     * shown to the student come from the row that was retrieved, so they describe a document that
     * exists even when the model's own description of it drifted.
     */
    public void finalise(LearningPathDto path, CareerGoalDto goal, List<LearningResourceDoc> retrieved) {
        Map<String, LearningResourceDoc> byKey = new LinkedHashMap<>();
        retrieved.forEach(doc -> byKey.put(doc.getResourceKey(), doc));

        path.durationMonths = goal.durationMonths;
        path.hoursPerWeek = goal.hoursPerWeek;
        path.budgetHours = goal.budgetHours();

        int total = 0;
        if (path.phases != null) {
            for (LearningPathDto.Phase phase : path.phases) {
                phase.id = UUID.randomUUID().toString();
                if (phase.estimatedHours != null) {
                    total += phase.estimatedHours;
                }
                if (phase.activities != null) {
                    phase.activities.forEach(activity -> activity.id = UUID.randomUUID().toString());
                }
                if (phase.completionCriteria != null) {
                    phase.completionCriteria.forEach(c -> c.id = UUID.randomUUID().toString());
                }
                if (phase.resources != null) {
                    List<LearningPathDto.ResourceRef> kept = new ArrayList<>();
                    for (LearningPathDto.ResourceRef ref : phase.resources) {
                        LearningResourceDoc doc = byKey.get(ref.resourceKey);
                        if (doc == null) {
                            // Validation should have caught this; if a key still gets through, drop
                            // the citation rather than render a resource with no source behind it.
                            log.warn("Dropping a citation to unknown resourceKey '{}'", ref.resourceKey);
                            continue;
                        }
                        ref.title = doc.getTitle();
                        ref.provider = doc.getProvider();
                        ref.url = doc.getUrl();
                        ref.type = doc.getType();
                        ref.level = doc.getLevel();
                        ref.cost = doc.getCost();
                        ref.approxHours = doc.getApproxHours();
                        ref.urlReachable = doc.getUrlReachable();
                        kept.add(ref);
                    }
                    phase.resources = kept;
                }
            }
        }
        path.plannedHours = total;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialise the gap list for the prompt: {}", e.getMessage());
            return "[]";
        }
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
