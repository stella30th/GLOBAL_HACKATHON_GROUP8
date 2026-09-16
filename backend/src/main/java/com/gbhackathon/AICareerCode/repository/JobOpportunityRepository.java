package com.gbhackathon.AICareerCode.repository;

import com.gbhackathon.AICareerCode.model.JobOpportunity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JobOpportunityRepository extends JpaRepository<JobOpportunity, Long> {

    List<JobOpportunity> findByIsOverseas(Boolean isOverseas);

    /**
     * Dedupe check for the importer. Replaces a per-posting full table scan that ran
     * {@code searchJobs(title, ...)} and then filtered the result in memory.
     */
    boolean existsByTitleIgnoreCaseAndCompanyIgnoreCase(String title, String company);

    List<JobOpportunity> findByVisaSponsorshipTrue();

    @Query("SELECT j FROM JobOpportunity j WHERE " +
           "(:keyword IS NULL OR LOWER(j.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(j.company) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(COALESCE(j.requiredSkills, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(COALESCE(j.category, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))) AND " +
           "(:isOverseas IS NULL OR j.isOverseas = :isOverseas) AND " +
           "(:workType IS NULL OR j.workType = :workType) AND " +
           "(:visaSponsorship IS NULL OR j.visaSponsorship = :visaSponsorship)")
    List<JobOpportunity> searchJobs(@Param("keyword") String keyword,
                                    @Param("isOverseas") Boolean isOverseas,
                                    @Param("workType") String workType,
                                    @Param("visaSponsorship") Boolean visaSponsorship);
}
