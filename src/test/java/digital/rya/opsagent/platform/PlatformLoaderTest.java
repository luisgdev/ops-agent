package digital.rya.opsagent.platform;

import digital.rya.opsagent.TestData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformLoaderTest {

    @Test
    void loadsTheWholeDataKit() {
        PlatformStore store = TestData.store();
        assertEquals(1639, store.calls().size());
        assertEquals(600, store.pings().size());
        assertEquals(3, store.campaigns().size());
        assertEquals(5, store.buyers().size());
        assertEquals(5, store.publishers().size());
        assertEquals(List.of(TestData.NOV2, TestData.NOV3, TestData.NOV4), store.days());
    }

    @Test
    void parsesBuyerSettingsAndUnroutedCalls() {
        PlatformStore store = TestData.store();
        Buyer gulf = store.buyer("BUY-204");
        assertEquals(2, gulf.priority());
        assertEquals(100, gulf.dailyCap());
        assertTrue(gulf.coversState("MS"));
        assertTrue(store.buyer("BUY-205").coversState("AZ"), "'*' means every state");

        Call unrouted = store.calls().stream().filter(c -> c.status() == CallStatus.NOT_ROUTED_NO_BUYER).findFirst().orElseThrow();
        assertNull(unrouted.buyerId(), "blank buyerId becomes null");
    }
}
