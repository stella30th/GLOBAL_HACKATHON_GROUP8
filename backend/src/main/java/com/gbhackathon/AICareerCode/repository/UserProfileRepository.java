package com.gbhackathon.AICareerCode.repository;

import com.gbhackathon.AICareerCode.model.UserProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    /**
     * The one row a request is allowed to see. Every read path goes through this: there is no
     * "current profile" independent of who is asking, and reintroducing one would put a stranger's
     * CV back on the page.
     */
    Optional<UserProfile> findBySessionId(String sessionId);

    Optional<UserProfile> findByEmail(String email);

    /**
     * Row-level lock held only for the duration of a snapshot or progress write.
     *
     * <p>Two browser tabs opening the roadmap at the same time both generate an audit, and a fast
     * double-click on a checkbox produces two overlapping read-modify-write cycles. Both cases
     * previously ended with one result overwriting the other. The lock is taken after any AI call
     * has already returned, so a slow model never holds a database row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserProfile p where p.id = :id")
    Optional<UserProfile> findByIdForUpdate(@Param("id") Long id);
}
