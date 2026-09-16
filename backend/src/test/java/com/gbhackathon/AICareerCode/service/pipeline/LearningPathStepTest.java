package com.gbhackathon.AICareerCode.service.pipeline;

import com.gbhackathon.AICareerCode.dto.plan.CareerGoalDto;
import com.gbhackathon.AICareerCode.dto.plan.LearningPathDto;
import com.gbhackathon.AICareerCode.dto.plan.SkillGraphDto;
import com.gbhackathon.AICareerCode.model.LearningResourceDoc;
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
 * The learning-path step is where every earlier step has to pay off, so its checks are the ones
 * that decide whether a plan is grounded or merely plausible.
 */
class LearningPathStepTest {

    private final LearningPathStep step = new LearningPathStep(null);

    private static final Set<String> GAP_IDS = Set.of("gap-a", "gap-b");
    private static final Set<String> RESOURCE_KEYS = Set.of("mdn-web-docs", "sqlbolt");

    /** SQL must come before automated testing; "basic programming" is already evidenced. */
    private static final SkillGraphDto GRAPH = graph();

    private static SkillGraphDto graph() {
        SkillGraphDto g = new SkillGraphDto();
        g.nodes = new ArrayList<>(List.of(
                node("n1", "SQL", SkillGraphDto.KIND_GAP),
                node("n2", "Automated testing", SkillGraphDto.KIND_GAP),
                node("n3", "Basic programming", SkillGraphDto.KIND_CURRENT),
                node("n4", "Backend Developer", SkillGraphDto.KIND_TARGET)));
        g.edges = new ArrayList<>(List.of(
                edge("n1", "n2", SkillGraphDto.RELATION_PREREQUISITE),
                edge("n3", "n1", SkillGraphDto.RELATION_PREREQUISITE)));
        return g;
    }

    private static SkillGraphDto.Node node(String id, String label, String kind) {
        SkillGraphDto.Node n = new SkillGraphDto.Node();
        n.id = id;
        n.label = label;
        n.kind = kind;
        return n;
    }

    private static SkillGraphDto.Edge edge(String from, String to, String relation) {
        SkillGraphDto.Edge e = new SkillGraphDto.Edge();
        e.from = from;
        e.to = to;
        e.relation = relation;
        e.basis = SkillGraphDto.BASIS_AI;
        return e;
    }

    // ---- the graph as a constraint ----

    /**
     * The check that makes the graph step worth running. Before it existed the topological order
     * was computed, printed into the prompt, and never enforced - so a plan that ignored it was
     * accepted and the ordering was decoration.
     */
    @Test
    void rejectsAPhaseThatTeachesASkillBeforeItsGraphPrerequisite() {
        LearningPathDto path = validPath();
        // Swap them: testing now comes first, although the graph says SQL precedes it.
        path.phases.get(0).skillNodeIds = List.of("n2");
        path.phases.get(1).skillNodeIds = List.of("n1");

        List<String> problems = step.validate(path, GAP_IDS, RESOURCE_KEYS, 500, GRAPH);

        assertTrue(problems.stream().anyMatch(p -> p.contains("must come first")), problems.toString());
    }

    /**
     * A prerequisite the profile already evidences is not something the plan has to teach, so its
     * absence from every phase is correct rather than an ordering mistake.
     */
    @Test
    void doesNotDemandThatThePlanTeachAnAlreadyEvidencedPrerequisite() {
        // n1 (SQL) depends on n3 (Basic programming, kind CURRENT), and no phase teaches n3.
        assertTrue(step.validate(validPath(), GAP_IDS, RESOURCE_KEYS, 500, GRAPH).isEmpty());
    }

    @Test
    void rejectsAPhaseReferencingAGraphNodeThatDoesNotExist() {
        LearningPathDto path = validPath();
        path.phases.get(0).skillNodeIds = List.of("n99");

        List<String> problems = step.validate(path, GAP_IDS, RESOURCE_KEYS, 500, GRAPH);

        assertTrue(problems.stream().anyMatch(p -> p.contains("n99")), problems.toString());
    }

    /**
     * Without ids the prerequisite check cannot run at all, and a silently unchecked plan is the
     * state this whole fix exists to leave behind.
     */
    @Test
    void rejectsAPhaseWithNoGraphNodeIds() {
        LearningPathDto path = validPath();
        path.phases.get(0).skillNodeIds = List.of();

        List<String> problems = step.validate(path, GAP_IDS, RESOURCE_KEYS, 500, GRAPH);

        assertTrue(problems.stream().anyMatch(p -> p.contains("no skillNodeIds")), problems.toString());
    }

    /** No graph means no graph check; the rest of the validation still applies. */
    @Test
    void skipsTheGraphCheckWhenThereIsNoGraph() {
        LearningPathDto path = validPath();
        path.phases.forEach(phase -> phase.skillNodeIds = null);

        assertTrue(step.validate(path, GAP_IDS, RESOURCE_KEYS, 500, new SkillGraphDto()).isEmpty());
    }

    // ---- citations ----

    /**
     * The single most important rule here. A citation the model produced from memory cannot be
     * traced to anything, and the URL attached to it is frequently wrong in a way nobody notices.
     */
    @Test
    void rejectsACitationToADocumentThatWasNeverRetrieved() {
        LearningPathDto path = validPath();
        path.phases.get(0).resources.get(0).resourceKey = "some-course-i-remember";

        List<String> problems = step.validate(path, GAP_IDS, RESOURCE_KEYS, 200, GRAPH);

        assertTrue(problems.stream().anyMatch(p -> p.contains("some-course-i-remember")),
                problems.toString());
    }

    @Test
    void rejectsAModelSuppliedUrl() {
        LearningPathDto path = validPath();
        path.phases.get(0).resources.get(0).url = "https://example.com/invented";

        List<String> problems = step.validate(path, GAP_IDS, RESOURCE_KEYS, 200, GRAPH);

        assertTrue(problems.stream().anyMatch(p -> p.contains("Do not write URLs")), problems.toString());
    }

    /**
     * When retrieval genuinely found nothing, a phase with no sources is the honest answer and must
     * not be rejected - otherwise the repair loop pushes the model towards inventing one.
     */
    @Test
    void allowsAPhaseWithNoSourcesWhenNothingWasRetrieved() {
        LearningPathDto path = validPath();
        path.phases.forEach(phase -> phase.resources = List.of());

        assertTrue(step.validate(path, GAP_IDS, Set.of(), 200, GRAPH).isEmpty());
    }

    @Test
    void requiresSourcesWhenSomeWereRetrieved() {
        LearningPathDto path = validPath();
        path.phases.get(0).resources = List.of();

        List<String> problems = step.validate(path, GAP_IDS, RESOURCE_KEYS, 200, GRAPH);

        assertTrue(problems.stream().anyMatch(p -> p.contains("cites no resources")), problems.toString());
    }

    // ---- time budget ----

    /**
     * A plan that overruns the budget is rejected; one that comes in under it is not. "You have 120
     * hours, here is 90 hours of work and here is why the rest does not fit" is an honest answer,
     * and padding it out would not be.
     */
    @Test
    void rejectsAPlanThatOverrunsTheHoursAvailable() {
        LearningPathDto path = validPath();
        path.phases.get(0).estimatedHours = 400;

        List<String> problems = step.validate(path, GAP_IDS, RESOURCE_KEYS, 100, GRAPH);

        assertTrue(problems.stream().anyMatch(p -> p.contains("hours but the student only has")),
                problems.toString());
    }

    @Test
    void acceptsAPlanThatUsesLessThanTheHoursAvailable() {
        assertTrue(step.validate(validPath(), GAP_IDS, RESOURCE_KEYS, 500, GRAPH).isEmpty());
    }

    // ---- structure ----

    @Test
    void rejectsPhasesThatOverlapOrLeaveAGapInTheWeeks() {
        LearningPathDto path = validPath();
        path.phases.get(1).startWeek = 8;

        List<String> problems = step.validate(path, GAP_IDS, RESOURCE_KEYS, 500, GRAPH);

        assertTrue(problems.stream().anyMatch(p -> p.contains("consecutively")), problems.toString());
    }

    @Test
    void rejectsAReferenceToAGapThatWasNotIdentified() {
        LearningPathDto path = validPath();
        path.phases.get(0).addressesGapIds = List.of("gap-that-does-not-exist");

        List<String> problems = step.validate(path, GAP_IDS, RESOURCE_KEYS, 500, GRAPH);

        assertTrue(problems.stream().anyMatch(p -> p.contains("gap-that-does-not-exist")),
                problems.toString());
    }

    /**
     * The graph decided the order; this is the check that the plan actually respected it. A phase
     * that needs a skill taught two phases later is the failure the whole graph step exists to
     * prevent.
     */
    @Test
    void rejectsAPhaseThatNeedsASkillTaughtLater() {
        LearningPathDto path = validPath();
        path.phases.get(0).prerequisiteSkills = List.of("Automated testing");
        path.phases.get(1).skills = List.of("Automated testing");

        List<String> problems = step.validate(path, GAP_IDS, RESOURCE_KEYS, 500, GRAPH);

        assertTrue(problems.stream().anyMatch(p -> p.contains("Move it earlier")), problems.toString());
    }

    /**
     * A prerequisite the plan never teaches is not an error: it is something the student is assumed
     * to already have, which the phase says out loud.
     */
    @Test
    void allowsAPrerequisiteThePlanDoesNotTeach() {
        LearningPathDto path = validPath();
        path.phases.get(0).prerequisiteSkills = List.of("Basic programming");

        assertTrue(step.validate(path, GAP_IDS, RESOURCE_KEYS, 500, GRAPH).isEmpty());
    }

    @Test
    void requiresAFeasibilityVerdict() {
        LearningPathDto path = validPath();
        path.feasibility = null;

        List<String> problems = step.validate(path, GAP_IDS, RESOURCE_KEYS, 500, GRAPH);

        assertTrue(problems.stream().anyMatch(p -> p.contains("feasibility.verdict")), problems.toString());
    }

    // ---- finalise ----

    /**
     * The catalogue row, not the model, supplies what the student sees. This is what keeps a link
     * pointing at a document that exists even when the model's description of it drifted.
     */
    @Test
    void attachesCatalogueFactsAndIssuesIds() {
        LearningPathDto path = validPath();
        CareerGoalDto goal = new CareerGoalDto();
        goal.durationMonths = 3;
        goal.hoursPerWeek = 10;

        LearningResourceDoc doc = new LearningResourceDoc();
        doc.setResourceKey("mdn-web-docs");
        doc.setTitle("MDN Web Docs");
        doc.setProvider("Mozilla");
        doc.setUrl("https://developer.mozilla.org/en-US/docs/Web");
        doc.setCost("FREE");
        doc.setUrlReachable(true);

        step.finalise(path, goal, List.of(doc));

        LearningPathDto.Phase first = path.phases.get(0);
        assertNotNull(first.id);
        assertNotNull(first.activities.get(0).id);
        assertNotNull(first.completionCriteria.get(0).id);
        assertEquals("Mozilla", first.resources.get(0).provider);
        assertEquals("https://developer.mozilla.org/en-US/docs/Web", first.resources.get(0).url);
        assertEquals(130, path.budgetHours, "3 months at 10h/week, 4.33 weeks per month");
        assertEquals(60, path.plannedHours, "summed from the phases, not taken from the model");
        assertEquals(6, path.allCheckableIds().size());
    }

    /**
     * Validation should catch an unknown key first. If one still gets through, dropping the
     * citation beats rendering a resource with no source behind it.
     */
    @Test
    void dropsACitationWhoseDocumentIsNotInTheCatalogue() {
        LearningPathDto path = validPath();
        CareerGoalDto goal = new CareerGoalDto();
        goal.durationMonths = 1;
        goal.hoursPerWeek = 5;

        step.finalise(path, goal, List.of());

        assertTrue(path.phases.get(0).resources.isEmpty());
    }

    @Test
    void reportsZeroBudgetWhenTheGoalIsIncomplete() {
        CareerGoalDto goal = new CareerGoalDto();
        assertEquals(0, goal.budgetHours());
        // A zero budget must not then reject every plan for overrunning it.
        assertNull(goal.durationMonths);
        assertFalse(step.validate(validPath(), GAP_IDS, RESOURCE_KEYS, 0, GRAPH).stream()
                .anyMatch(p -> p.contains("hours but the student only has")));
    }

    // ---- fixtures ----

    private LearningPathDto validPath() {
        LearningPathDto path = new LearningPathDto();
        path.expectedOutcome = "A small deployed application you can walk someone through.";

        LearningPathDto.Feasibility feasibility = new LearningPathDto.Feasibility();
        feasibility.verdict = "FITS";
        feasibility.note = "Comfortable at the stated pace.";
        path.feasibility = feasibility;

        path.phases = new ArrayList<>(List.of(
                phase(1, "Foundations", 1, 4, 30, List.of("SQL"), List.of("n1"), "gap-a"),
                phase(2, "Building on it", 5, 8, 30, List.of("Automated testing"), List.of("n2"), "gap-b")));
        return path;
    }

    private LearningPathDto.Phase phase(int order, String title, int startWeek, int endWeek,
                                        int hours, List<String> skills, List<String> nodeIds,
                                        String gapId) {
        LearningPathDto.Phase phase = new LearningPathDto.Phase();
        phase.skillNodeIds = new ArrayList<>(nodeIds);
        phase.order = order;
        phase.title = title;
        phase.goal = "Be able to do " + title.toLowerCase();
        phase.startWeek = startWeek;
        phase.endWeek = endWeek;
        phase.estimatedHours = hours;
        phase.skills = new ArrayList<>(skills);
        phase.prerequisiteSkills = new ArrayList<>();
        phase.addressesGapIds = new ArrayList<>(List.of(gapId));
        phase.orderingRationale = "It builds on what came before.";

        LearningPathDto.Activity activity = new LearningPathDto.Activity();
        activity.title = "Build the thing";
        activity.description = "Write it, then test it.";
        activity.estimatedHours = 10;
        activity.type = "BUILD";
        phase.activities = new ArrayList<>(List.of(activity));

        LearningPathDto.CompletionCriterion criterion = new LearningPathDto.CompletionCriterion();
        criterion.text = "It runs and the tests pass.";
        phase.completionCriteria = new ArrayList<>(List.of(criterion));

        LearningPathDto.ResourceRef ref = new LearningPathDto.ResourceRef();
        ref.resourceKey = "mdn-web-docs";
        ref.forSkill = skills.get(0);
        ref.whyChosen = "It is the reference for this.";
        phase.resources = new ArrayList<>(List.of(ref));

        return phase;
    }
}
