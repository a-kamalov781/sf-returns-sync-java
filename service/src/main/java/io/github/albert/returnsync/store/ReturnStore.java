package io.github.albert.returnsync.store;

import com.google.protobuf.ByteString;
import io.github.albert.returnsync.processing.ReturnEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A separate bean on purpose: {@code @Transactional} works through a proxy, so a call to a transactional
 * method from inside the same class would not open a transaction.
 */
@Repository
public class ReturnStore {

    public record Processed(ReturnEvent event, String status) {}

    public record DeadLetter(ReturnEvent event, String reason) {}

    private final JdbcClient jdbc;

    public ReturnStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Which of these External IDs already have a result (one query per batch). */
    public Set<String> processedAmong(Collection<String> externalIds) {
        if (externalIds.isEmpty()) return Set.of();
        return jdbc.sql("SELECT external_id FROM processed_returns WHERE external_id IN (:ids)")
                .param("ids", externalIds)
                .query(String.class)
                .list().stream().collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Results, dead letters and the replay ID of the last event of the batch, in one transaction: either the
     * whole batch is recorded and the stream moves on, or nothing is and the batch comes again.
     * {@code ON CONFLICT DO NOTHING} keeps a concurrent duplicate harmless.
     */
    @Transactional
    public void commitBatch(List<Processed> processed, List<DeadLetter> deadLetters,
                            String topic, ByteString replayId) {
        for (Processed p : processed) {
            jdbc.sql("""
                            INSERT INTO processed_returns (external_id, event_id, status)
                            VALUES (?, ?, ?)
                            ON CONFLICT (external_id) DO NOTHING
                            """)
                    .params(p.event().externalId(), p.event().eventId(), p.status())
                    .update();
        }
        for (DeadLetter d : deadLetters) {
            jdbc.sql("INSERT INTO dead_letters (external_id, payload, reason) VALUES (?, ?, ?)")
                    .params(d.event().externalId(), d.event().toString(), d.reason())
                    .update();
        }
        saveReplayId(topic, replayId);
    }

    /** No @Transactional: inside a caller's transaction it joins it. */
    public void saveReplayId(String topic, ByteString replayId) {
        jdbc.sql("""
                        INSERT INTO replay_state (topic, replay_id, updated_at)
                        VALUES (?, ?, now())
                        ON CONFLICT (topic) DO UPDATE SET replay_id = EXCLUDED.replay_id, updated_at = now()
                        """)
                .params(topic, replayId.toByteArray())
                .update();
    }

    public Optional<ByteString> loadReplayId(String topic) {
        return jdbc.sql("SELECT replay_id FROM replay_state WHERE topic = ?")
                .param(topic)
                .query((rs, n) -> ByteString.copyFrom(rs.getBytes(1)))
                .optional();
    }
}
