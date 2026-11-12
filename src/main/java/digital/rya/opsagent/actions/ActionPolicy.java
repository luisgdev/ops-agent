package digital.rya.opsagent.actions;

import digital.rya.opsagent.metrics.MetricsService;
import digital.rya.opsagent.metrics.Reports.CoverageReport;
import digital.rya.opsagent.platform.Buyer;
import digital.rya.opsagent.platform.PlatformStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The guardrails: decides, for each requested change, whether the agent may apply it on its
 * own, needs a person's approval, or must not do it at all.
 *
 * <pre>
 * Action                         Decision
 * -----------------------------  ------------------------------------------------------------
 * FLAG_PUBLISHER                 AUTO (changes no routing, just asks a human to look)
 * SET_BUYER_PRIORITY  demote +1  AUTO, at most once per buyer per 24h; anything else -> approval
 * SET_BUYER_CAP                  APPROVAL (a cap is a commercial agreement with the buyer)
 * SET_BUYER_STATUS               APPROVAL (pausing can strand states; warns if it does)
 * SET_PUBLISHER_STATUS           APPROVAL (cuts a partner's traffic)
 * any, without a real reason     BLOCK
 * out-of-range values / no-ops   BLOCK
 * </pre>
 *
 * The idea: the agent acts alone only when the change is small, reversible and cannot leave a
 * caller without a buyer. Everything with commercial or coverage impact goes to a person.
 */
public class ActionPolicy {

    public enum Outcome { AUTO, NEEDS_APPROVAL, BLOCK }

    public record Decision(Outcome outcome, String note, List<String> warnings) {
    }

    static final int MIN_REASON_LENGTH = 20;
    static final int MIN_PRIORITY = 1;
    static final int MAX_PRIORITY = 5;
    static final double MAX_CAP_CHANGE_FACTOR = 3.0;
    static final Duration AUTO_PRIORITY_COOLDOWN = Duration.ofHours(24);

    private final PlatformStore store;
    private final MetricsService metrics;
    private final boolean autoApplyEnabled;

    /** @param autoApplyEnabled kill switch: when false, nothing is auto-applied, everything needs approval */
    public ActionPolicy(PlatformStore store, MetricsService metrics, boolean autoApplyEnabled) {
        this.store = store;
        this.metrics = metrics;
        this.autoApplyEnabled = autoApplyEnabled;
    }

    /**
     * @param currentValue    the setting's value right now
     * @param recentActions   actions already recorded, used for the once-per-24h rule
     */
    public Decision evaluate(ActionRequest req, String currentValue, List<PlatformAction> recentActions, Instant now) {
        if (req.reason() == null || req.reason().trim().length() < MIN_REASON_LENGTH) {
            return block("Every change needs a specific reason (at least " + MIN_REASON_LENGTH
                    + " characters) explaining the evidence behind it.");
        }
        Decision d = switch (req.type()) {
            case FLAG_PUBLISHER -> "true".equals(currentValue)
                    ? block("Publisher is already flagged for review.")
                    : new Decision(Outcome.AUTO, "Flagging only asks a person to review; it changes no routing.", List.of());
            case SET_PUBLISHER_STATUS -> statusChange(req, currentValue,
                    "Pausing or resuming a publisher cuts or restores a partner's traffic; a person decides.", List.of());
            case SET_BUYER_STATUS -> statusChange(req, currentValue,
                    "Pausing or resuming a buyer changes where calls go; a person decides.", buyerPauseWarnings(req));
            case SET_BUYER_PRIORITY -> priority(req, currentValue, recentActions, now);
            case SET_BUYER_CAP -> cap(req, currentValue);
        };
        if (d.outcome() == Outcome.AUTO && !autoApplyEnabled) {
            return new Decision(Outcome.NEEDS_APPROVAL, "Auto-apply is switched off, so this needs approval. (" + d.note() + ")",
                    d.warnings());
        }
        return d;
    }

    private Decision statusChange(ActionRequest req, String currentValue, String note, List<String> warnings) {
        String v = req.newValue() == null ? "" : req.newValue().trim().toUpperCase();
        if (!v.equals("ACTIVE") && !v.equals("PAUSED")) {
            return block("Status must be ACTIVE or PAUSED, got '" + req.newValue() + "'.");
        }
        if (v.equals(currentValue)) {
            return block("It is already " + v + ".");
        }
        return new Decision(Outcome.NEEDS_APPROVAL, note, warnings);
    }

    private List<String> buyerPauseWarnings(ActionRequest req) {
        if (!"PAUSED".equalsIgnoreCase(req.newValue())) {
            return List.of();
        }
        CoverageReport cov = metrics.buyerCoverage(req.targetId(), store.latestDay());
        if (cov.soleCoverageStates().isEmpty()) {
            return List.of();
        }
        return List.of("Pausing " + req.targetId() + " leaves no buyer for: " + String.join(", ", cov.soleCoverageStates())
                + ". On " + cov.day() + " that was " + cov.callsOnDayThatWouldHaveNoBuyerIfPaused() + " calls.");
    }

    private Decision priority(ActionRequest req, String currentValue, List<PlatformAction> recent, Instant now) {
        Integer target = parseInt(req.newValue());
        if (target == null || target < MIN_PRIORITY || target > MAX_PRIORITY) {
            return block("Priority must be a whole number from " + MIN_PRIORITY + " to " + MAX_PRIORITY + ".");
        }
        int current = Integer.parseInt(currentValue);
        if (target == current) {
            return block("Priority is already " + current + ".");
        }
        List<String> warnings = new ArrayList<>();
        Buyer buyer = store.buyer(req.targetId());
        if (target > current) {
            // Demoting: who picks up the calls this buyer gets first today?
            warnings.add("Lower priority means other buyers covering the same states get calls first; "
                    + buyer.name() + " still receives calls nobody else can take.");
        }
        boolean oneStepDemotion = target == current + 1;
        boolean recentlyAutoChanged = recent.stream().anyMatch(a -> a.type() == ActionType.SET_BUYER_PRIORITY
                && a.targetId().equals(req.targetId()) && a.status() == ActionStatus.AUTO_APPLIED
                && a.createdAt().isAfter(now.minus(AUTO_PRIORITY_COOLDOWN)));
        if (oneStepDemotion && !recentlyAutoChanged) {
            return new Decision(Outcome.AUTO, "One-step demotion: small, reversible, and the buyer stays as a fallback.", warnings);
        }
        String why = !oneStepDemotion
                ? (target < current ? "Promotions send a buyer more calls" : "Demotions of more than one step are large changes")
                : "This buyer's priority was already auto-changed in the last 24h";
        return new Decision(Outcome.NEEDS_APPROVAL, why + "; a person approves.", warnings);
    }

    private Decision cap(ActionRequest req, String currentValue) {
        Integer target = parseInt(req.newValue());
        int current = Integer.parseInt(currentValue);
        if (target == null || target <= 0) {
            return block("Cap must be a positive whole number. To stop a buyer, pause it instead.");
        }
        if (target == current) {
            return block("Cap is already " + current + ".");
        }
        if (target > current * MAX_CAP_CHANGE_FACTOR || target < current / MAX_CAP_CHANGE_FACTOR) {
            return block("A change from " + current + " to " + target + " is more than " + (int) MAX_CAP_CHANGE_FACTOR
                    + "x; do it manually if it is really intended.");
        }
        List<String> warnings = new ArrayList<>();
        if (target > current) {
            warnings.add("Raising the cap only helps if the buyer agrees to take more calls. Confirm with "
                    + store.buyer(req.targetId()).name() + " before approving.");
        }
        return new Decision(Outcome.NEEDS_APPROVAL, "Caps are commercial agreements with the buyer; a person approves.", warnings);
    }

    private static Decision block(String note) {
        return new Decision(Outcome.BLOCK, note, List.of());
    }

    private static Integer parseInt(String s) {
        try {
            return s == null ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
