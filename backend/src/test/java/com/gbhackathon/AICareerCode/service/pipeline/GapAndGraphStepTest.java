package com.gbhackathon.AICareerCode.service.pipeline;

import com.gbhackathon.AICareerCode.dto.plan.SkillGapDto;
import com.gbhackathon.AICareerCode.dto.plan.SkillGraphDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ordering is the part of the knowledge graph that has to be right: it is fed into the
 * learning-path step as a constraint, so a wrong order produces a plan that teaches a skill before
 * the thing it depends on.
 */
class GapAndGraphStepTest {

    /** The runner is never reached by the methods under test, so no model call is involved. */
    private final GapAndGraphStep step = new GapAndGraphStep(null);

    private static SkillGraphDto.Node node(String id, String label, String kind, String gapId) {
        SkillGraphDto.Node node = new SkillGraphDto.Node();
        node.id = id;
        node.label = label;
        node.kind = kind;
        node.gapId = gapId;
        return node;
    }

    private static SkillGraphDto.Edge edge(String from, String to, String relation) {
        SkillGraphDto.Edge e = new SkillGraphDto.Edge();
        e.from = from;
        e.to = to;
        e.relation = relation;
        e.basis = SkillGraphDto.BASIS_AI;
        e.basisNote = "reasoned";
        return e;
    }

    private static SkillGapDto gap(String label, int priority) {
        SkillGapDto g = new SkillGapDto();
        g.skillLabel = label;
        g.priority = priority;
        g.rationale = "because";
        return g;
    }

    private static GapAndGraphStep.Result resultWith(SkillGraphDto graph, int gapCount) {
        GapAndGraphStep.Result result = new GapAndGraphStep.Result();
        result.knowledgeGraph = graph;
        result.skillGaps = new ArrayList<>();
        for (int i = 1; i <= gapCount; i++) {
            result.skillGaps.add(gap("skill " + i, i));
        }
        return result;
    }

    @Test
    void ordersPrerequisitesBeforeTheSkillsThatDependOnThem() {
        SkillGraphDto graph = new SkillGraphDto();
        graph.nodes = List.of(
                node("n3", "Next.js", SkillGraphDto.KIND_GAP, "gap-3"),
                node("n1", "JavaScript", SkillGraphDto.KIND_GAP, "gap-1"),
                node("n2", "React", SkillGraphDto.KIND_GAP, "gap-2"));
        graph.edges = List.of(
                edge("n1", "n2", SkillGraphDto.RELATION_PREREQUISITE),
                edge("n2", "n3", SkillGraphDto.RELATION_PREREQUISITE));

        GapAndGraphStep.Result result = resultWith(graph, 3);
        step.orderGraph(result);

        List<String> order = graph.learningOrder;
        assertTrue(order.indexOf("n1") < order.indexOf("n2"), order.toString());
        assertTrue(order.indexOf("n2") < order.indexOf("n3"), order.toString());
        assertTrue(graph.brokenCycles.isEmpty());
    }

    /**
     * A model that says A comes before B and B before A has contradicted itself. Quietly picking
     * one would hide a real defect in the analysis, so every node is still ordered and the
     * contradiction is recorded for the provenance panel.
     */
    @Test
    void breaksACycleAndSaysSo() {
        SkillGraphDto graph = new SkillGraphDto();
        graph.nodes = List.of(
                node("a", "Testing", SkillGraphDto.KIND_GAP, "gap-1"),
                node("b", "CI/CD", SkillGraphDto.KIND_GAP, "gap-2"));
        graph.edges = List.of(
                edge("a", "b", SkillGraphDto.RELATION_PREREQUISITE),
                edge("b", "a", SkillGraphDto.RELATION_PREREQUISITE));

        GapAndGraphStep.Result result = resultWith(graph, 2);
        step.orderGraph(result);

        assertEquals(2, graph.learningOrder.size(), "every node must still appear in the order");
        assertFalse(graph.brokenCycles.isEmpty(), "the contradiction must be reported");
        assertTrue(graph.brokenCycles.get(0).toLowerCase().contains("cycle"));
    }

    /**
     * A repeated edge counts twice towards an in-degree, leaving the target unreachable and
     * looking exactly like a cycle to the sort.
     */
    @Test
    void aDuplicatedEdgeIsNotMistakenForACycle() {
        SkillGraphDto graph = new SkillGraphDto();
        graph.nodes = List.of(
                node("a", "SQL", SkillGraphDto.KIND_GAP, "gap-1"),
                node("b", "Data modelling", SkillGraphDto.KIND_GAP, "gap-2"));
        graph.edges = List.of(
                edge("a", "b", SkillGraphDto.RELATION_PREREQUISITE),
                edge("a", "b", SkillGraphDto.RELATION_PREREQUISITE));

        GapAndGraphStep.Result result = resultWith(graph, 2);
        step.orderGraph(result);

        assertTrue(graph.brokenCycles.isEmpty(), "a duplicate is not a cycle");
        assertEquals(List.of("a", "b"), graph.learningOrder);
    }

    @Test
    void nonPrerequisiteEdgesDoNotConstrainTheOrder() {
        SkillGraphDto graph = new SkillGraphDto();
        graph.nodes = List.of(
                node("a", "Docker", SkillGraphDto.KIND_GAP, "gap-1"),
                node("b", "Linux", SkillGraphDto.KIND_GAP, "gap-2"));
        graph.edges = List.of(
                edge("b", "a", SkillGraphDto.RELATION_RELATED),
                edge("a", "b", SkillGraphDto.RELATION_RELATED));

        GapAndGraphStep.Result result = resultWith(graph, 2);
        step.orderGraph(result);

        assertTrue(graph.brokenCycles.isEmpty());
        assertEquals(2, graph.learningOrder.size());
    }

    /**
     * The model references gaps by position because it cannot be trusted to echo a UUID back
     * unchanged. A reference that resolves to nothing must be cleared rather than left as a string
     * the UI would render as a link to a gap that does not exist.
     */
    @Test
    void resolvesPositionalGapReferencesAndDropsUnresolvableOnes() {
        SkillGraphDto graph = new SkillGraphDto();
        graph.nodes = List.of(
                node("a", "SQL", SkillGraphDto.KIND_GAP, "gap-1"),
                node("b", "Ghost", SkillGraphDto.KIND_GAP, "gap-9"));
        graph.edges = List.of(edge("a", "b", SkillGraphDto.RELATION_RELATED));

        GapAndGraphStep.Result result = resultWith(graph, 2);
        step.orderGraph(result);

        assertNotNull(result.skillGaps.get(0).id, "every gap must get a server-issued id");
        assertEquals(result.skillGaps.get(0).id, graph.nodes.get(0).gapId);
        assertNull(graph.nodes.get(1).gapId, "a reference to a gap that does not exist is cleared");
    }

    // ---- validation ----

    @Test
    void rejectsATaxonomyCodeThatWasNotRetrieved() {
        GapAndGraphStep.Result result = validResult();
        result.skillGaps.get(0).taxonomyCode = "MADE-UP";

        List<String> problems = step.validate(result, Set.of("EXT-SQL"));

        assertTrue(problems.stream().anyMatch(p -> p.contains("MADE-UP")), problems.toString());
    }

    @Test
    void rejectsDuplicatePriorities() {
        GapAndGraphStep.Result result = validResult();
        result.skillGaps.get(1).priority = 1;

        List<String> problems = step.validate(result, Set.of("EXT-SQL"));

        assertTrue(problems.stream().anyMatch(p -> p.contains("more than once")), problems.toString());
    }

    @Test
    void rejectsAnEdgePointingAtANodeThatDoesNotExist() {
        GapAndGraphStep.Result result = validResult();
        SkillGraphDto.Edge dangling = edge("n1", "nowhere", SkillGraphDto.RELATION_PREREQUISITE);
        List<SkillGraphDto.Edge> edges = new ArrayList<>(result.knowledgeGraph.edges);
        edges.add(dangling);
        result.knowledgeGraph.edges = edges;

        List<String> problems = step.validate(result, Set.of("EXT-SQL"));

        assertTrue(problems.stream().anyMatch(p -> p.contains("nowhere")), problems.toString());
    }

    /**
     * SFIA describes professional skills and levels; it is not a source for "learn React before
     * Next.js". An edge claiming that authority without naming the row has to be sent back.
     */
    @Test
    void rejectsATaxonomyBasedPrerequisiteWithNoRowNamed() {
        GapAndGraphStep.Result result = validResult();
        result.knowledgeGraph.edges.get(0).basis = SkillGraphDto.BASIS_TAXONOMY;
        result.knowledgeGraph.edges.get(0).basisNote = null;

        List<String> problems = step.validate(result, Set.of("EXT-SQL"));

        assertTrue(problems.stream().anyMatch(p -> p.contains("TAXONOMY basis")), problems.toString());
    }

    @Test
    void acceptsAWellFormedResult() {
        assertTrue(step.validate(validResult(), Set.of("EXT-SQL")).isEmpty());
    }

    private GapAndGraphStep.Result validResult() {
        GapAndGraphStep.Result result = new GapAndGraphStep.Result();
        result.skillGaps = new ArrayList<>(List.of(gap("SQL", 1), gap("Testing", 2), gap("CI/CD", 3)));

        SkillGraphDto graph = new SkillGraphDto();
        graph.nodes = new ArrayList<>(List.of(
                node("n1", "SQL", SkillGraphDto.KIND_GAP, "gap-1"),
                node("n2", "Testing", SkillGraphDto.KIND_GAP, "gap-2"),
                node("n3", "CI/CD", SkillGraphDto.KIND_GAP, "gap-3"),
                node("n4", "Backend Developer", SkillGraphDto.KIND_TARGET, null)));
        graph.edges = new ArrayList<>(List.of(
                edge("n1", "n2", SkillGraphDto.RELATION_PREREQUISITE),
                edge("n3", "n4", SkillGraphDto.RELATION_REQUIRED_FOR_ROLE)));
        result.knowledgeGraph = graph;
        return result;
    }
}
