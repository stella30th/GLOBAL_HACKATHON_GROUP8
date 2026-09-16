package com.gbhackathon.AICareerCode.service;

import com.gbhackathon.AICareerCode.dto.MatchResultDto;
import com.gbhackathon.AICareerCode.model.JobOpportunity;
import com.gbhackathon.AICareerCode.model.UserProfile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class JobMatchingService {

    private final JobService jobService;

    public JobMatchingService(JobService jobService) {
        this.jobService = jobService;
    }

    public List<MatchResultDto> matchJobsForProfile(UserProfile profile, List<JobOpportunity> jobs) {
        return jobs.stream()
                .map(job -> evaluateMatch(profile, job))
                .sorted((a, b) -> Integer.compare(b.getOverallScore(), a.getOverallScore()))
                .collect(Collectors.toList());
    }

    public MatchResultDto evaluateMatch(UserProfile profile, JobOpportunity job) {
        MatchResultDto result = new MatchResultDto();
        result.setJob(jobService.toDto(job));

        List<String> userSkills = profile.getSkillList();
        List<String> reqSkills = job.getRequiredSkillList();
        List<String> prefSkills = job.getPreferredSkillList();

        // 1. Skill overlap
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String req : reqSkills) {
            if (containsSkill(userSkills, req)) matched.add(req); else missing.add(req);
        }
        for (String pref : prefSkills) {
            if (containsSkill(userSkills, pref)) {
                if (!matched.contains(pref)) matched.add(pref);
            } else if (!missing.contains(pref)) {
                missing.add(pref);
            }
        }

        // An empty requirements list means "unknown", not "everything matches". Scoring it as a
        // perfect 1.0 (as the previous version did) handed a full skill score to every posting whose
        // board publishes no tags.
        double reqRatio = reqSkills.isEmpty()
                ? 0.35
                : (double) reqSkills.stream().filter(r -> containsSkill(userSkills, r)).count() / reqSkills.size();
        double prefRatio = prefSkills.isEmpty()
                ? 0.35
                : (double) prefSkills.stream().filter(p -> containsSkill(userSkills, p)).count() / prefSkills.size();

        int skillScore = (int) Math.min(100, Math.round((reqRatio * 75.0) + (prefRatio * 25.0)));
        result.setSkillsScore(skillScore);
        result.setMatchedSkills(matched);
        result.setMissingSkills(missing);

        // 2. Field relevance. Without this, an integrated-circuit candidate scored 80% on a backend
        // engineering role purely because they were willing to relocate and met the years-of-experience
        // bar. Relevance is the gate: a role in another profession cannot be a strong match.
        int domainScore = scoreDomainRelevance(profile, job);
        result.setDomainScore(domainScore);

        // 3. Experience
        double userExp = profile.getYearsOfExperience() != null ? profile.getYearsOfExperience() : 0.0;
        int jobMinExp = job.getMinYearsExp() != null ? job.getMinYearsExp() : 0;
        int expScore;
        if (userExp >= jobMinExp) {
            expScore = 100;
        } else {
            double shortfall = jobMinExp - userExp;
            expScore = (int) Math.max(20, Math.round(100 - shortfall * 20));
        }
        result.setExperienceScore(expScore);

        // 4. Location and work authorisation
        int relocationScore;
        String visaSuitability;
        if (Boolean.TRUE.equals(job.getIsOverseas())) {
            boolean userWilling = Boolean.TRUE.equals(profile.getWillingToRelocate());
            boolean isRemoteJob = "REMOTE".equalsIgnoreCase(job.getWorkType());
            boolean hasVisaSupport = Boolean.TRUE.equals(job.getVisaSponsorship());

            if (isRemoteJob) {
                relocationScore = 95;
                visaSuitability = "Remote role - no visa required, but confirm which countries the employer can hire from.";
            } else if (userWilling && hasVisaSupport) {
                relocationScore = 90;
                visaSuitability = "The posting states visa sponsorship for " + safe(job.getCountry()) + ".";
            } else if (userWilling) {
                relocationScore = 70;
                visaSuitability = "Sponsorship is not stated - check work authorisation requirements for " + safe(job.getCountry()) + ".";
            } else {
                relocationScore = 40;
                visaSuitability = "Onsite role abroad while your profile is not open to relocation.";
            }
        } else {
            relocationScore = 95;
            visaSuitability = "Domestic role - standard local employment terms.";
        }
        result.setRelocationScore(relocationScore);
        result.setVisaSuitability(visaSuitability);

        // 5. Overall. Field relevance carries real weight and also caps the result: scoring 90% on a
        // job from a different profession is worse than useless to the candidate.
        int overallScore = (int) Math.round(
                (skillScore * 0.30) + (domainScore * 0.40) + (expScore * 0.20) + (relocationScore * 0.10));
        // A role in another profession cannot be a strong match, however transferable the keywords
        // look. Capping keeps unrelated postings out of the top of the list entirely.
        if (domainScore < 40) {
            overallScore = Math.min(overallScore, 30);
        }
        overallScore = Math.max(5, Math.min(99, overallScore));
        result.setOverallScore(overallScore);

        result.setAiSummary(buildSummary(profile, job, overallScore, domainScore, matched, missing));
        result.setActionItems(buildActionItems(job, domainScore, matched, missing));
        return result;
    }

    /**
     * How close this posting is to the candidate's profession.
     *
     * <p>The candidate and the posting are each classified into a {@link CareerField}, then compared.
     * Counting shared words does not work here: a semiconductor CV and a marketing posting both
     * contain "design", and that coincidence alone was enough to score unrelated roles near 50%.
     */
    private int scoreDomainRelevance(UserProfile profile, JobOpportunity job) {
        CareerField candidateField = classifyCandidate(profile);
        CareerField jobField = classifyJob(job);

        int score;
        if (candidateField == CareerField.GENERAL || jobField == CareerField.GENERAL) {
            // One side could not be classified. Stay cautious rather than neutral: an unknown role
            // should not outrank a confirmed same-field one.
            score = 45;
        } else if (candidateField == jobField) {
            score = 85;
        } else if (candidateField.isAdjacentTo(jobField)) {
            score = 60;
        } else {
            score = 12;
        }

        // A posting whose title literally contains one of the candidate's target roles is the
        // strongest signal available, and can lift a role the classifier was unsure about.
        String jobTitle = safe(job.getTitle()).toLowerCase(Locale.ROOT);
        for (String role : profile.getTargetRoleList()) {
            String normalized = role.toLowerCase(Locale.ROOT).trim();
            if (normalized.length() >= 4 && jobTitle.contains(normalized)) {
                score = Math.max(score, 90);
                break;
            }
        }

        return Math.max(5, Math.min(100, score));
    }

    private CareerField classifyCandidate(UserProfile profile) {
        StringBuilder sb = new StringBuilder();
        append(sb, profile.getIndustry());
        append(sb, profile.getCurrentTitle());
        append(sb, profile.getTargetRoles());
        for (String skill : profile.getSkillList()) {
            append(sb, skill);
        }
        return CareerField.classify(sb.toString());
    }

    private CareerField classifyJob(JobOpportunity job) {
        StringBuilder sb = new StringBuilder();
        // Title and tags describe the role; a long description mostly describes the employer and
        // will drag an unrelated posting into a technical field if it is given equal weight. The
        // title is repeated, and only the opening of the description is considered.
        for (int i = 0; i < 4; i++) {
            append(sb, job.getTitle());
        }
        append(sb, job.getCategory());
        append(sb, job.getRequiredSkills());
        String description = job.getDescription();
        if (description != null && !description.isBlank()) {
            append(sb, description.length() > 300 ? description.substring(0, 300) : description);
        }
        return CareerField.classify(sb.toString());
    }

    private void append(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(value).append(' ');
        }
    }

    private String buildSummary(UserProfile profile, JobOpportunity job, int overall, int domainScore,
                                List<String> matched, List<String> missing) {
        String field = profile.getIndustry() != null ? profile.getIndustry() : "your field";

        if (domainScore < 40) {
            return String.format(
                    "This role sits outside %s, so it is a weak fit despite any incidental keyword overlap. "
                            + "Treat it as relevant only if you intend to change profession.", field);
        }
        if (overall >= 78) {
            return String.format(
                    "Strong fit: you meet %d of %d listed requirements for %s at %s, and the role is squarely in %s.",
                    matched.size(), matched.size() + missing.size(), safe(job.getTitle()), safe(job.getCompany()), field);
        }
        if (overall >= 55) {
            return String.format(
                    "Partial fit at %d%%. The main gaps for %s are %s - close those and this becomes a realistic target.",
                    overall, safe(job.getCompany()),
                    missing.isEmpty() ? "experience depth rather than specific skills"
                            : String.join(", ", missing.subList(0, Math.min(3, missing.size()))));
        }
        return String.format(
                "Reach role. It requires deeper experience in %s. Keep it as a target after the next stage of your roadmap.",
                missing.isEmpty() ? "the core responsibilities" : String.join(", ", missing.subList(0, Math.min(3, missing.size()))));
    }

    private List<String> buildActionItems(JobOpportunity job, int domainScore, List<String> matched, List<String> missing) {
        List<String> actions = new ArrayList<>();
        if (domainScore < 40) {
            actions.add("Only pursue this if you are deliberately changing field - your current profile does not evidence this profession.");
            return actions;
        }
        if (!missing.isEmpty()) {
            actions.add("Add evidence for: " + String.join(", ", missing.subList(0, Math.min(3, missing.size()))));
        }
        if (Boolean.TRUE.equals(job.getIsOverseas()) && !"REMOTE".equalsIgnoreCase(job.getWorkType())) {
            actions.add("Prepare an international-format CV and confirm work authorisation for " + safe(job.getCountry()) + ".");
        }
        if (!matched.isEmpty()) {
            actions.add("Prepare a worked example for each strength you already match: "
                    + String.join(", ", matched.subList(0, Math.min(3, matched.size()))));
        } else {
            actions.add("Mirror the exact terminology from this posting wherever it truthfully describes your work.");
        }
        return actions;
    }

    private boolean containsSkill(List<String> userSkills, String targetSkill) {
        if (userSkills == null || targetSkill == null) return false;
        String target = targetSkill.trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) return false;

        for (String s : userSkills) {
            String candidate = s.trim().toLowerCase(Locale.ROOT);
            if (candidate.isEmpty()) continue;
            if (candidate.equals(target)) return true;
            // Require a reasonable length before accepting substring overlap; "c" or "r" as a skill
            // otherwise matched almost every requirement string.
            if (candidate.length() >= 4 && target.contains(candidate)) return true;
            if (target.length() >= 4 && candidate.contains(target)) return true;
            if (areSynonyms(candidate, target)) return true;
        }
        return false;
    }

    private static final List<List<String>> SYNONYM_GROUPS = List.of(
            List.of("kubernetes", "k8s"),
            List.of("postgresql", "postgres"),
            List.of("javascript", "js"),
            List.of("typescript", "ts"),
            List.of("golang", "go"),
            List.of("verilog", "verilog hdl", "rtl", "rtl design"),
            List.of("systemverilog", "system verilog"),
            List.of("fpga", "field programmable gate array"),
            List.of("asic", "application specific integrated circuit"),
            List.of("machine learning", "ml"),
            List.of("artificial intelligence", "ai"),
            List.of("continuous integration", "ci/cd"),
            List.of("amazon web services", "aws"),
            List.of("google cloud", "gcp"));

    private boolean areSynonyms(String a, String b) {
        for (List<String> group : SYNONYM_GROUPS) {
            if (group.contains(a) && group.contains(b)) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "this role" : value;
    }
}
