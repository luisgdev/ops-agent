package digital.rya.opsagent.metrics;

import java.util.List;
import java.util.Map;

/**
 * Result types returned by {@link MetricsService}. They are plain records so they serialize
 * straight to JSON for the model and for the REST API. Money is rounded to cents, rates to
 * 4 decimals (0.1234 = 12.34%).
 */
public final class Reports {

    private Reports() {
    }

    public record DaySummary(
            String day,
            int calls,
            Map<String, Long> callsByStatus,
            int routed,
            int payable,
            double payableRateOfConnected,
            double revenue,
            double payout,
            double margin,
            double marginPerCall) {
    }

    public record BuyerPerformance(
            String buyerId,
            String name,
            String status,
            int priority,
            int dailyCap,
            int routed,
            int payable,
            int connectedShort,
            int noAnswer,
            int buyerHangup,
            double buyerFailureRate,
            double payableRateOfConnected,
            double revenue,
            double payout,
            double margin,
            double capUsedPct,
            String capReachedAt,
            Integer minutesLeftInDayWhenCapped,
            double avgAgentsReported,
            int minAgentsReported,
            List<String> flags) {
    }

    public record BuyerHour(int hour, int routed, int payable, int noAnswer, int buyerHangup, double avgAgentsReported) {
    }

    public record PublisherQuality(
            String publisherId,
            String name,
            String status,
            boolean flaggedForReview,
            int calls,
            int routed,
            int payable,
            int connectedShort,
            int veryShortUnder30s,
            int duplicates,
            int repeatCallersSameDay,
            int notRouted,
            int buyerFailures,
            double payableRateOfConnected,
            int medianDurationSec,
            double revenue,
            double payout,
            double margin,
            double marginPerCall,
            List<String> qualityFlags) {
    }

    public record UnroutedGroup(
            String reason,
            String campaignId,
            String callerState,
            int calls,
            String firstAt,
            String lastAt,
            List<String> publishers,
            List<String> buyersCoveringThisStateAndCampaign,
            String explanation) {
    }

    public record UnroutedReport(String day, int totalUnrouted, Map<String, Integer> byReason, List<UnroutedGroup> groups) {
    }

    public record EntityDelta(
            String id,
            String name,
            int callsBaseline,
            int callsTarget,
            int payableBaseline,
            int payableTarget,
            double revenueBaseline,
            double revenueTarget,
            double marginBaseline,
            double marginTarget,
            double marginChange) {
    }

    public record DayComparison(
            DaySummary baseline,
            DaySummary target,
            int callsChange,
            double revenueChange,
            double payoutChange,
            double marginChange,
            Map<String, Long> statusChange,
            List<EntityDelta> byBuyer,
            List<EntityDelta> byPublisher,
            List<EntityDelta> byCampaign) {
    }

    /**
     * One reason the target day earned less than it should have.
     *
     * @param kind DECLINE_DRIVER = something got worse vs the baseline day;
     *             MISSED_UPSIDE = extra calls we could not sell (we did not lose existing money,
     *             but the volume increase did not turn into revenue)
     */
    public record Cause(
            String type,
            String kind,
            String entityId,
            String entityName,
            String summary,
            Map<String, Object> evidence,
            double estimatedLostPayableCalls,
            double estimatedLostRevenue,
            double estimatedLostMargin,
            String method) {
    }

    public record MarginExplanation(
            String baselineDay,
            String targetDay,
            int callsChange,
            double revenueChange,
            double marginChange,
            double baselineMargin,
            double targetMargin,
            List<Cause> causes,
            double totalEstimatedLostRevenue,
            double totalEstimatedLostMargin,
            double counterfactualTargetMargin,
            double marginChangeNotExplainedByCauses,
            List<String> notes) {
    }

    public record CoverageLine(
            String campaignId,
            String state,
            boolean soleCoverage,
            List<String> otherActiveBuyers,
            int callsRoutedToThisBuyerOnDay) {
    }

    public record CoverageReport(
            String buyerId,
            String name,
            String day,
            List<CoverageLine> lines,
            List<String> soleCoverageStates,
            int callsOnDayThatWouldHaveNoBuyerIfPaused,
            String guidance) {
    }
}
