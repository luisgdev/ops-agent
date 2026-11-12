package digital.rya.opsagent.actions;

public enum ActionStatus {
    /** Safe enough to apply without a person; already applied. */
    AUTO_APPLIED,
    /** Waiting for a person to approve or reject. Nothing changed yet. */
    PENDING_APPROVAL,
    /** A person approved it and it was applied. */
    APPROVED_APPLIED,
    /** A person rejected it. Nothing changed. */
    REJECTED,
    /** The policy refused it (bad value, missing reason, too risky). Nothing changed. */
    BLOCKED_BY_POLICY,
    /** Dry-run: recorded what would have happened. Nothing changed. */
    DRY_RUN,
    /** Approved, but the setting had changed since it was proposed, so it was not applied. */
    FAILED_STALE
}
