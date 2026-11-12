package digital.rya.opsagent.actions;

/**
 * A request to change the platform.
 *
 * @param newValue    the value to set, as text ("2", "100", "PAUSED"); ignored for FLAG_PUBLISHER
 * @param reason      why; mandatory, it goes into the audit log
 * @param requestedBy "agent" or a person's name
 * @param requestId   id of the /agent/ask request that produced it (null if manual)
 */
public record ActionRequest(ActionType type, String targetId, String newValue, String reason, String requestedBy,
                            String requestId) {
}
