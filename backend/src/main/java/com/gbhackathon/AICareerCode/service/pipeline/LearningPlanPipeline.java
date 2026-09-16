package com.gbhackathon.AICareerCode.service.pipeline;

import com.gbhackathon.AICareerCode.dto.ProfileAuditDto;
import com.gbhackathon.AICareerCode.dto.plan.CareerGoalDto;
import com.gbhackathon.AICareerCode.dto.plan.LearningPathDto;
import com.gbhackathon.AICareerCode.dto.plan.LearningPlanDto;
import com.gbhackathon.AICareerCode.dto.plan.SkillGapDto;
import com.gbhackathon.AICareerCode.model.LearningResourceDoc;
import com.gbhackathon.AICareerCode.model.TaxonomySkill;
import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.service.retrieval.ResourceRetrievalService;
import com.gbhackathon.AICareerCode.service.taxonomy.TaxonomyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the five methods in order and assembles one plan from them.
 *
 * <p>The order is not decorative. Extraction gives the evidence; the taxonomy normalises it so two
 * spellings of the same skill stop being two skills; the gap analysis can then compare like with
 * like; the graph turns an unordered gap list into a sequence; and the path spends the student's
 * actual hours on that sequence, citing documents that were retrieved before any of it was written.
 * Remove any step and the one after it loses the thing it operates on.
 *
 * <p>Retrieval happens twice, at the two points where the model would otherwise be working from
 * memory: once over the taxonomy before skills are mapped, once over the document corpus before
 * sources are cited. Both shortlists are recorded in the plan's provenance, so a reader can see
 * exactly what the model had in front of it.
 *
 * <p>Nothing in this class substitutes content when a step fails. A failure propagates, the caller
 * turns it into an explained error, and the student is offered a retry. That is the whole point:
 * a plan on this page is either the model's work grounded in retrieved data, or it is absent.
 */
@Service
public class LearningPlanPipeline {

    private static final Logger log = LoggerFactory.getLogger(LearningPlanPipeline.class);

    /**
     * Bumped when the prompts, the validation rules or the stored shape change enough that an
     * older plan should not be presented as though this pipeline produced it.
     */
    public static final int PIPELINE_VERSION = 1;

    /** How many taxonomy rows and documents the model may be shown. Bounded for cost and focus. */
    private static final int TAXONOMY_PER_TERM = 4;
    private static final int TAXONOMY_LIMIT = 45;
    private static final int RESOURCES_PER_SKILL = 5;
    private static final int RESOURCE_LIMIT = 40;

    private final TaxonomyService taxonomyService;
    private final ResourceRetrievalService retrievalService;
    private final TaxonomyMappingStep taxonomyMappingStep;
    private final GapAndGraphStep gapAndGraphStep;
    private final LearningPathStep learningPathStep;
    private final ProfileAuditStep profileAuditStep;

    public LearningPlanPipeline(TaxonomyService taxonomyService,
                                ResourceRetrievalService retrievalService,
                                TaxonomyMappingStep taxonomyMappingStep,
                                GapAndGraphStep gapAndGraphStep,
                                LearningPathStep learningPathStep,
                                ProfileAuditStep profileAuditStep) {
        this.taxonomyService = taxonomyService;
        this.retrievalService = retrievalService;
        this.taxonomyMappingStep = taxonomyMappingStep;
        this.gapAndGraphStep = gapAndGraphStep;
        this.learningPathStep = learningPathStep;
        this.profileAuditStep = profileAuditStep;
    }

    /**
     * Builds a complete plan. Runs entirely outside any database transaction: this takes tens of
     * seconds across four model calls, and holding a row lock for that would block every other
     * request on the profile.
     */
    public LearningPlanDto generate(UserProfile profile, CareerGoalDto goal) {
        long startedAt = System.currentTimeMillis();
        List<String> limitations = new ArrayList<>();
        Map<String, String> stepModels = new LinkedHashMap<>();
        int repairAttempts = 0;

        // ---- Retrieval 1: the taxonomy rows relevant to this profile and this role ----
        List<String> taxonomyQueries = taxonomyQueries(profile, goal);
        List<TaxonomySkill> retrievedTaxonomy =
                taxonomyService.retrieveForTerms(taxonomyQueries, TAXONOMY_PER_TERM, TAXONOMY_LIMIT);

        if (!taxonomyService.isSfiaLoaded()) {
            limitations.add("SFIA 9 was not loaded when this plan was generated, so skills were "
                    + "normalised against this project's technology vocabulary only. Skill codes "
                    + "shown are not official SFIA codes.");
        }
        if (retrievedTaxonomy.isEmpty()) {
            limitations.add("No taxonomy entry matched this profile, so no skill in this plan carries "
                    + "a standardised code.");
        }

        Map<String, TaxonomySkill> taxonomyByCode = new LinkedHashMap<>();
        retrievedTaxonomy.forEach(s -> taxonomyByCode.put(s.getCode().toUpperCase(Locale.ROOT), s));

        // ---- Step: LLM extraction is already done; normalise it onto the taxonomy ----
        AiStepRunner.StepResult<TaxonomyMappingStep.Result> mapping =
                taxonomyMappingStep.run(profile, goal, retrievedTaxonomy);
        taxonomyMappingStep.decorate(mapping.value(), taxonomyByCode);
        stepModels.put("taxonomy-mapping", mapping.model());
        repairAttempts += mapping.repairAttempts();
        if (mapping.value().referenceDataLimitations != null
                && !mapping.value().referenceDataLimitations.isBlank()) {
            limitations.add(mapping.value().referenceDataLimitations);
        }

        // ---- Step: gap analysis and knowledge graph ----
        AiStepRunner.StepResult<GapAndGraphStep.Result> gapAndGraph = gapAndGraphStep.run(
                goal, mapping.value().profileEvidence, mapping.value().targetRequirements, retrievedTaxonomy);
        gapAndGraphStep.orderGraph(gapAndGraph.value());
        stepModels.put("gap-and-graph", gapAndGraph.model());
        repairAttempts += gapAndGraph.repairAttempts();
        if (gapAndGraph.value().analysisLimitations != null
                && !gapAndGraph.value().analysisLimitations.isBlank()) {
            limitations.add(gapAndGraph.value().analysisLimitations);
        }
        if (gapAndGraph.value().knowledgeGraph.brokenCycles != null
                && !gapAndGraph.value().knowledgeGraph.brokenCycles.isEmpty()) {
            limitations.addAll(gapAndGraph.value().knowledgeGraph.brokenCycles);
        }

        // ---- Retrieval 2: documents for exactly the skills that turned out to be gaps ----
        List<String> gapSkills = new ArrayList<>();
        gapAndGraph.value().skillGaps.forEach(gap -> gapSkills.add(searchTermFor(gap)));
        List<LearningResourceDoc> retrievedResources =
                retrievalService.retrieveForSkills(gapSkills, RESOURCES_PER_SKILL, RESOURCE_LIMIT);

        List<String> unsourced = skillsWithoutSources(gapAndGraph.value().skillGaps, retrievedResources);
        if (!unsourced.isEmpty()) {
            limitations.add("The reference library contains no document for: "
                    + String.join(", ", unsourced)
                    + ". Those phases carry no source, rather than a source chosen at random.");
        }

        // ---- Step: the learning path ----
        AiStepRunner.StepResult<LearningPathDto> path = learningPathStep.run(
                goal, gapAndGraph.value().skillGaps, gapAndGraph.value().knowledgeGraph, retrievedResources);
        learningPathStep.finalise(path.value(), goal, retrievedResources);
        stepModels.put("learning-path", path.model());
        repairAttempts += path.repairAttempts();

        // ---- Step: the profile-as-a-document review ----
        AiStepRunner.StepResult<ProfileAuditDto> audit =
                profileAuditStep.run(profile, goal, mapping.value().profileEvidence);
        stepModels.put("profile-audit", audit.model());
        repairAttempts += audit.repairAttempts();

        LearningPlanDto plan = new LearningPlanDto();
        plan.planId = UUID.randomUUID().toString();
        plan.goal = goal;
        plan.profileEvidence = mapping.value().profileEvidence;
        plan.targetRequirements = mapping.value().targetRequirements;
        plan.skillGaps = gapAndGraph.value().skillGaps;
        plan.knowledgeGraph = gapAndGraph.value().knowledgeGraph;
        plan.learningPath = path.value();
        plan.audit = audit.value();

        LearningPlanDto.Provenance provenance = new LearningPlanDto.Provenance();
        provenance.generatedAt = Instant.now().toString();
        provenance.stepModels = stepModels;
        provenance.pipelineVersion = PIPELINE_VERSION;
        provenance.sfiaLoaded = taxonomyService.isSfiaLoaded();
        provenance.sfiaDatasetVersion = (String) taxonomyService.status().get("sfiaDatasetVersion");
        provenance.corpusVersion = retrievalService.getCorpusVersion();
        provenance.retrievedTaxonomyCodes = retrievedTaxonomy.stream().map(TaxonomySkill::getCode).toList();
        provenance.retrievedResourceKeys =
                retrievedResources.stream().map(LearningResourceDoc::getResourceKey).toList();
        provenance.limitations = limitations;
        provenance.repairAttempts = repairAttempts;
        plan.provenance = provenance;

        log.info("Generated a learning plan for profile {} in {} ms ({} taxonomy rows, {} documents, "
                        + "{} gaps, {} phases, {} repairs)",
                profile.getId(), System.currentTimeMillis() - startedAt,
                retrievedTaxonomy.size(), retrievedResources.size(),
                plan.skillGaps.size(),
                plan.learningPath.phases == null ? 0 : plan.learningPath.phases.size(),
                repairAttempts);
        return plan;
    }

    /**
     * The terms retrieval runs against.
     *
     * <p>The student's own skills, the target role and the seniority, plus the distinctive words
     * from a pasted job description. The job description is reduced to its longer words because
     * feeding several thousand characters into a term-frequency ranker retrieves whatever the
     * advert repeats most, which is usually the company name.
     */
    private List<String> taxonomyQueries(UserProfile profile, CareerGoalDto goal) {
        Set<String> queries = new LinkedHashSet<>();
        profile.getSkillList().forEach(queries::add);
        if (goal.targetRole != null) {
            queries.add(goal.targetRole);
        }
        if (profile.getIndustry() != null && !profile.getIndustry().isBlank()) {
            queries.add(profile.getIndustry());
        }
        if (profile.getCurrentTitle() != null && !profile.getCurrentTitle().isBlank()) {
            queries.add(profile.getCurrentTitle());
        }
        if (goal.jobDescription != null && !goal.jobDescription.isBlank()) {
            String[] words = goal.jobDescription.split("[^\\p{L}\\p{N}+#.]+");
            int added = 0;
            for (String word : words) {
                if (word.length() >= 4 && added < 25 && queries.add(word)) {
                    added++;
                }
            }
        }
        return new ArrayList<>(queries);
    }

    /** What to search the document corpus with for a gap: the label, plus its taxonomy name. */
    private String searchTermFor(SkillGapDto gap) {
        StringBuilder sb = new StringBuilder(gap.skillLabel == null ? "" : gap.skillLabel);
        if (gap.taxonomyName != null && !gap.taxonomyName.isBlank()) {
            sb.append(' ').append(gap.taxonomyName);
        }
        return sb.toString();
    }

    /**
     * Gap skills for which retrieval found nothing at all.
     *
     * <p>Reported rather than papered over. A phase with no source is a real limitation of the
     * library, and saying so is more useful than attaching the nearest unrelated document to make
     * the page look complete.
     */
    private List<String> skillsWithoutSources(List<SkillGapDto> gaps, List<LearningResourceDoc> retrieved) {
        List<String> missing = new ArrayList<>();
        for (SkillGapDto gap : gaps) {
            List<LearningResourceDoc> forSkill = retrievalService.retrieve(searchTermFor(gap), 1);
            if (forSkill.isEmpty()) {
                missing.add(gap.skillLabel);
            }
        }
        return missing;
    }
}
