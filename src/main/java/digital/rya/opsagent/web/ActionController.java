package digital.rya.opsagent.web;

import digital.rya.opsagent.actions.ActionService;
import digital.rya.opsagent.actions.ActionStatus;
import digital.rya.opsagent.actions.AuditLog;
import digital.rya.opsagent.actions.PlatformAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Human side of the guardrails: review, approve or reject what the agent proposed. */
@RestController
public class ActionController {

    public record ApproveRequest(String approvedBy, String note) {
    }

    public record RejectRequest(String rejectedBy, String reason) {
    }

    private final ActionService actions;

    public ActionController(ActionService actions) {
        this.actions = actions;
    }

    /** All actions, or only those with a status, e.g. /actions?status=PENDING_APPROVAL */
    @GetMapping("/actions")
    public List<PlatformAction> list(@RequestParam(name = "status", required = false) String status) {
        return actions.list(status == null || status.isBlank() ? null : parseStatus(status));
    }

    @GetMapping("/actions/{id}")
    public PlatformAction get(@PathVariable("id") String id) {
        return actions.get(id);
    }

    @PostMapping("/actions/{id}/approve")
    public PlatformAction approve(@PathVariable("id") String id, @RequestBody ApproveRequest req) {
        requireText(req.approvedBy(), "approvedBy");
        return actions.approve(id, req.approvedBy(), req.note());
    }

    @PostMapping("/actions/{id}/reject")
    public PlatformAction reject(@PathVariable("id") String id, @RequestBody RejectRequest req) {
        requireText(req.rejectedBy(), "rejectedBy");
        requireText(req.reason(), "reason");
        return actions.reject(id, req.rejectedBy(), req.reason());
    }

    @GetMapping("/audit")
    public List<AuditLog.Entry> audit() {
        return actions.audit().entries();
    }

    private static ActionStatus parseStatus(String s) {
        try {
            return ActionStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown status '" + s + "'. Use one of " + List.of(ActionStatus.values()));
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + field + "' is required");
        }
    }
}
