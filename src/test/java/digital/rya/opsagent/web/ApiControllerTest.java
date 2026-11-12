package digital.rya.opsagent.web;

import digital.rya.opsagent.actions.ActionRequest;
import digital.rya.opsagent.actions.ActionService;
import digital.rya.opsagent.actions.ActionType;
import digital.rya.opsagent.actions.PlatformAction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP layer: approve / reject flow and error codes. No Claude calls (no API key needed). */
@SpringBootTest(properties = {"opsagent.audit-file=", "anthropic.api-key="})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ApiControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ActionService actions;

    private PlatformAction proposeCap() {
        return actions.submit(new ActionRequest(ActionType.SET_BUYER_CAP, "BUY-203", "100",
                "Cap 75 reached at 13:53 on Nov 4, 12 extra calls rejected", "agent", null), false);
    }

    @Test
    void askWithoutAQuestionIsABadRequest() throws Exception {
        mvc.perform(post("/agent/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approvingAPendingActionAppliesItOnce() throws Exception {
        PlatformAction a = proposeCap();
        mvc.perform(get("/actions").param("status", "PENDING_APPROVAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(a.id()));

        mvc.perform(post("/actions/" + a.id() + "/approve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\":\"ops-lead\",\"note\":\"confirmed with buyer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED_APPLIED"));

        mvc.perform(get("/platform/buyers"))
                .andExpect(jsonPath("$[?(@.buyerId == 'BUY-203')].dailyCap").value(100));

        mvc.perform(post("/actions/" + a.id() + "/approve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\":\"ops-lead\"}"))
                .andExpect(status().isConflict());

        mvc.perform(get("/audit")).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void rejectNeedsAReasonAndChangesNothing() throws Exception {
        PlatformAction a = proposeCap();
        mvc.perform(post("/actions/" + a.id() + "/reject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectedBy\":\"ops-lead\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/actions/" + a.id() + "/reject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectedBy\":\"ops-lead\",\"reason\":\"buyer said no\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(get("/platform/buyers"))
                .andExpect(jsonPath("$[?(@.buyerId == 'BUY-203')].dailyCap").value(75));
    }

    @Test
    void unknownActionIsNotFound() throws Exception {
        mvc.perform(post("/actions/ACT-9999/approve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\":\"ops-lead\"}"))
                .andExpect(status().isNotFound());
    }
}
