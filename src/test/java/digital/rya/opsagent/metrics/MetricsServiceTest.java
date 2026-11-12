package digital.rya.opsagent.metrics;

import digital.rya.opsagent.TestData;
import digital.rya.opsagent.metrics.Reports.BuyerPerformance;
import digital.rya.opsagent.metrics.Reports.Cause;
import digital.rya.opsagent.metrics.Reports.CoverageReport;
import digital.rya.opsagent.metrics.Reports.MarginExplanation;
import digital.rya.opsagent.metrics.Reports.PublisherQuality;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The numbers the agent quotes come from here, so they are pinned to values cross-checked
 * independently (a separate pandas script over the same CSV).
 */
class MetricsServiceTest {

    private final MetricsService m = new MetricsService(TestData.store());

    @Test
    void daySummariesMatchTheRawData() {
        Reports.DaySummary nov2 = m.daySummary(TestData.NOV2);
        Reports.DaySummary nov4 = m.daySummary(TestData.NOV4);
        assertEquals(500, nov2.calls());
        assertEquals(12958.0, nov2.revenue(), 0.001);
        assertEquals(4722.0, nov2.margin(), 0.001);
        assertEquals(620, nov4.calls());
        assertEquals(11743.0, nov4.revenue(), 0.001);
        assertEquals(4221.0, nov4.margin(), 0.001);
    }

    @Test
    void compareDaysShowsVolumeUpAndMarginDown() {
        Reports.DayComparison c = m.compareDays(TestData.NOV2, TestData.NOV4);
        assertEquals(120, c.callsChange());
        assertEquals(-1215.0, c.revenueChange(), 0.001);
        assertEquals(-501.0, c.marginChange(), 0.001);
        assertEquals(20L, (long) c.statusChange().get("DUPLICATE")); // 3 -> 23
    }

    @Test
    void buyerSouthReportsAgentsButDoesNotAnswer() {
        BuyerPerformance south = m.buyerPerformance(TestData.NOV4, "BUY-202");
        assertEquals(177, south.routed());
        assertEquals(47, south.noAnswer());
        assertEquals(29, south.buyerHangup());
        assertEquals(0.4294, south.buyerFailureRate(), 0.0001);
        assertTrue(south.flags().contains("REPORTS_FREE_AGENTS_BUT_NOT_ANSWERING"));
        assertTrue(south.avgAgentsReported() > 5);

        BuyerPerformance southNormal = m.buyerPerformance(TestData.NOV2, "BUY-202");
        assertFalse(southNormal.flags().contains("HIGH_FAILURE_RATE"));
    }

    @Test
    void buyerEastHitsItsCapEarlier() {
        assertEquals("13:53", m.buyerPerformance(TestData.NOV4, "BUY-203").capReachedAt());
        assertEquals(75, m.buyerPerformance(TestData.NOV4, "BUY-203").routed());
    }

    @Test
    void socialAffiliateDIsFlaggedAsJunkOnlyOnNov4() {
        PublisherQuality d4 = m.publisherQuality(TestData.NOV4, "PUB-104");
        assertEquals(140, d4.calls());
        assertEquals(22, d4.duplicates());
        assertEquals(52, d4.veryShortUnder30s());
        assertTrue(d4.qualityFlags().contains("HIGH_DUPLICATES"));
        assertTrue(m.publisherQuality(TestData.NOV2, "PUB-104").qualityFlags().isEmpty());
        // Search Partner A lost payable calls on Nov 4, but because of a buyer, not its own quality.
        assertTrue(m.publisherQuality(TestData.NOV4, "PUB-101").qualityFlags().isEmpty());
    }

    @Test
    void coverageShowsBuyerSouthIsTheOnlyBuyerForSomeStates() {
        CoverageReport cov = m.buyerCoverage("BUY-202", TestData.NOV4);
        assertTrue(cov.soleCoverageStates().contains("TN on CMP-MA-SEARCH"));
        assertTrue(cov.soleCoverageStates().contains("NC on CMP-MA-CTV"));
        assertEquals(92, cov.callsOnDayThatWouldHaveNoBuyerIfPaused());
    }

    @Test
    void unroutedCallsAreGroupedByReason() {
        Reports.UnroutedReport u = m.unroutedCalls(TestData.NOV4);
        assertEquals(39, u.totalUnrouted());
        assertEquals(22, (int) u.byReason().get("NOT_ROUTED_CAP"));
        assertEquals(17, (int) u.byReason().get("NOT_ROUTED_NO_BUYER"));
    }

    @Test
    void explainsTheNov4MarginDropWithFourCauses() {
        MarginExplanation e = m.explainMarginChange(TestData.NOV2, TestData.NOV4);
        assertEquals(-501.0, e.marginChange(), 0.001);
        List<Cause> causes = e.causes();
        assertEquals(4, causes.size());

        Cause south = cause(causes, "BUYER_NOT_ANSWERING", "BUY-202");
        assertEquals("DECLINE_DRIVER", south.kind());
        assertEquals(1699.76, south.estimatedLostRevenue(), 0.01);
        assertEquals(630.80, south.estimatedLostMargin(), 0.01);

        Cause junk = cause(causes, "PUBLISHER_TRAFFIC_QUALITY", "PUB-104");
        assertEquals(744.43, junk.estimatedLostRevenue(), 0.01);
        assertEquals(276.50, junk.estimatedLostMargin(), 0.01);

        Cause cap = cause(causes, "BUYER_CAP_EXHAUSTED", "BUY-203");
        assertEquals("MISSED_UPSIDE", cap.kind());
        assertEquals(355.01, cap.estimatedLostRevenue(), 0.01);
        assertEquals(119.33, cap.estimatedLostMargin(), 0.01);
        assertEquals(90, cap.evidence().get("capNeededToTakeAllRejectedCallsOnTarget"), "75 cap + 15 rejected calls");
        assertTrue(cap.evidence().get("alternativesToRaisingTheCap").toString().contains("BUY-204"),
                "Buyer Gulf covers FL but does not buy the CTV campaign");

        Cause az = cause(causes, "NO_BUYER_FOR_STATE", "AZ");
        assertEquals(102.82, az.estimatedLostMargin(), 0.01);

        assertEquals(1129.45, e.totalEstimatedLostMargin(), 0.01);
        // Reconciliation: actual change + what the causes cost = what growth would have added.
        assertEquals(e.marginChange() + e.totalEstimatedLostMargin(), e.marginChangeNotExplainedByCauses(), 0.01);
        assertEquals(e.targetMargin() + e.totalEstimatedLostMargin(), e.counterfactualTargetMargin(), 0.01);
    }

    @Test
    void noCausesWhenADayIsComparedWithItself() {
        MarginExplanation e = m.explainMarginChange(TestData.NOV2, TestData.NOV2);
        assertTrue(e.causes().isEmpty());
        assertEquals(0.0, e.totalEstimatedLostMargin(), 0.001);
    }

    @Test
    void unknownDayIsAClearError() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> m.daySummary(LocalDate.parse("2026-12-25")));
        assertTrue(ex.getMessage().contains("Available days"));
    }

    private static Cause cause(List<Cause> causes, String type, String entity) {
        return causes.stream().filter(c -> c.type().equals(type) && c.entityId().equals(entity)).findFirst()
                .orElseThrow(() -> new AssertionError("missing cause " + type + " " + entity + " in " + causes));
    }
}
