package com.gbhackathon.AICareerCode.service;

import com.gbhackathon.AICareerCode.dto.ChatMessageDto;
import com.gbhackathon.AICareerCode.dto.plan.LearningPathDto;
import com.gbhackathon.AICareerCode.dto.plan.LearningPlanDto;
import com.gbhackathon.AICareerCode.dto.plan.SkillGapDto;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.service.ai.GeminiClient;
import com.gbhackathon.AICareerCode.service.pipeline.PromptSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The "ask about your plan" panel.
 *
 * <p>A support tool, not a second product. It explains what is already on the page - why a skill
 * was judged a gap, why one phase comes before another, how to use a cited resource - and it
 * cannot change anything. Letting a chat reply rewrite the plan or tick a phase would put the
 * student's progress record at the mercy of a conversation nobody validated; adjustments go
 * through regenerating the plan, which produces a new version with its own provenance.
 *
 * <p>It runs on the same two models as everything else. When neither answers, the panel says so
 * and the student can retry; there is no scripted reply pretending to be an answer.
 */
@Service
public class CoachChatService {

    private static final Logger log = LoggerFactory.getLogger(CoachChatService.class);

    /** How many turns of history to send. Enough for a follow-up, bounded for cost. */
    private static final int MAX_HISTORY_TURNS = 12;

    private final GeminiClient gemini;
    private final LearningSnapshotService snapshotService;

    public CoachChatService(GeminiClient gemini, LearningSnapshotService snapshotService) {
        this.gemini = gemini;
        this.snapshotService = snapshotService;
    }

    /**
     * Answers one question.
     *
     * @throws com.gbhackathon.AICareerCode.service.ai.AiUnavailableException when neither model answers
     */
    public Reply chat(UserProfile profile, List<ChatMessageDto> history, String question) {
        Optional<LearningPlanDto> plan = snapshotService.currentPlan(profile);

        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are a study coach answering questions about a learning plan that has already
                been generated for this specific student. The plan is below.

                WHAT YOU DO
                - Explain the reasoning behind the analysis and the plan: why a skill was judged a
                  gap, why one phase comes before another, what a completion criterion is asking for.
                - Help the student get started on a specific activity, or make sense of a resource.
                - Answer in the language the student writes in. Use short Markdown. Be concrete and
                  specific to this plan rather than generic.

                WHAT YOU DO NOT DO
                - You cannot change the plan, add a phase or mark anything complete. If the student
                  wants the plan changed, say that they can update their profile or goal and
                  generate a new plan, which keeps a record of what changed.
                - Do not invent a resource or a URL. If the plan does not cite a source for
                  something, say so.
                - Do not tell the student they lack an ability. The analysis describes what their
                  profile evidences; where it is silent, say the profile does not show it yet.
                - Do not promise a job, a salary or a guaranteed outcome.

                """);
        sb.append(PromptSupport.GROUND_RULES).append('\n');
        sb.append("STUDENT PROFILE:\n").append(PromptSupport.describeCandidate(profile)).append('\n');

        if (plan.isPresent()) {
            sb.append("THE CURRENT PLAN:\n").append(summarise(plan.get())).append('\n');
        } else {
            sb.append("""
                    THE CURRENT PLAN: none has been generated yet.
                    Answer general questions about the profile and the target role, and tell the
                    student that generating a plan will let you explain their specific phases.

                    """);
        }

        List<ChatMessageDto> recent = trimHistory(history);
        if (!recent.isEmpty()) {
            sb.append("CONVERSATION SO FAR:\n");
            for (ChatMessageDto message : recent) {
                String role = "user".equalsIgnoreCase(message.getRole()) ? "Student" : "Coach";
                sb.append(role).append(": ").append(message.getContent()).append('\n');
            }
        }
        sb.append("\nStudent: ").append(question).append("\nCoach:");

        String reply = gemini.generateText(sb.toString());
        Object model = gemini.status().get("lastWorkingModel");
        return new Reply(reply, model == null ? null : model.toString());
    }

    public record Reply(String text, String model) {}

    /**
     * The plan reduced to what a conversation needs.
     *
     * <p>The whole document would be tens of thousands of characters, most of it fields the chat
     * never refers to, and sending it on every turn would burn the quota this panel shares with
     * plan generation.
     */
    private String summarise(LearningPlanDto plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Target: ").append(plan.goal.targetRole)
                .append(" (").append(plan.goal.targetSeniority).append("), ")
                .append(plan.goal.durationMonths).append(" months at ")
                .append(plan.goal.hoursPerWeek).append(" hours per week.\n\n");

        sb.append("SKILL GAPS:\n");
        for (SkillGapDto gap : plan.skillGaps) {
            sb.append("- ").append(gap.skillLabel)
                    .append(" [").append(gap.severity).append(", evidence: ").append(gap.evidenceStatus)
                    .append("] ").append(gap.rationale == null ? "" : gap.rationale).append('\n');
        }

        if (plan.learningPath != null && plan.learningPath.phases != null) {
            sb.append("\nPHASES:\n");
            for (LearningPathDto.Phase phase : plan.learningPath.phases) {
                sb.append("Phase ").append(phase.order).append(": ").append(phase.title)
                        .append(" (weeks ").append(phase.startWeek).append('-').append(phase.endWeek)
                        .append(", ").append(phase.estimatedHours).append(" hours)\n");
                sb.append("  Goal: ").append(phase.goal).append('\n');
                if (phase.orderingRationale != null) {
                    sb.append("  Why now: ").append(phase.orderingRationale).append('\n');
                }
                if (phase.skills != null) {
                    sb.append("  Skills: ").append(String.join(", ", phase.skills)).append('\n');
                }
                if (phase.activities != null) {
                    for (LearningPathDto.Activity activity : phase.activities) {
                        sb.append("  - Activity: ").append(activity.title)
                                .append(" (").append(activity.estimatedHours).append("h) ")
                                .append(activity.description == null ? "" : activity.description).append('\n');
                    }
                }
                if (phase.resources != null) {
                    for (LearningPathDto.ResourceRef ref : phase.resources) {
                        sb.append("  - Source: ").append(ref.title).append(" by ").append(ref.provider)
                                .append(" (").append(ref.url).append(") for ").append(ref.forSkill).append('\n');
                    }
                }
            }
            if (plan.learningPath.feasibility != null) {
                sb.append("\nFeasibility: ").append(plan.learningPath.feasibility.verdict)
                        .append(" - ").append(plan.learningPath.feasibility.note).append('\n');
            }
        }

        if (plan.provenance != null && plan.provenance.limitations != null
                && !plan.provenance.limitations.isEmpty()) {
            sb.append("\nKNOWN LIMITATIONS OF THIS ANALYSIS (be honest about these if asked):\n");
            plan.provenance.limitations.forEach(l -> sb.append("- ").append(l).append('\n'));
        }
        return sb.toString();
    }

    private List<ChatMessageDto> trimHistory(List<ChatMessageDto> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.size() <= MAX_HISTORY_TURNS
                ? history
                : history.subList(history.size() - MAX_HISTORY_TURNS, history.size());
    }
}
