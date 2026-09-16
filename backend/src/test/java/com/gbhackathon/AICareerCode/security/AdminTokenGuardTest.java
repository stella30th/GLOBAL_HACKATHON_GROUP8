package com.gbhackathon.AICareerCode.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one shared-secret check behind every operator action that replaces reference data
 * (SFIA upload, SFIA reload, and the reference-data reload endpoint). An admin request wrong or
 * missing this token is exactly the request the whole class exists to refuse.
 */
class AdminTokenGuardTest {

    private static AdminTokenGuard guardWith(String configuredToken) {
        AdminTokenGuard guard = new AdminTokenGuard();
        try {
            Field field = AdminTokenGuard.class.getDeclaredField("adminToken");
            field.setAccessible(true);
            field.set(guard, configuredToken);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AdminTokenGuard no longer has a field called adminToken", e);
        }
        return guard;
    }

    @Test
    void refusesEverythingWhenNoTokenIsConfigured() {
        AdminTokenGuard guard = guardWith("");

        assertFalse(guard.isConfigured());
        assertFalse(guard.matches("anything"));
        assertNotNull(guard.denyReason("anything"), "unconfigured must fail closed, not open");
    }

    @Test
    void acceptsExactlyTheConfiguredToken() {
        AdminTokenGuard guard = guardWith("correct-token");

        assertTrue(guard.isConfigured());
        assertTrue(guard.matches("correct-token"));
        assertNull(guard.denyReason("correct-token"));
    }

    @Test
    void rejectsAWrongToken() {
        AdminTokenGuard guard = guardWith("correct-token");

        assertFalse(guard.matches("wrong-token"));
        assertNotNull(guard.denyReason("wrong-token"));
    }

    @Test
    void rejectsAMissingToken() {
        AdminTokenGuard guard = guardWith("correct-token");

        assertFalse(guard.matches(null));
        assertNotNull(guard.denyReason(null));
    }

    @Test
    void rejectsAnEmptyPresentedToken() {
        AdminTokenGuard guard = guardWith("correct-token");

        assertFalse(guard.matches(""));
    }

    @Test
    void aBlankConfiguredTokenCountsAsUnconfigured() {
        AdminTokenGuard guard = guardWith("   ");

        assertFalse(guard.isConfigured());
    }
}
