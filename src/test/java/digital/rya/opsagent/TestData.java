package digital.rya.opsagent;

import digital.rya.opsagent.actions.ActionPolicy;
import digital.rya.opsagent.actions.ActionService;
import digital.rya.opsagent.actions.AuditLog;
import digital.rya.opsagent.metrics.MetricsService;
import digital.rya.opsagent.platform.PlatformLoader;
import digital.rya.opsagent.platform.PlatformStore;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Shared fixtures: the real data kit, loaded fresh for each test so actions never leak between tests. */
public final class TestData {

    public static final Path DATA = Path.of("data");
    public static final LocalDate NOV2 = LocalDate.parse("2026-11-02");
    public static final LocalDate NOV3 = LocalDate.parse("2026-11-03");
    public static final LocalDate NOV4 = LocalDate.parse("2026-11-04");

    private TestData() {
    }

    public static PlatformStore store() {
        return PlatformLoader.load(DATA);
    }

    /** A clock the test can move forward (for the once-per-24h rule). */
    public static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-05T14:00:00Z");

        public void advanceHours(long h) {
            now = now.plusSeconds(h * 3600);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    public record Fixture(PlatformStore store, MetricsService metrics, ActionService actions, MutableClock clock) {
    }

    public static Fixture fixture(boolean autoApplyEnabled) {
        PlatformStore store = store();
        MetricsService metrics = new MetricsService(store);
        MutableClock clock = new MutableClock();
        ActionService actions = new ActionService(store, new ActionPolicy(store, metrics, autoApplyEnabled), new AuditLog(null), clock);
        return new Fixture(store, metrics, actions, clock);
    }
}
