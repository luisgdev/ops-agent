package digital.rya.opsagent.platform;

import java.time.OffsetDateTime;

/** A buyer's self-reported number of free licensed agents (every 15 minutes). */
public record AvailabilityPing(OffsetDateTime timestamp, String buyerId, int agentsAvailable) {
}
