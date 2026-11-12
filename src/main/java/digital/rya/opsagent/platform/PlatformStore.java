package digital.rya.opsagent.platform;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The in-memory platform: configuration (campaigns, buyers, publishers) plus history
 * (calls, availability pings).
 *
 * <p>History is read-only. Configuration can only be changed through
 * {@link #replaceBuyer(Buyer)} / {@link #replacePublisher(Publisher)}, which only the
 * {@code ActionService} calls, so every change goes through guardrails and the audit log.
 */
public class PlatformStore {

    private final Map<String, Campaign> campaigns;
    private final Map<String, Buyer> buyers;
    private final Map<String, Publisher> publishers;
    private final List<Call> calls;
    private final List<AvailabilityPing> pings;

    public PlatformStore(Collection<Campaign> campaigns, Collection<Buyer> buyers, Collection<Publisher> publishers,
                         List<Call> calls, List<AvailabilityPing> pings) {
        this.campaigns = index(campaigns, Campaign::campaignId);
        this.buyers = index(buyers, Buyer::buyerId);
        this.publishers = index(publishers, Publisher::publisherId);
        List<Call> sorted = new ArrayList<>(calls);
        sorted.sort((a, b) -> a.startedAt().compareTo(b.startedAt()));
        this.calls = List.copyOf(sorted);
        this.pings = List.copyOf(pings);
    }

    private static <T> Map<String, T> index(Collection<T> items, java.util.function.Function<T, String> key) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T item : items) {
            map.put(key.apply(item), item);
        }
        return map;
    }

    // ---- configuration ----------------------------------------------------------------

    public synchronized List<Campaign> campaigns() {
        return List.copyOf(campaigns.values());
    }

    public synchronized List<Buyer> buyers() {
        return List.copyOf(buyers.values());
    }

    public synchronized List<Publisher> publishers() {
        return List.copyOf(publishers.values());
    }

    public synchronized Optional<Campaign> findCampaign(String id) {
        return Optional.ofNullable(campaigns.get(id));
    }

    public synchronized Optional<Buyer> findBuyer(String id) {
        return Optional.ofNullable(buyers.get(id));
    }

    public synchronized Optional<Publisher> findPublisher(String id) {
        return Optional.ofNullable(publishers.get(id));
    }

    public Buyer buyer(String id) {
        return findBuyer(id).orElseThrow(() -> new NoSuchElementException("Unknown buyerId: " + id));
    }

    public Publisher publisher(String id) {
        return findPublisher(id).orElseThrow(() -> new NoSuchElementException("Unknown publisherId: " + id));
    }

    /** Only ActionService should call this, so the change is checked and audited. */
    public synchronized void replaceBuyer(Buyer updated) {
        if (!buyers.containsKey(updated.buyerId())) {
            throw new NoSuchElementException("Unknown buyerId: " + updated.buyerId());
        }
        buyers.put(updated.buyerId(), updated);
    }

    public synchronized void replacePublisher(Publisher updated) {
        if (!publishers.containsKey(updated.publisherId())) {
            throw new NoSuchElementException("Unknown publisherId: " + updated.publisherId());
        }
        publishers.put(updated.publisherId(), updated);
    }

    // ---- history ----------------------------------------------------------------------

    public List<Call> calls() {
        return calls;
    }

    public List<Call> callsOn(LocalDate day) {
        return calls.stream().filter(c -> c.day().equals(day)).toList();
    }

    public List<AvailabilityPing> pings() {
        return pings;
    }

    /** Days present in the call history, oldest first. */
    public List<LocalDate> days() {
        TreeSet<LocalDate> days = new TreeSet<>();
        calls.forEach(c -> days.add(c.day()));
        return Collections.unmodifiableList(new ArrayList<>(days));
    }

    public LocalDate latestDay() {
        List<LocalDate> days = days();
        if (days.isEmpty()) {
            throw new IllegalStateException("No call history loaded");
        }
        return days.get(days.size() - 1);
    }
}
