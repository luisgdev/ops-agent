package digital.rya.opsagent.metrics;

import digital.rya.opsagent.metrics.Reports.BuyerHour;
import digital.rya.opsagent.metrics.Reports.BuyerPerformance;
import digital.rya.opsagent.metrics.Reports.Cause;
import digital.rya.opsagent.metrics.Reports.CoverageLine;
import digital.rya.opsagent.metrics.Reports.CoverageReport;
import digital.rya.opsagent.metrics.Reports.DayComparison;
import digital.rya.opsagent.metrics.Reports.DaySummary;
import digital.rya.opsagent.metrics.Reports.EntityDelta;
import digital.rya.opsagent.metrics.Reports.MarginExplanation;
import digital.rya.opsagent.metrics.Reports.PublisherQuality;
import digital.rya.opsagent.metrics.Reports.UnroutedGroup;
import digital.rya.opsagent.metrics.Reports.UnroutedReport;
import digital.rya.opsagent.platform.AvailabilityPing;
import digital.rya.opsagent.platform.Buyer;
import digital.rya.opsagent.platform.Call;
import digital.rya.opsagent.platform.CallStatus;
import digital.rya.opsagent.platform.Campaign;
import digital.rya.opsagent.platform.PlatformStore;
import digital.rya.opsagent.platform.Publisher;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Every number the agent sees is computed here, in plain Java, so it is deterministic and
 * testable. The model only reads these results; it never does the arithmetic itself.
 */
public class MetricsService {

    // ---- thresholds (kept in one place so they are easy to discuss and tune) ----------

    /** A buyer is "failing" when this share of routed calls is NO_ANSWER or BUYER_HANGUP. */
    static final double HIGH_FAILURE_RATE = 0.25;
    /** ... and it still reports at least this many free agents on average. */
    static final double AGENTS_REPORTED_FOR_PING_MISMATCH = 2.0;
    /** A buyer becomes a cause when its failure rate rose by at least this much vs baseline. */
    static final double FAILURE_RATE_INCREASE_FOR_CAUSE = 0.10;
    static final double MIN_EXCESS_FAILED_CALLS_FOR_CAUSE = 5;

    static final double HIGH_DUPLICATE_SHARE = 0.05;
    static final double HIGH_VERY_SHORT_SHARE = 0.20;
    static final double LOW_PAYABLE_RATE = 0.50;
    static final int VERY_SHORT_SEC = 30;

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final PlatformStore store;

    public MetricsService(PlatformStore store) {
        this.store = store;
    }

    public PlatformStore store() {
        return store;
    }

    // =====================================================================================
    // Day summary and comparison
    // =====================================================================================

    public DaySummary daySummary(LocalDate day) {
        List<Call> calls = requireCalls(day);
        Map<String, Long> byStatus = countByStatus(calls);
        int connected = count(calls, c -> c.status().isConnected());
        int payable = count(calls, c -> c.status() == CallStatus.CONNECTED_PAYABLE);
        double revenue = sum(calls, Call::revenue);
        double payout = sum(calls, Call::payout);
        return new DaySummary(day.toString(), calls.size(), byStatus, count(calls, Call::isRouted), payable,
                rate(payable, connected), money(revenue), money(payout), money(revenue - payout),
                money((revenue - payout) / calls.size()));
    }

    public DayComparison compareDays(LocalDate baselineDay, LocalDate targetDay) {
        DaySummary b = daySummary(baselineDay);
        DaySummary t = daySummary(targetDay);
        Map<String, Long> statusChange = new LinkedHashMap<>();
        for (CallStatus s : CallStatus.values()) {
            statusChange.put(s.name(), t.callsByStatus().getOrDefault(s.name(), 0L) - b.callsByStatus().getOrDefault(s.name(), 0L));
        }
        List<Call> bc = store.callsOn(baselineDay);
        List<Call> tc = store.callsOn(targetDay);

        List<EntityDelta> byBuyer = new ArrayList<>();
        for (Buyer buyer : store.buyers()) {
            byBuyer.add(delta(buyer.buyerId(), buyer.name(), bc, tc, c -> buyer.buyerId().equals(c.buyerId())));
        }
        List<EntityDelta> byPublisher = new ArrayList<>();
        for (Publisher p : store.publishers()) {
            byPublisher.add(delta(p.publisherId(), p.name(), bc, tc, c -> p.publisherId().equals(c.publisherId())));
        }
        List<EntityDelta> byCampaign = new ArrayList<>();
        for (Campaign cmp : store.campaigns()) {
            byCampaign.add(delta(cmp.campaignId(), cmp.name(), bc, tc, c -> cmp.campaignId().equals(c.campaignId())));
        }
        byBuyer.sort(Comparator.comparingDouble(EntityDelta::marginChange));
        byPublisher.sort(Comparator.comparingDouble(EntityDelta::marginChange));
        byCampaign.sort(Comparator.comparingDouble(EntityDelta::marginChange));

        return new DayComparison(b, t, t.calls() - b.calls(), money(t.revenue() - b.revenue()),
                money(t.payout() - b.payout()), money(t.margin() - b.margin()), statusChange, byBuyer, byPublisher, byCampaign);
    }

    private EntityDelta delta(String id, String name, List<Call> baseline, List<Call> target, Predicate<Call> filter) {
        List<Call> b = baseline.stream().filter(filter).toList();
        List<Call> t = target.stream().filter(filter).toList();
        double mb = sum(b, Call::margin);
        double mt = sum(t, Call::margin);
        return new EntityDelta(id, name, b.size(), t.size(),
                count(b, c -> c.status() == CallStatus.CONNECTED_PAYABLE), count(t, c -> c.status() == CallStatus.CONNECTED_PAYABLE),
                money(sum(b, Call::revenue)), money(sum(t, Call::revenue)), money(mb), money(mt), money(mt - mb));
    }

    // =====================================================================================
    // Buyers
    // =====================================================================================

    public List<BuyerPerformance> buyerPerformance(LocalDate day) {
        requireCalls(day);
        List<BuyerPerformance> out = new ArrayList<>();
        for (Buyer b : store.buyers()) {
            out.add(buyerPerformance(day, b.buyerId()));
        }
        return out;
    }

    public BuyerPerformance buyerPerformance(LocalDate day, String buyerId) {
        Buyer buyer = store.buyer(buyerId);
        List<Call> all = requireCalls(day);
        List<Call> calls = all.stream().filter(c -> buyerId.equals(c.buyerId())).toList();
        int routed = calls.size();
        int payable = count(calls, c -> c.status() == CallStatus.CONNECTED_PAYABLE);
        int shortC = count(calls, c -> c.status() == CallStatus.CONNECTED_SHORT);
        int noAnswer = count(calls, c -> c.status() == CallStatus.NO_ANSWER);
        int hangup = count(calls, c -> c.status() == CallStatus.BUYER_HANGUP);
        double failureRate = rate(noAnswer + hangup, routed);

        // When did the buyer's routed count reach its cap? (routed calls count against the cap)
        String capReachedAt = null;
        Integer minutesLeft = null;
        if (routed >= buyer.dailyCap() && buyer.dailyCap() > 0) {
            OffsetDateTime reached = calls.get(buyer.dailyCap() - 1).startedAt();
            OffsetDateTime lastCallOfDay = all.get(all.size() - 1).startedAt();
            capReachedAt = reached.format(HHMM);
            minutesLeft = (int) Duration.between(reached, lastCallOfDay).toMinutes();
        }

        List<AvailabilityPing> pings = pingsOn(day, buyerId);
        double avgAgents = pings.stream().mapToInt(AvailabilityPing::agentsAvailable).average().orElse(0);
        int minAgents = pings.stream().mapToInt(AvailabilityPing::agentsAvailable).min().orElse(0);

        List<String> flags = new ArrayList<>();
        if (failureRate >= HIGH_FAILURE_RATE) {
            flags.add("HIGH_FAILURE_RATE");
            if (avgAgents >= AGENTS_REPORTED_FOR_PING_MISMATCH) {
                flags.add("REPORTS_FREE_AGENTS_BUT_NOT_ANSWERING");
            }
        }
        if (capReachedAt != null) {
            flags.add("CAP_REACHED");
        }
        if (!buyer.isActive()) {
            flags.add("PAUSED");
        }

        return new BuyerPerformance(buyer.buyerId(), buyer.name(), buyer.status().name(), buyer.priority(), buyer.dailyCap(),
                routed, payable, shortC, noAnswer, hangup, failureRate, rate(payable, payable + shortC),
                money(sum(calls, Call::revenue)), money(sum(calls, Call::payout)), money(sum(calls, Call::margin)),
                rate(routed, buyer.dailyCap()), capReachedAt, minutesLeft, round(avgAgents, 2), minAgents, flags);
    }

    /** Hour-by-hour view of one buyer: calls sent vs answered vs agents it claimed to have. */
    public List<BuyerHour> buyerHourly(LocalDate day, String buyerId) {
        store.buyer(buyerId);
        List<Call> calls = requireCalls(day).stream().filter(c -> buyerId.equals(c.buyerId())).toList();
        List<AvailabilityPing> pings = pingsOn(day, buyerId);
        TreeSet<Integer> hours = new TreeSet<>();
        calls.forEach(c -> hours.add(c.startedAt().getHour()));
        pings.forEach(p -> hours.add(p.timestamp().getHour()));
        List<BuyerHour> out = new ArrayList<>();
        for (int h : hours) {
            List<Call> hc = calls.stream().filter(c -> c.startedAt().getHour() == h).toList();
            double agents = pings.stream().filter(p -> p.timestamp().getHour() == h)
                    .mapToInt(AvailabilityPing::agentsAvailable).average().orElse(0);
            out.add(new BuyerHour(h, hc.size(), count(hc, c -> c.status() == CallStatus.CONNECTED_PAYABLE),
                    count(hc, c -> c.status() == CallStatus.NO_ANSWER), count(hc, c -> c.status() == CallStatus.BUYER_HANGUP),
                    round(agents, 2)));
        }
        return out;
    }

    /**
     * What happens to traffic if this buyer is paused? Lists, per campaign and state it covers,
     * which other active buyers could take the call. "Sole coverage" means pausing it turns
     * those calls into NOT_ROUTED_NO_BUYER.
     */
    public CoverageReport buyerCoverage(String buyerId, LocalDate day) {
        Buyer buyer = store.buyer(buyerId);
        List<Call> dayCalls = requireCalls(day);
        Set<String> states = new TreeSet<>();
        if (buyer.states().contains("*")) {
            dayCalls.stream().filter(c -> buyerId.equals(c.buyerId())).forEach(c -> states.add(c.callerState()));
        } else {
            states.addAll(buyer.states());
        }
        List<CoverageLine> lines = new ArrayList<>();
        Set<String> soleStates = new TreeSet<>();
        int atRisk = 0;
        for (String campaignId : buyer.campaignIds()) {
            for (String state : states) {
                List<String> others = store.buyers().stream()
                        .filter(o -> !o.buyerId().equals(buyerId) && o.isActive() && o.buysCampaign(campaignId) && o.coversState(state))
                        .map(o -> o.buyerId() + " (priority " + o.priority() + ", cap " + o.dailyCap() + ")")
                        .toList();
                int routedHere = count(dayCalls, c -> buyerId.equals(c.buyerId()) && campaignId.equals(c.campaignId())
                        && state.equals(c.callerState()));
                boolean sole = others.isEmpty();
                if (sole) {
                    soleStates.add(state + " on " + campaignId);
                    atRisk += routedHere;
                }
                lines.add(new CoverageLine(campaignId, state, sole, others, routedHere));
            }
        }
        String guidance = soleStates.isEmpty()
                ? "Every state/campaign this buyer covers has a backup buyer. Pausing it shifts traffic, it does not strand it."
                : "Pausing this buyer would leave " + soleStates.size() + " state/campaign combinations with no buyer ("
                + atRisk + " calls on " + day + " would have been unroutable). Prefer lowering its priority, which keeps it as the fallback.";
        return new CoverageReport(buyer.buyerId(), buyer.name(), day.toString(), lines, new ArrayList<>(soleStates), atRisk, guidance);
    }

    // =====================================================================================
    // Publishers
    // =====================================================================================

    public List<PublisherQuality> publisherQuality(LocalDate day) {
        requireCalls(day);
        List<PublisherQuality> out = new ArrayList<>();
        for (Publisher p : store.publishers()) {
            out.add(publisherQuality(day, p.publisherId()));
        }
        return out;
    }

    public PublisherQuality publisherQuality(LocalDate day, String publisherId) {
        Publisher pub = store.publisher(publisherId);
        List<Call> calls = requireCalls(day).stream().filter(c -> publisherId.equals(c.publisherId())).toList();
        int routed = count(calls, Call::isRouted);
        int payable = count(calls, c -> c.status() == CallStatus.CONNECTED_PAYABLE);
        int shortC = count(calls, c -> c.status() == CallStatus.CONNECTED_SHORT);
        int veryShort = count(calls, c -> c.status() == CallStatus.CONNECTED_SHORT && c.durationSec() < VERY_SHORT_SEC);
        int dups = count(calls, c -> c.status() == CallStatus.DUPLICATE);

        // Same caller calling again later the same day (outside the 1-hour duplicate window).
        Set<String> seen = new HashSet<>();
        int repeat = 0;
        for (Call c : calls) {
            if (c.status() == CallStatus.DUPLICATE) {
                continue;
            }
            if (!seen.add(c.callerHash())) {
                repeat++;
            }
        }

        List<Integer> durations = calls.stream().map(Call::durationSec).sorted().toList();
        int median = durations.isEmpty() ? 0 : durations.get(durations.size() / 2);
        double payableRate = rate(payable, payable + shortC);
        double margin = sum(calls, Call::margin);

        List<String> flags = new ArrayList<>();
        if (rate(dups, calls.size()) >= HIGH_DUPLICATE_SHARE) {
            flags.add("HIGH_DUPLICATES");
        }
        if (rate(veryShort, routed) >= HIGH_VERY_SHORT_SHARE) {
            flags.add("MANY_CALLS_UNDER_30S");
        }
        if (payable + shortC > 0 && payableRate < LOW_PAYABLE_RATE) {
            flags.add("LOW_PAYABLE_RATE");
        }

        return new PublisherQuality(pub.publisherId(), pub.name(), pub.status().name(), pub.flaggedForReview(), calls.size(),
                routed, payable, shortC, veryShort, dups, repeat, count(calls, c -> c.status().isNotRouted()),
                count(calls, c -> c.status().isBuyerFailure()), payableRate, median, money(sum(calls, Call::revenue)),
                money(sum(calls, Call::payout)), money(margin), money(calls.isEmpty() ? 0 : margin / calls.size()), flags);
    }

    // =====================================================================================
    // Unrouted calls
    // =====================================================================================

    public UnroutedReport unroutedCalls(LocalDate day) {
        List<Call> unrouted = requireCalls(day).stream().filter(c -> c.status().isNotRouted()).toList();
        Map<String, List<Call>> groups = new TreeMap<>();
        for (Call c : unrouted) {
            groups.computeIfAbsent(c.status() + "|" + c.campaignId() + "|" + c.callerState(), k -> new ArrayList<>()).add(c);
        }
        List<UnroutedGroup> out = new ArrayList<>();
        for (List<Call> g : groups.values()) {
            Call first = g.get(0);
            List<String> covering = store.buyers().stream()
                    .filter(b -> b.buysCampaign(first.campaignId()) && b.coversState(first.callerState()))
                    .map(Buyer::buyerId).toList();
            String explanation = first.status() == CallStatus.NOT_ROUTED_NO_BUYER
                    ? "No buyer on this campaign is licensed in " + first.callerState() + "."
                    : "All buyers covering " + first.callerState() + " on this campaign (" + String.join(", ", covering)
                    + ") had hit their daily cap.";
            out.add(new UnroutedGroup(first.status().name(), first.campaignId(), first.callerState(), g.size(),
                    first.startedAt().format(HHMM), g.get(g.size() - 1).startedAt().format(HHMM),
                    g.stream().map(Call::publisherId).distinct().sorted().toList(), covering, explanation));
        }
        out.sort(Comparator.comparingInt(UnroutedGroup::calls).reversed());
        Map<String, Integer> byReason = new TreeMap<>();
        unrouted.forEach(c -> byReason.merge(c.status().name(), 1, Integer::sum));
        return new UnroutedReport(day.toString(), unrouted.size(), byReason, out);
    }

    // =====================================================================================
    // Why did margin change? (the main analysis)
    // =====================================================================================

    /**
     * Breaks the margin change between two days into causes and puts a dollar figure on each.
     * Each cause is a counterfactual: "if this one thing had behaved like on the baseline day,
     * how many more payable calls would we have had?" Each lost payable call is valued at the
     * baseline revenue and margin per payable call of the buyer / publisher / campaign involved.
     *
     * <p>The causes are built so they do not double count: buyer failures are counted on the
     * buyer, publisher losses only from quality (not from buyer failures), and calls a junk
     * publisher lost to a cap are already inside that publisher's shortfall.
     */
    public MarginExplanation explainMarginChange(LocalDate baselineDay, LocalDate targetDay) {
        List<Call> bc = requireCalls(baselineDay);
        List<Call> tc = requireCalls(targetDay);
        List<Cause> causes = new ArrayList<>();

        // 1) Buyers that stopped taking calls ---------------------------------------------
        for (Buyer buyer : store.buyers()) {
            List<Call> b = byBuyer(bc, buyer.buyerId());
            List<Call> t = byBuyer(tc, buyer.buyerId());
            if (b.isEmpty() || t.isEmpty()) {
                continue;
            }
            int failedB = count(b, c -> c.status().isBuyerFailure());
            int failedT = count(t, c -> c.status().isBuyerFailure());
            double rateB = (double) failedB / b.size();
            double rateT = (double) failedT / t.size();
            double excessFailed = failedT - rateB * t.size();
            if (rateT - rateB < FAILURE_RATE_INCREASE_FOR_CAUSE || excessFailed < MIN_EXCESS_FAILED_CALLS_FOR_CAUSE) {
                continue;
            }
            List<Call> payableB = b.stream().filter(c -> c.status() == CallStatus.CONNECTED_PAYABLE).toList();
            int connectedB = count(b, c -> c.status().isConnected());
            double payableRateB = (double) payableB.size() / connectedB;
            double lostPayable = excessFailed * payableRateB;
            double revPerPayable = avg(payableB, Call::revenue);
            double marginPerPayable = avg(payableB, Call::margin);
            double avgAgents = pingsOn(targetDay, buyer.buyerId()).stream().mapToInt(AvailabilityPing::agentsAvailable).average().orElse(0);

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("routedBaseline", b.size());
            ev.put("routedTarget", t.size());
            ev.put("noAnswerPlusHangupBaseline", failedB);
            ev.put("noAnswerPlusHangupTarget", failedT);
            ev.put("failureRateBaseline", round(rateB, 4));
            ev.put("failureRateTarget", round(rateT, 4));
            ev.put("excessFailedCalls", round(excessFailed, 1));
            ev.put("baselinePayableRateWhenAnswered", round(payableRateB, 4));
            ev.put("avgAgentsReportedInPingsTarget", round(avgAgents, 2));
            ev.put("currentPriority", buyer.priority());
            causes.add(new Cause("BUYER_NOT_ANSWERING", "DECLINE_DRIVER", buyer.buyerId(), buyer.name(),
                    buyer.name() + " failed " + pct(rateT) + " of the calls routed to it (vs " + pct(rateB)
                            + " on " + baselineDay + ") while its pings still reported ~" + round(avgAgents, 1) + " free agents.",
                    ev, round(lostPayable, 1), money(lostPayable * revPerPayable), money(lostPayable * marginPerPayable),
                    "excess failed calls = failed(target) - failureRate(baseline) x routed(target); "
                            + "lost payable = excess x baseline payable rate when answered; valued at baseline revenue/margin per payable call."));
        }

        // 2) Publishers whose traffic quality collapsed ----------------------------------
        Set<String> junkPublishers = new HashSet<>();
        Map<String, Cause> publisherCauses = new LinkedHashMap<>();
        for (Publisher pub : store.publishers()) {
            PublisherQuality qT = publisherQuality(targetDay, pub.publisherId());
            if (qT.qualityFlags().isEmpty()) {
                continue;
            }
            junkPublishers.add(pub.publisherId());
            PublisherQuality qB = publisherQuality(baselineDay, pub.publisherId());
            List<Call> payableB = bc.stream().filter(c -> pub.publisherId().equals(c.publisherId())
                    && c.status() == CallStatus.CONNECTED_PAYABLE).toList();
            int connectedT = qT.payable() + qT.connectedShort();
            // Conservative: the smaller of "fewer payable calls than baseline" and
            // "connected calls x drop in payable rate". Junk calls on top of normal traffic
            // are not counted as if they were good calls we lost.
            double lostPayable = Math.max(0, Math.min(qB.payable() - qT.payable(),
                    connectedT * (qB.payableRateOfConnected() - qT.payableRateOfConnected())));
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("callsBaseline", qB.calls());
            ev.put("callsTarget", qT.calls());
            ev.put("payableBaseline", qB.payable());
            ev.put("payableTarget", qT.payable());
            ev.put("payableRateBaseline", qB.payableRateOfConnected());
            ev.put("payableRateTarget", qT.payableRateOfConnected());
            ev.put("duplicatesTarget", qT.duplicates());
            ev.put("callsUnder30sTarget", qT.veryShortUnder30s());
            ev.put("repeatCallersSameDayTarget", qT.repeatCallersSameDay());
            ev.put("medianDurationSecBaseline", qB.medianDurationSec());
            ev.put("medianDurationSecTarget", qT.medianDurationSec());
            ev.put("qualityFlags", qT.qualityFlags());
            publisherCauses.put(pub.publisherId(), new Cause("PUBLISHER_TRAFFIC_QUALITY", "DECLINE_DRIVER", pub.publisherId(), pub.name(),
                    pub.name() + " sent " + qT.calls() + " calls (vs " + qB.calls() + ") but only " + qT.payable()
                            + " were payable (vs " + qB.payable() + "): " + qT.duplicates() + " duplicates, "
                            + qT.veryShortUnder30s() + " calls under 30s, median duration " + qT.medianDurationSec() + "s.",
                    ev, round(lostPayable, 1), money(lostPayable * avg(payableB, Call::revenue)),
                    money(lostPayable * avg(payableB, Call::margin)),
                    "lost payable = min(payable(baseline) - payable(target), connected(target) x drop in payable rate); "
                            + "valued at the publisher's baseline revenue/margin per payable call."));
        }

        // 3) Calls rejected because every eligible buyer was capped ----------------------
        //    and 4) calls with no buyer licensed in the caller's state.
        Map<String, double[]> capByBuyers = new TreeMap<>();      // key: capped buyer ids -> [calls, payable, rev, margin]
        Map<String, Map<String, Integer>> capDetail = new TreeMap<>();
        Map<String, Integer> capRejectedOnTarget = new TreeMap<>();
        Map<String, Set<String>> capAlternatives = new TreeMap<>();
        Map<String, double[]> noCoverageByState = new TreeMap<>();
        Map<String, Map<String, Integer>> noCoverageDetail = new TreeMap<>();

        for (Campaign cmp : store.campaigns()) {
            List<Call> b = bc.stream().filter(c -> cmp.campaignId().equals(c.campaignId())).toList();
            List<Call> t = tc.stream().filter(c -> cmp.campaignId().equals(c.campaignId())).toList();
            List<Call> payableB = b.stream().filter(c -> c.status() == CallStatus.CONNECTED_PAYABLE).toList();
            double payablePerRouted = rawRate(payableB.size(), count(b, Call::isRouted));
            double revPerPayable = avg(payableB, Call::revenue);
            double marginPerPayable = avg(payableB, Call::margin);

            // Cap rejections from clean publishers (junk publishers' own rejected calls are
            // already part of their shortfall above).
            Predicate<Call> cleanCapReject = c -> c.status() == CallStatus.NOT_ROUTED_CAP && !junkPublishers.contains(c.publisherId());
            int excessCap = Math.max(0, count(t, cleanCapReject) - count(b, cleanCapReject));
            if (excessCap > 0) {
                // How many junk calls did junk publishers push into the capped buyers today?
                List<Call> rejected = t.stream().filter(cleanCapReject).toList();
                Set<String> cappedBuyers = new TreeSet<>();
                for (Call r : rejected) {
                    store.buyers().stream().filter(x -> x.buysCampaign(r.campaignId()) && x.coversState(r.callerState()))
                            .forEach(x -> cappedBuyers.add(x.buyerId()));
                }
                int junkIntoCappedBuyers = count(t, c -> junkPublishers.contains(c.publisherId()) && c.isRouted()
                        && cappedBuyers.contains(c.buyerId()) && c.status() == CallStatus.CONNECTED_SHORT
                        && c.durationSec() < VERY_SHORT_SEC);
                int crowdOut = Math.min(excessCap, junkIntoCappedBuyers);
                int realCap = excessCap - crowdOut;
                if (crowdOut > 0) {
                    // Attribute to the junk publisher(s) on this campaign (the one with most junk).
                    String culprit = junkPublishers.stream()
                            .filter(p -> t.stream().anyMatch(c -> p.equals(c.publisherId())))
                            .max(Comparator.comparingInt(p -> count(t, c -> p.equals(c.publisherId())
                                    && c.status() == CallStatus.CONNECTED_SHORT && c.durationSec() < VERY_SHORT_SEC)))
                            .orElseThrow();
                    Cause base = publisherCauses.get(culprit);
                    double lp = crowdOut * payablePerRouted;
                    Map<String, Object> ev = new LinkedHashMap<>(base.evidence());
                    ev.put("cleanCallsLostToCapFilledByJunk", crowdOut);
                    ev.put("cappedBuyers", new ArrayList<>(cappedBuyers));
                    ev.put("junkCallsRoutedToCappedBuyers", junkIntoCappedBuyers);
                    publisherCauses.put(culprit, new Cause(base.type(), base.kind(), base.entityId(), base.entityName(),
                            base.summary() + " Its junk calls also used up the cap of " + String.join(", ", cappedBuyers)
                                    + ", so " + crowdOut + " good calls from other publishers were rejected.",
                            ev, round(base.estimatedLostPayableCalls() + lp, 1),
                            money(base.estimatedLostRevenue() + lp * revPerPayable),
                            money(base.estimatedLostMargin() + lp * marginPerPayable),
                            base.method() + " Plus clean calls rejected at cap after junk filled it, valued at the campaign's baseline payable rate per routed call."));
                }
                if (realCap > 0) {
                    String key = String.join(",", cappedBuyers);
                    double lp = realCap * payablePerRouted;
                    add(capByBuyers, key, realCap, lp, lp * revPerPayable, lp * marginPerPayable);
                    capDetail.computeIfAbsent(key, k -> new TreeMap<>()).merge(cmp.campaignId(), realCap, Integer::sum);
                    capRejectedOnTarget.merge(key, rejected.size(), Integer::sum);
                    // Buyers licensed in the rejected states that do not buy this campaign:
                    // adding the campaign to them is an alternative to raising the cap.
                    for (Call r : rejected) {
                        store.buyers().stream()
                                .filter(x -> x.isActive() && x.coversState(r.callerState()) && !x.buysCampaign(r.campaignId()))
                                .forEach(x -> capAlternatives.computeIfAbsent(key, k -> new TreeSet<>()).add(x.buyerId()
                                        + " (" + x.name() + ") is licensed in " + r.callerState() + " but does not buy " + r.campaignId()));
                    }
                }
            }

            // No coverage, per state.
            Map<String, Integer> nbB = countByState(b, CallStatus.NOT_ROUTED_NO_BUYER);
            Map<String, Integer> nbT = countByState(t, CallStatus.NOT_ROUTED_NO_BUYER);
            for (Map.Entry<String, Integer> e : nbT.entrySet()) {
                int excess = Math.max(0, e.getValue() - nbB.getOrDefault(e.getKey(), 0));
                if (excess > 0) {
                    double lp = excess * payablePerRouted;
                    add(noCoverageByState, e.getKey(), excess, lp, lp * revPerPayable, lp * marginPerPayable);
                    noCoverageDetail.computeIfAbsent(e.getKey(), k -> new TreeMap<>()).merge(cmp.campaignId(), excess, Integer::sum);
                }
            }
        }
        causes.addAll(publisherCauses.values());

        for (Map.Entry<String, double[]> e : capByBuyers.entrySet()) {
            String ids = e.getKey();
            List<String> details = new ArrayList<>();
            for (String id : ids.split(",")) {
                BuyerPerformance p = buyerPerformance(targetDay, id);
                details.add(id + " cap " + p.dailyCap() + " reached at " + p.capReachedAt());
            }
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("extraCallsRejectedAtCapVsBaseline", (int) e.getValue()[0]);
            ev.put("byCampaign", capDetail.get(ids));
            ev.put("cappedBuyers", details);
            int rejectedOnTarget = capRejectedOnTarget.getOrDefault(ids, 0);
            ev.put("callsRejectedAtCapOnTarget", rejectedOnTarget);
            if (!ids.contains(",")) {
                // Single capped buyer: this cap would have absorbed every rejected call that day.
                ev.put("capNeededToTakeAllRejectedCallsOnTarget", store.buyer(ids).dailyCap() + rejectedOnTarget);
            }
            ev.put("alternativesToRaisingTheCap", new ArrayList<>(capAlternatives.getOrDefault(ids, Set.of())));
            causes.add(new Cause("BUYER_CAP_EXHAUSTED", "MISSED_UPSIDE", ids, buyerNames(ids),
                    (int) e.getValue()[0] + " more calls than on " + baselineDay + " were rejected because "
                            + String.join("; ", details) + " and no other buyer on those campaigns covers those states.",
                    ev, round(e.getValue()[1], 1), money(e.getValue()[2]), money(e.getValue()[3]),
                    "extra NOT_ROUTED_CAP calls vs baseline x campaign baseline payable rate per routed call x baseline revenue/margin per payable call."));
        }
        for (Map.Entry<String, double[]> e : noCoverageByState.entrySet()) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("extraCallsWithNoBuyerVsBaseline", (int) e.getValue()[0]);
            ev.put("byCampaign", noCoverageDetail.get(e.getKey()));
            causes.add(new Cause("NO_BUYER_FOR_STATE", "MISSED_UPSIDE", e.getKey(), "Callers in " + e.getKey(),
                    (int) e.getValue()[0] + " more calls from " + e.getKey() + " than on " + baselineDay
                            + " could not be sold: no buyer on those campaigns is licensed there.",
                    ev, round(e.getValue()[1], 1), money(e.getValue()[2]), money(e.getValue()[3]),
                    "extra NOT_ROUTED_NO_BUYER calls vs baseline x campaign baseline payable rate per routed call x baseline revenue/margin per payable call."));
        }

        causes.sort(Comparator.comparingDouble(Cause::estimatedLostMargin).reversed());
        DaySummary b = daySummary(baselineDay);
        DaySummary t = daySummary(targetDay);
        double lostRevenue = causes.stream().mapToDouble(Cause::estimatedLostRevenue).sum();
        double lostMargin = causes.stream().mapToDouble(Cause::estimatedLostMargin).sum();
        double marginChange = t.margin() - b.margin();

        double notExplained = marginChange + lostMargin;
        List<String> notes = new ArrayList<>(List.of(
                "Estimates are counterfactuals against " + baselineDay + " behaviour, not booked losses.",
                "The causes are built NOT to double count: buyer failures are charged to the buyer, publishers only for their own "
                        + "traffic quality, and calls a junk publisher lost at a cap are inside that publisher's figure.",
                "Unrouted and unanswered calls cost $0 in payout (publishers are only paid on payable calls), so the loss is the margin we did not earn.",
                "MISSED_UPSIDE causes did not reduce money we made on " + baselineDay + "; they explain why extra volume did not become revenue."));
        if (!causes.isEmpty() && notExplained > 0) {
            notes.add("The causes total $" + money(lostMargin) + " of lost margin, more than the actual change of $" + money(marginChange)
                    + ", because the rest of the business grew: without these causes margin would have been $" + money(notExplained)
                    + " higher than on " + baselineDay + " (marginChangeNotExplainedByCauses).");
        } else if (!causes.isEmpty()) {
            notes.add("$" + money(-notExplained) + " of the margin decline is not explained by these causes (normal variation or something the tools do not detect).");
        }

        return new MarginExplanation(baselineDay.toString(), targetDay.toString(), t.calls() - b.calls(),
                money(t.revenue() - b.revenue()), money(marginChange), b.margin(), t.margin(), causes,
                money(lostRevenue), money(lostMargin), money(t.margin() + lostMargin), money(notExplained), notes);
    }

    // =====================================================================================
    // helpers
    // =====================================================================================

    private List<Call> requireCalls(LocalDate day) {
        List<Call> calls = store.callsOn(day);
        if (calls.isEmpty()) {
            throw new IllegalArgumentException("No calls on " + day + ". Available days: " + store.days());
        }
        return calls;
    }

    private List<AvailabilityPing> pingsOn(LocalDate day, String buyerId) {
        return store.pings().stream()
                .filter(p -> p.buyerId().equals(buyerId) && p.timestamp().toLocalDate().equals(day)).toList();
    }

    private static List<Call> byBuyer(List<Call> calls, String buyerId) {
        return calls.stream().filter(c -> buyerId.equals(c.buyerId())).toList();
    }

    private String buyerNames(String ids) {
        List<String> names = new ArrayList<>();
        for (String id : ids.split(",")) {
            names.add(store.buyer(id).name());
        }
        return String.join(", ", names);
    }

    private static Map<String, Long> countByStatus(List<Call> calls) {
        Map<CallStatus, Long> m = new EnumMap<>(CallStatus.class);
        calls.forEach(c -> m.merge(c.status(), 1L, Long::sum));
        Map<String, Long> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k.name(), v));
        return out;
    }

    private static Map<String, Integer> countByState(List<Call> calls, CallStatus status) {
        Map<String, Integer> m = new HashMap<>();
        calls.stream().filter(c -> c.status() == status).forEach(c -> m.merge(c.callerState(), 1, Integer::sum));
        return m;
    }

    private static void add(Map<String, double[]> m, String key, double calls, double payable, double rev, double margin) {
        double[] v = m.computeIfAbsent(key, k -> new double[4]);
        v[0] += calls;
        v[1] += payable;
        v[2] += rev;
        v[3] += margin;
    }

    private static int count(List<Call> calls, Predicate<Call> p) {
        return (int) calls.stream().filter(p).count();
    }

    private static double sum(List<Call> calls, java.util.function.ToDoubleFunction<Call> f) {
        return calls.stream().mapToDouble(f).sum();
    }

    private static double avg(List<Call> calls, java.util.function.ToDoubleFunction<Call> f) {
        return calls.stream().mapToDouble(f).average().orElse(0);
    }

    private static double rawRate(int num, int den) {
        return den == 0 ? 0 : (double) num / den;
    }

    static double rate(int num, int den) {
        return round(rawRate(num, den), 4);
    }

    static double money(double v) {
        return round(v, 2);
    }

    static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    private static String pct(double r) {
        return Math.round(r * 1000) / 10.0 + "%";
    }
}
