package digital.rya.opsagent.actions;

import digital.rya.opsagent.platform.Buyer;
import digital.rya.opsagent.platform.PlatformStore;
import digital.rya.opsagent.platform.Publisher;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The only way to change the platform. Every request goes through {@link ActionPolicy}, and
 * every outcome (applied, pending, blocked, dry-run, approved, rejected) is written to the
 * {@link AuditLog}.
 */
public class ActionService {

    private final PlatformStore store;
    private final ActionPolicy policy;
    private final AuditLog audit;
    private final Clock clock;
    private final Map<String, PlatformAction> actions = new LinkedHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    public ActionService(PlatformStore store, ActionPolicy policy, AuditLog audit, Clock clock) {
        this.store = store;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Submit a change. In dry-run mode nothing changes and nothing is queued; the result says
     * what would have happened.
     */
    public synchronized PlatformAction submit(ActionRequest req, boolean dryRun) {
        if (req.type() == null) {
            throw new IllegalArgumentException("Action type is required");
        }
        String current = currentValue(req.type(), req.targetId()); // throws if the target does not exist
        String newValue = normalize(req);
        Instant now = clock.instant();

        // Same change already waiting for approval? Return it instead of queueing a duplicate.
        Optional<PlatformAction> samePending = actions.values().stream()
                .filter(a -> a.status() == ActionStatus.PENDING_APPROVAL && a.type() == req.type()
                        && a.targetId().equals(req.targetId()) && a.newValue().equals(newValue))
                .findFirst();
        if (samePending.isPresent() && !dryRun) {
            return samePending.get();
        }

        ActionPolicy.Decision d = policy.evaluate(req, current, List.copyOf(actions.values()), now);
        ActionStatus status = switch (d.outcome()) {
            case BLOCK -> ActionStatus.BLOCKED_BY_POLICY;
            case AUTO -> dryRun ? ActionStatus.DRY_RUN : ActionStatus.AUTO_APPLIED;
            case NEEDS_APPROVAL -> dryRun ? ActionStatus.DRY_RUN : ActionStatus.PENDING_APPROVAL;
        };
        String note = dryRun && d.outcome() != ActionPolicy.Outcome.BLOCK
                ? "DRY RUN - would be " + (d.outcome() == ActionPolicy.Outcome.AUTO ? "applied automatically" : "sent for approval")
                + ". " + d.note()
                : d.note();

        PlatformAction action = new PlatformAction("ACT-" + String.format("%04d", seq.incrementAndGet()), req.type(),
                req.targetId(), current, newValue, req.reason(), req.requestedBy(), req.requestId(), status, note,
                d.warnings(), now, null, null, null);

        if (status == ActionStatus.AUTO_APPLIED) {
            apply(action);
            action = action.withDecision(ActionStatus.AUTO_APPLIED, now, "policy", "applied automatically");
        }
        actions.put(action.id(), action);
        audit.record(status.name(), action, req.requestedBy(), note, now);
        return action;
    }

    /** A person approves a pending action. It is applied only if the setting has not changed meanwhile. */
    public synchronized PlatformAction approve(String actionId, String approver, String note) {
        PlatformAction a = requirePending(actionId);
        Instant now = clock.instant();
        String current = currentValue(a.type(), a.targetId());
        if (!current.equals(a.oldValue())) {
            PlatformAction stale = a.withDecision(ActionStatus.FAILED_STALE, now, approver,
                    "Not applied: value is now " + current + ", it was " + a.oldValue() + " when proposed. Re-check and propose again.");
            actions.put(a.id(), stale);
            audit.record("FAILED_STALE", stale, approver, stale.decisionNote(), now);
            return stale;
        }
        apply(a);
        PlatformAction done = a.withDecision(ActionStatus.APPROVED_APPLIED, now, approver, note);
        actions.put(a.id(), done);
        audit.record("APPROVED_APPLIED", done, approver, note, now);
        return done;
    }

    public synchronized PlatformAction reject(String actionId, String rejecter, String reason) {
        PlatformAction a = requirePending(actionId);
        Instant now = clock.instant();
        PlatformAction done = a.withDecision(ActionStatus.REJECTED, now, rejecter, reason);
        actions.put(a.id(), done);
        audit.record("REJECTED", done, rejecter, reason, now);
        return done;
    }

    public synchronized List<PlatformAction> list(ActionStatus filter) {
        List<PlatformAction> out = new ArrayList<>();
        for (PlatformAction a : actions.values()) {
            if (filter == null || a.status() == filter) {
                out.add(a);
            }
        }
        out.sort(Comparator.comparing(PlatformAction::id));
        return out;
    }

    public synchronized PlatformAction get(String id) {
        PlatformAction a = actions.get(id);
        if (a == null) {
            throw new NoSuchElementException("Unknown action id: " + id);
        }
        return a;
    }

    public AuditLog audit() {
        return audit;
    }

    // ---------------------------------------------------------------------------------

    private PlatformAction requirePending(String id) {
        PlatformAction a = get(id);
        if (a.status() != ActionStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Action " + id + " is " + a.status() + ", only PENDING_APPROVAL actions can be decided");
        }
        return a;
    }

    private String currentValue(ActionType type, String targetId) {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId is required");
        }
        return switch (type) {
            case SET_BUYER_PRIORITY -> String.valueOf(store.buyer(targetId).priority());
            case SET_BUYER_CAP -> String.valueOf(store.buyer(targetId).dailyCap());
            case SET_BUYER_STATUS -> store.buyer(targetId).status().name();
            case FLAG_PUBLISHER -> String.valueOf(store.publisher(targetId).flaggedForReview());
            case SET_PUBLISHER_STATUS -> store.publisher(targetId).status().name();
        };
    }

    private static String normalize(ActionRequest req) {
        if (req.type() == ActionType.FLAG_PUBLISHER) {
            return "true";
        }
        String v = req.newValue() == null ? "" : req.newValue().trim();
        return (req.type() == ActionType.SET_BUYER_STATUS || req.type() == ActionType.SET_PUBLISHER_STATUS) ? v.toUpperCase() : v;
    }

    private void apply(PlatformAction a) {
        switch (a.type()) {
            case SET_BUYER_PRIORITY -> store.replaceBuyer(store.buyer(a.targetId()).withPriority(Integer.parseInt(a.newValue())));
            case SET_BUYER_CAP -> store.replaceBuyer(store.buyer(a.targetId()).withDailyCap(Integer.parseInt(a.newValue())));
            case SET_BUYER_STATUS -> store.replaceBuyer(store.buyer(a.targetId()).withStatus(Buyer.BuyerStatus.valueOf(a.newValue())));
            case FLAG_PUBLISHER -> store.replacePublisher(store.publisher(a.targetId()).withFlag(a.reason()));
            case SET_PUBLISHER_STATUS -> store.replacePublisher(
                    store.publisher(a.targetId()).withStatus(Publisher.PublisherStatus.valueOf(a.newValue())));
        }
    }
}
