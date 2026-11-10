package io.github.albert.returnsync.store;

import com.google.protobuf.ByteString;
import io.github.albert.returnsync.processing.ReturnEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * A separate bean on purpose: {@code @Transactional} works through a proxy, so a call to a transactional
 * method from inside the same class would not open a transaction.
 */
@Repository
public class ReturnStore {

    private final JdbcClient jdbc;

    public ReturnStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isProcessed(String externalId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM processed_returns WHERE external_id = ?)")
                .param(externalId)
                .query(Boolean.class)
                .single();
    }

    /** true = first write; false = already there (ON CONFLICT DO NOTHING). */
    @Transactional
    public boolean markProcessedAndSaveReplay(ReturnEvent e, String status, String topic, ByteString replayId) {
        int inserted = jdbc.sql("""
                        INSERT INTO processed_returns (external_id, event_id, status)
                        VALUES (?, ?, ?)
                        ON CONFLICT (external_id) DO NOTHING
                        """)
                .params(e.externalId(), e.eventId(), status)
                .update();
        saveReplayId(topic, replayId);
        return inserted == 1;
    }

    @Transactional
    public void deadLetterAndSaveReplay(ReturnEvent e, String reason, String topic, ByteString replayId) {
        jdbc.sql("INSERT INTO dead_letters (external_id, payload, reason) VALUES (?, ?, ?)")
                .params(e.externalId(), e.toString(), reason)
                .update();
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
