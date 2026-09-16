package com.gbhackathon.AICareerCode.service;

import com.gbhackathon.AICareerCode.dto.JobDto;
import com.gbhackathon.AICareerCode.model.JobOpportunity;
import com.gbhackathon.AICareerCode.repository.JobOpportunityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class JobService {

    /**
     * Upper bound on rows returned by a search. Matching scores every returned job and the frontend
     * renders them all, so an unbounded list makes both the response and the browser slower as the
     * imported catalogue grows - noticeable on Render's free tier.
     */
    private static final int MAX_RESULTS = 120;

    private final JobOpportunityRepository jobRepository;

    public JobService(JobOpportunityRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional(readOnly = true)
    public List<JobOpportunity> getAllJobs() {
        return jobRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<JobOpportunity> getJobById(Long id) {
        return jobRepository.findById(id);
    }

    /**
     * Filtering happens in memory rather than in JPQL because the nullable bind parameters in the
     * repository's {@code searchJobs} query cannot have their type inferred by the PostgreSQL
     * driver. The catalogue is small enough that a single fetch plus a stream is cheap, and it
     * behaves identically on MySQL locally and PostgreSQL on Render.
     */
    @Transactional(readOnly = true)
    public List<JobOpportunity> searchJobs(String keyword, Boolean isOverseas, String workType, Boolean visaSponsorship) {
        String cleanKeyword = (keyword != null && !keyword.isBlank())
                ? keyword.trim().toLowerCase(Locale.ROOT) : null;
        String cleanWorkType = (workType != null && !workType.isBlank() && !workType.equalsIgnoreCase("ALL"))
                ? workType : null;

        return jobRepository.findAll().stream()
                .filter(j -> matchesKeyword(j, cleanKeyword))
                .filter(j -> isOverseas == null || isOverseas.equals(Boolean.TRUE.equals(j.getIsOverseas())))
                .filter(j -> cleanWorkType == null
                        || (j.getWorkType() != null && j.getWorkType().equalsIgnoreCase(cleanWorkType)))
                .filter(j -> visaSponsorship == null
                        || visaSponsorship.equals(Boolean.TRUE.equals(j.getVisaSponsorship())))
                // Newest first so a fresh import surfaces immediately.
                .sorted(Comparator.comparing(JobOpportunity::getPostedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_RESULTS)
                .collect(Collectors.toList());
    }

    private boolean matchesKeyword(JobOpportunity job, String keyword) {
        if (keyword == null) {
            return true;
        }
        return contains(job.getTitle(), keyword)
                || contains(job.getCompany(), keyword)
                || contains(job.getRequiredSkills(), keyword)
                || contains(job.getCategory(), keyword)
                || contains(job.getLocation(), keyword);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    public JobDto toDto(JobOpportunity job) {
        JobDto dto = new JobDto();
        dto.setId(job.getId());
        dto.setTitle(job.getTitle());
        dto.setCompany(job.getCompany());
        dto.setCompanyLogo(job.getCompanyLogo());
        dto.setLocation(job.getLocation());
        dto.setCountry(job.getCountry());
        dto.setIsOverseas(job.getIsOverseas());
        dto.setWorkType(job.getWorkType());
        dto.setSalaryRange(job.getSalaryRange());
        dto.setExperienceLevel(job.getExperienceLevel());
        dto.setMinYearsExp(job.getMinYearsExp());
        dto.setRequiredSkills(job.getRequiredSkillList());
        dto.setPreferredSkills(job.getPreferredSkillList());
        dto.setVisaSponsorship(job.getVisaSponsorship());
        dto.setRelocationAssistance(job.getRelocationAssistance());
        dto.setLanguageRequirements(job.getLanguageRequirements());
        dto.setDescription(job.getDescription());
        dto.setRequirements(job.getRequirements());
        dto.setBenefits(job.getBenefits());
        dto.setApplyUrl(job.getApplyUrl());
        dto.setSource(job.getSource());
        dto.setCategory(job.getCategory());
        dto.setPostedAt(job.getPostedAt());
        return dto;
    }

    public List<JobDto> toDtoList(List<JobOpportunity> list) {
        return list.stream().map(this::toDto).collect(Collectors.toList());
    }
}
