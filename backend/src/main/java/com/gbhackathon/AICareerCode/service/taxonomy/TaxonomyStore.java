package com.gbhackathon.AICareerCode.service.taxonomy;

import com.gbhackathon.AICareerCode.model.TaxonomySkill;
import com.gbhackathon.AICareerCode.repository.TaxonomySkillRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The transactional writes against {@code taxonomy_skills}.
 *
 * <p>Its own bean rather than a private method on {@link TaxonomyService} for the same reason
 * {@code LearningSnapshotStore} is separate: a {@code @Transactional} method called from inside
 * the same bean bypasses the proxy and runs with no transaction at all. That failure is invisible
 * until the delete has something to delete - the first start-up writes cleanly, and the second one
 * fails with "no EntityManager with actual transaction available" once there are rows to remove.
 *
 * <p>Replacing a source is delete-then-insert inside one transaction, so a failure halfway leaves
 * the previous rows in place rather than an empty taxonomy that silently strips every skill code
 * out of the next analysis.
 */
@Component
public class TaxonomyStore {

    private final TaxonomySkillRepository repository;

    public TaxonomyStore(TaxonomySkillRepository repository) {
        this.repository = repository;
    }

    /** Replaces every row of one source atomically. Returns how many rows are now stored. */
    @Transactional
    public int replaceSource(String source, List<TaxonomySkill> rows) {
        repository.deleteBySource(source);
        repository.flush();
        repository.saveAll(rows);
        return rows.size();
    }
}
