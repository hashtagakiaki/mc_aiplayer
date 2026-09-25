package io.github.zoyluo.aibot.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MissionRecoveryEligibilityTest {
    @Test
    void incompleteObservationCanRecoverButPermissionDenialCannot() {
        assertTrue(GoalExecutor.eligibleForMissionRecovery("no_resource_nearby: none observed"));
        assertFalse(GoalExecutor.eligibleForMissionRecovery(
                "Capability decision DENIED_STRICT_SURVIVAL"));
        assertFalse(GoalExecutor.eligibleForMissionRecovery("permission_denied: container"));
        assertFalse(GoalExecutor.eligibleForMissionRecovery("invalid_checkpoint: unknown schema"));
    }
}
