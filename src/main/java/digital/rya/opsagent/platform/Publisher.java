package digital.rya.opsagent.platform;

import java.util.List;

/**
 * A traffic source. {@code flaggedForReview} and {@code status} are the two things the agent
 * can change; everything else comes from publishers.json.
 *
 * @param flagReason why it was flagged (null if not flagged)
 */
public record Publisher(
        String publisherId,
        String name,
        List<String> campaignIds,
        double payoutPerPayableCall,
        PublisherStatus status,
        boolean flaggedForReview,
        String flagReason) {

    public enum PublisherStatus { ACTIVE, PAUSED }

    public Publisher {
        campaignIds = List.copyOf(campaignIds);
    }

    public Publisher withFlag(String reason) {
        return new Publisher(publisherId, name, campaignIds, payoutPerPayableCall, status, true, reason);
    }

    public Publisher withStatus(PublisherStatus newStatus) {
        return new Publisher(publisherId, name, campaignIds, payoutPerPayableCall, newStatus, flaggedForReview, flagReason);
    }
}
