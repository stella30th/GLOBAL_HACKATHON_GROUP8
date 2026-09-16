package com.gbhackathon.AICareerCode.config;

import com.gbhackathon.AICareerCode.model.UserProfile;
import com.gbhackathon.AICareerCode.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Removes profile rows left behind by the version that had no sessions.
 *
 * <p>Those rows are not one person's data. The previous build served a single profile to everyone
 * and overwrote it on every save, so whatever is in them is a collision between however many
 * people used the deployment - one person's CV text next to another person's target role, with no
 * way to tell which field came from whom. There is nothing to migrate and nobody to return it to,
 * and leaving it in place would mean the first visitor after the upgrade could still be handed it.
 *
 * <p>Deleting user data on start-up is not something to do casually, so the scope is deliberately
 * narrow: only rows with no {@code session_id} at all, which no code path in this version can
 * create. Once the upgrade has run anywhere, this finds nothing and stays silent.
 */
@Component
public class LegacyProfilePurge {

    private static final Logger log = LoggerFactory.getLogger(LegacyProfilePurge.class);

    private final UserProfileRepository repository;

    public LegacyProfilePurge(UserProfileRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void purge() {
        List<UserProfile> orphans = repository.findAll().stream()
                .filter(profile -> profile.getSessionId() == null || profile.getSessionId().isBlank())
                .toList();

        if (orphans.isEmpty()) {
            return;
        }

        // Counted, never logged in detail: the rows contain CV text.
        log.warn("Removing {} profile row(s) from before sessions existed. They were shared between "
                + "everyone using this deployment and cannot be attributed to any one person.",
                orphans.size());
        repository.deleteAll(orphans);
    }
}
