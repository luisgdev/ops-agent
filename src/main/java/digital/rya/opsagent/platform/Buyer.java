package digital.rya.opsagent.platform;

import java.util.List;
import java.util.Set;

/**
 * A buyer and its routing settings.
 *
 * <p>Immutable on purpose: when an action changes a setting, the store swaps in a new copy
 * ({@code withPriority}, {@code withDailyCap}, ...). That makes "before" and "after" values
 * trivial to record in the audit log and avoids half-updated objects.
 */
public record Buyer(
        String buyerId,
        String name,
        List<String> campaignIds,
        Set<String> states,
        int dailyCap,
        int priority,
        double revenuePerPayableCall,
        BuyerStatus status) {

    public enum BuyerStatus { ACTIVE, PAUSED }

    public Buyer {
        campaignIds = List.copyOf(campaignIds);
        states = Set.copyOf(states);
    }

    /** "*" in buyers.json means the buyer is licensed in every state. */
    public boolean coversState(String state) {
        return states.contains("*") || states.contains(state);
    }

    public boolean buysCampaign(String campaignId) {
        return campaignIds.contains(campaignId);
    }

    public boolean isActive() {
        return status == BuyerStatus.ACTIVE;
    }

    public Buyer withPriority(int newPriority) {
        return new Buyer(buyerId, name, campaignIds, states, dailyCap, newPriority, revenuePerPayableCall, status);
    }

    public Buyer withDailyCap(int newCap) {
        return new Buyer(buyerId, name, campaignIds, states, newCap, priority, revenuePerPayableCall, status);
    }

    public Buyer withStatus(BuyerStatus newStatus) {
        return new Buyer(buyerId, name, campaignIds, states, dailyCap, priority, revenuePerPayableCall, newStatus);
    }
}
