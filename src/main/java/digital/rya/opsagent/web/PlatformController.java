package digital.rya.opsagent.web;

import digital.rya.opsagent.platform.Buyer;
import digital.rya.opsagent.platform.PlatformStore;
import digital.rya.opsagent.platform.Publisher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only view of the current configuration, to see the effect of applied actions. */
@RestController
public class PlatformController {

    private final PlatformStore store;

    public PlatformController(PlatformStore store) {
        this.store = store;
    }

    @GetMapping("/platform/buyers")
    public List<Buyer> buyers() {
        return store.buyers();
    }

    @GetMapping("/platform/publishers")
    public List<Publisher> publishers() {
        return store.publishers();
    }
}
