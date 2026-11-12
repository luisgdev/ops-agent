package digital.rya.opsagent.platform;

/** A campaign and the minimum duration for a call to be billable. */
public record Campaign(String campaignId, String name, String vertical, int payableAfterSec) {
}
