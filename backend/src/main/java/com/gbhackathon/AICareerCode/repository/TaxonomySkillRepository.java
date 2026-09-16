package com.gbhackathon.AICareerCode.repository;

import com.gbhackathon.AICareerCode.model.TaxonomySkill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaxonomySkillRepository extends JpaRepository<TaxonomySkill, Long> {

    List<TaxonomySkill> findBySource(String source);

    Optional<TaxonomySkill> findByCodeAndSource(String code, String source);

    long countBySource(String source);

    void deleteBySource(String source);
}
