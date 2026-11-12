package digital.rya.opsagent.actions;

import java.time.Instant;
import java.util.List;

/**
 * An action and everything that happened to it. Immutable: each state change creates a new
 * copy, which the ActionService stores under the same id.
 *
 * @param oldValue   the setting's value when the action was proposed (used for the stale check)
 * @param policyNote why the policy decided AUTO / NEEDS_APPROVAL / BLOCK
 * @param warnings   risks a reviewer should read before approving
 */
public record PlatformAction(
        String id,
        ActionType type,
        String targetId,
        String oldValue,
        String newValue,
        String reason,
        String requestedBy,
        String requestId,
        ActionStatus status,
        String policyNote,
        List<String> warnings,
        Instant createdAt,
        Instant decidedAt,
        String decidedBy,
        String decisionNote) {

    public PlatformAction {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public PlatformAction withDecision(ActionStatus newStatus, Instant at, String by, String note) {
        return new PlatformAction(id, type, targetId, oldValue, newValue, reason, requestedBy, requestId, newStatus,
                policyNote, warnings, createdAt, at, by, note);
    }
}
