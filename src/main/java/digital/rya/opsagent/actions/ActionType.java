package digital.rya.opsagent.actions;

/** The changes the agent can ask for. Each one maps to exactly one platform setting. */
public enum ActionType {
    /** Change a buyer's routing priority (1 = gets calls first). */
    SET_BUYER_PRIORITY,
    /** Change a buyer's daily cap. */
    SET_BUYER_CAP,
    /** Pause or resume a buyer (ACTIVE / PAUSED). */
    SET_BUYER_STATUS,
    /** Mark a publisher for human review. Changes no routing. */
    FLAG_PUBLISHER,
    /** Pause or resume a publisher's traffic (ACTIVE / PAUSED). */
    SET_PUBLISHER_STATUS
}
