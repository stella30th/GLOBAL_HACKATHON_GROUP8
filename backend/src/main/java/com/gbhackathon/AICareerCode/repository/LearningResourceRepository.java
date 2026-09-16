package com.gbhackathon.AICareerCode.repository;

import com.gbhackathon.AICareerCode.model.LearningResourceDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LearningResourceRepository extends JpaRepository<LearningResourceDoc, Long> {

    Optional<LearningResourceDoc> findByResourceKey(String resourceKey);

    List<LearningResourceDoc> findByResourceKeyIn(List<String> resourceKeys);
}
