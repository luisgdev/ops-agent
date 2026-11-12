package digital.rya.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Reads the data kit (3 JSON files + 2 CSV files) into a {@link PlatformStore}. */
public final class PlatformLoader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PlatformLoader() {
    }

    public static PlatformStore load(Path dataDir) {
        try {
            return new PlatformStore(
                    loadCampaigns(dataDir.resolve("campaigns.json")),
                    loadBuyers(dataDir.resolve("buyers.json")),
                    loadPublishers(dataDir.resolve("publishers.json")),
                    loadCalls(dataDir.resolve("calls.csv")),
                    loadPings(dataDir.resolve("availability_pings.csv")));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load platform data from " + dataDir.toAbsolutePath(), e);
        }
    }

    static List<Campaign> loadCampaigns(Path file) throws IOException {
        List<Campaign> out = new ArrayList<>();
        for (JsonNode n : JSON.readTree(file.toFile())) {
            out.add(new Campaign(n.get("campaignId").asText(), n.get("name").asText(),
                    n.get("vertical").asText(), n.get("payableAfterSec").asInt()));
        }
        return out;
    }

    static List<Buyer> loadBuyers(Path file) throws IOException {
        List<Buyer> out = new ArrayList<>();
        for (JsonNode n : JSON.readTree(file.toFile())) {
            out.add(new Buyer(
                    n.get("buyerId").asText(),
                    n.get("name").asText(),
                    strings(n.get("campaignIds")),
                    new HashSet<>(strings(n.get("states"))),
                    n.get("dailyCap").asInt(),
                    n.get("priority").asInt(),
                    n.get("revenuePerPayableCall").asDouble(),
                    Buyer.BuyerStatus.valueOf(n.get("status").asText())));
        }
        return out;
    }

    static List<Publisher> loadPublishers(Path file) throws IOException {
        List<Publisher> out = new ArrayList<>();
        for (JsonNode n : JSON.readTree(file.toFile())) {
            out.add(new Publisher(
                    n.get("publisherId").asText(),
                    n.get("name").asText(),
                    strings(n.get("campaignIds")),
                    n.get("payoutPerPayableCall").asDouble(),
                    Publisher.PublisherStatus.ACTIVE,
                    false,
                    null));
        }
        return out;
    }

    static List<Call> loadCalls(Path file) throws IOException {
        List<String[]> rows = readCsv(file, List.of("callId", "startedAt", "campaignId", "publisherId", "buyerId",
                "callerState", "callerHash", "durationSec", "status", "revenue", "payout"));
        List<Call> out = new ArrayList<>(rows.size());
        for (String[] r : rows) {
            out.add(new Call(r[0], OffsetDateTime.parse(r[1]), r[2], r[3], r[4].isBlank() ? null : r[4], r[5], r[6],
                    Integer.parseInt(r[7]), CallStatus.valueOf(r[8]), Double.parseDouble(r[9]), Double.parseDouble(r[10])));
        }
        return out;
    }

    static List<AvailabilityPing> loadPings(Path file) throws IOException {
        List<String[]> rows = readCsv(file, List.of("timestamp", "buyerId", "agentsAvailable"));
        List<AvailabilityPing> out = new ArrayList<>(rows.size());
        for (String[] r : rows) {
            out.add(new AvailabilityPing(OffsetDateTime.parse(r[0]), r[1], Integer.parseInt(r[2])));
        }
        return out;
    }

    /** Minimal CSV reader: the kit has no quoted fields. Fails loudly if the header changes. */
    private static List<String[]> readCsv(Path file, List<String> expectedHeader) throws IOException {
        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty()) {
            throw new IOException("Empty file: " + file);
        }
        List<String> header = List.of(lines.get(0).trim().split(",", -1));
        if (!header.equals(expectedHeader)) {
            throw new IOException("Unexpected header in " + file + ": " + header);
        }
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cols = line.split(",", -1);
            if (cols.length != expectedHeader.size()) {
                throw new IOException("Line " + (i + 1) + " of " + file + " has " + cols.length + " columns");
            }
            rows.add(cols);
        }
        return rows;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
