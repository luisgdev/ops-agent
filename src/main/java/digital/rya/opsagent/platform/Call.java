package digital.rya.opsagent.platform;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One row of calls.csv. Immutable: call history is a fact, the agent never edits it.
 *
 * @param buyerId null when the call was not routed (NOT_ROUTED_* and DUPLICATE)
 */
public record Call(
        String callId,
        OffsetDateTime startedAt,
        String campaignId,
        String publisherId,
        String buyerId,
        String callerState,
        String callerHash,
        int durationSec,
        CallStatus status,
        double revenue,
        double payout) {

    /** Platform day (the timestamps are already in US Central time). */
    public LocalDate day() {
        return startedAt.toLocalDate();
    }

    public boolean isRouted() {
        return buyerId != null;
    }

    public double margin() {
        return revenue - payout;
    }
}
