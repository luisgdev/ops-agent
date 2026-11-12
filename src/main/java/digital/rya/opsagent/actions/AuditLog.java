package digital.rya.opsagent.actions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only record of every action event: proposed, auto-applied, blocked, dry-run,
 * approved, rejected. Kept in memory for the API and, if a file is configured, also written
 * as one JSON object per line so it survives a restart and can be grepped.
 */
public class AuditLog {

    public record Entry(
            Instant at,
            String event,
            String actionId,
            ActionType type,
            String targetId,
            String oldValue,
            String newValue,
            ActionStatus status,
            String actor,
            String reason,
            String note) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Path file;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** @param file JSONL file to append to, or null for memory only */
    public AuditLog(Path file) {
        this.file = file;
    }

    public synchronized void record(String event, PlatformAction a, String actor, String note, Instant at) {
        Entry e = new Entry(at, event, a.id(), a.type(), a.targetId(), a.oldValue(), a.newValue(), a.status(), actor,
                a.reason(), note);
        entries.add(e);
        if (file != null) {
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, json.writeValueAsString(e) + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                throw new UncheckedIOException("Could not write audit log " + file, ex);
            }
        }
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }
}
