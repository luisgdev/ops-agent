package digital.rya.opsagent.actions;

import digital.rya.opsagent.TestData;
import digital.rya.opsagent.TestData.Fixture;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guardrails: what is applied alone, what waits for a person, what is refused. */
class ActionServiceTest {

    private static final String REASON = "Failure rate 42.9% on Nov 4 vs 11.2% on Nov 2 while reporting free agents";

    private final Fixture f = TestData.fixture(true);

    private PlatformAction submit(ActionType type, String target, String value, boolean dryRun) {
        return f.actions().submit(new ActionRequest(type, target, value, REASON, "agent", "REQ-test"), dryRun);
    }

    @Test
    void oneStepDemotionIsAppliedAutomatically() {
        PlatformAction a = submit(ActionType.SET_BUYER_PRIORITY, "BUY-202", "2", false);
        assertEquals(ActionStatus.AUTO_APPLIED, a.status());
        assertEquals("1", a.oldValue());
        assertEquals(2, f.store().buyer("BUY-202").priority());
        assertEquals(1, f.actions().audit().entries().size());
    }

    @Test
    void secondAutoDemotionWithin24hNeedsApproval() {
        submit(ActionType.SET_BUYER_PRIORITY, "BUY-202", "2", false);
        PlatformAction second = submit(ActionType.SET_BUYER_PRIORITY, "BUY-202", "3", false);
        assertEquals(ActionStatus.PENDING_APPROVAL, second.status());
        assertEquals(2, f.store().buyer("BUY-202").priority(), "pending actions change nothing");

        f.clock().advanceHours(25);
        PlatformAction later = submit(ActionType.SET_BUYER_PRIORITY, "BUY-201", "2", false);
        assertEquals(ActionStatus.AUTO_APPLIED, later.status(), "other buyers are not affected by the cooldown");
    }

    @Test
    void promotionsAndBigJumpsNeedApproval() {
        assertEquals(ActionStatus.PENDING_APPROVAL, submit(ActionType.SET_BUYER_PRIORITY, "BUY-204", "1", false).status());
        assertEquals(ActionStatus.PENDING_APPROVAL, submit(ActionType.SET_BUYER_PRIORITY, "BUY-201", "3", false).status());
    }

    @Test
    void capChangesAlwaysNeedApprovalAndWarnWhenRaising() {
        PlatformAction a = submit(ActionType.SET_BUYER_CAP, "BUY-203", "100", false);
        assertEquals(ActionStatus.PENDING_APPROVAL, a.status());
        assertEquals(75, f.store().buyer("BUY-203").dailyCap());
        assertFalse(a.warnings().isEmpty());
    }

    @Test
    void absurdValuesAreBlocked() {
        assertEquals(ActionStatus.BLOCKED_BY_POLICY, submit(ActionType.SET_BUYER_CAP, "BUY-203", "0", false).status());
        assertEquals(ActionStatus.BLOCKED_BY_POLICY, submit(ActionType.SET_BUYER_CAP, "BUY-203", "1000", false).status());
        assertEquals(ActionStatus.BLOCKED_BY_POLICY, submit(ActionType.SET_BUYER_PRIORITY, "BUY-202", "9", false).status());
        assertEquals(ActionStatus.BLOCKED_BY_POLICY, submit(ActionType.SET_BUYER_PRIORITY, "BUY-202", "1", false).status(), "no-op");
        assertEquals(ActionStatus.BLOCKED_BY_POLICY, submit(ActionType.SET_BUYER_STATUS, "BUY-202", "DELETED", false).status());
    }

    @Test
    void everyChangeNeedsAReason() {
        PlatformAction a = f.actions().submit(new ActionRequest(ActionType.FLAG_PUBLISHER, "PUB-104", null, "bad", "agent", null), false);
        assertEquals(ActionStatus.BLOCKED_BY_POLICY, a.status());
        assertFalse(f.store().publisher("PUB-104").flaggedForReview());
    }

    @Test
    void pausingASoleCoverageBuyerNeedsApprovalAndSaysWhatWouldBeStranded() {
        PlatformAction a = submit(ActionType.SET_BUYER_STATUS, "BUY-202", "PAUSED", false);
        assertEquals(ActionStatus.PENDING_APPROVAL, a.status());
        assertTrue(a.warnings().get(0).contains("TN on CMP-MA-SEARCH"), a.warnings().toString());
        assertTrue(f.store().buyer("BUY-202").isActive());
    }

    @Test
    void flaggingAPublisherIsAutomaticPausingItIsNot() {
        assertEquals(ActionStatus.AUTO_APPLIED, submit(ActionType.FLAG_PUBLISHER, "PUB-104", null, false).status());
        assertTrue(f.store().publisher("PUB-104").flaggedForReview());
        assertEquals(ActionStatus.PENDING_APPROVAL, submit(ActionType.SET_PUBLISHER_STATUS, "PUB-104", "PAUSED", false).status());
    }

    @Test
    void dryRunChangesNothingAndQueuesNothing() {
        PlatformAction auto = submit(ActionType.SET_BUYER_PRIORITY, "BUY-202", "2", true);
        PlatformAction approval = submit(ActionType.SET_BUYER_CAP, "BUY-203", "100", true);
        assertEquals(ActionStatus.DRY_RUN, auto.status());
        assertTrue(auto.policyNote().contains("would be applied automatically"));
        assertTrue(approval.policyNote().contains("would be sent for approval"));
        assertEquals(1, f.store().buyer("BUY-202").priority());
        assertEquals(75, f.store().buyer("BUY-203").dailyCap());
        assertTrue(f.actions().list(ActionStatus.PENDING_APPROVAL).isEmpty());
        assertEquals(2, f.actions().audit().entries().size(), "dry runs are still audited");
    }

    @Test
    void approveAppliesAndRejectDoesNot() {
        PlatformAction cap = submit(ActionType.SET_BUYER_CAP, "BUY-203", "100", false);
        PlatformAction approved = f.actions().approve(cap.id(), "ops-lead", "Buyer East confirmed by phone");
        assertEquals(ActionStatus.APPROVED_APPLIED, approved.status());
        assertEquals(100, f.store().buyer("BUY-203").dailyCap());

        PlatformAction pause = submit(ActionType.SET_PUBLISHER_STATUS, "PUB-104", "PAUSED", false);
        PlatformAction rejected = f.actions().reject(pause.id(), "ops-lead", "Talk to the affiliate first");
        assertEquals(ActionStatus.REJECTED, rejected.status());
        assertEquals("ACTIVE", f.store().publisher("PUB-104").status().name());

        assertThrows(IllegalStateException.class, () -> f.actions().approve(pause.id(), "ops-lead", "again"));
    }

    @Test
    void approvalIsRefusedIfTheSettingChangedSinceTheProposal() {
        PlatformAction proposal = submit(ActionType.SET_BUYER_CAP, "BUY-203", "100", false);
        PlatformAction other = submit(ActionType.SET_BUYER_CAP, "BUY-203", "90", false);
        f.actions().approve(other.id(), "ops-lead", "ok");
        PlatformAction stale = f.actions().approve(proposal.id(), "ops-lead", "ok");
        assertEquals(ActionStatus.FAILED_STALE, stale.status());
        assertEquals(90, f.store().buyer("BUY-203").dailyCap());
    }

    @Test
    void samePendingChangeIsNotQueuedTwice() {
        PlatformAction a = submit(ActionType.SET_BUYER_CAP, "BUY-203", "100", false);
        PlatformAction b = submit(ActionType.SET_BUYER_CAP, "BUY-203", "100", false);
        assertEquals(a.id(), b.id());
        assertEquals(1, f.actions().list(ActionStatus.PENDING_APPROVAL).size());
    }

    @Test
    void killSwitchSendsEverythingToApproval() {
        Fixture locked = TestData.fixture(false);
        PlatformAction a = locked.actions().submit(
                new ActionRequest(ActionType.SET_BUYER_PRIORITY, "BUY-202", "2", REASON, "agent", null), false);
        assertEquals(ActionStatus.PENDING_APPROVAL, a.status());
        assertEquals(1, locked.store().buyer("BUY-202").priority());
    }

    @Test
    void unknownTargetIsAnError() {
        assertThrows(NoSuchElementException.class, () -> submit(ActionType.SET_BUYER_CAP, "BUY-999", "10", false));
    }
}
