package io.github.albert.returnsync.store;

import com.google.protobuf.ByteString;
import io.github.albert.returnsync.processing.ReturnEvent;
import io.github.albert.returnsync.pubsub.PubSubSubscriber;
import io.github.albert.returnsync.store.ReturnStore.DeadLetter;
import io.github.albert.returnsync.store.ReturnStore.Processed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL in Docker; Flyway applies the migration. The subscriber is a mock, so the context
 * never connects to Salesforce. Skipped (not failed) on a machine without Docker; CI always has Docker.
 */
@SpringBootTest(properties = {
        "salesforce.client-id=test", "salesforce.username=test@example.com",
        "salesforce.private-key-path=/dev/null"
})
@Testcontainers(disabledWithoutDocker = true)
class ReturnStoreIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean PubSubSubscriber subscriber;   // don't connect to Salesforce
    @Autowired ReturnStore store;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper jsonMapper;           // Boot 4 exposes the Jackson 3 JsonMapper bean

    private static final String TOPIC = "/event/Return_Requested__e";

    private static ReturnEvent event(String id) {
        return new ReturnEvent(id, "ORD-1", BigDecimal.TEN, "evt-" + id);
    }

    @Test
    void batchCommitRecordsResultsDeadLettersAndReplayTogether() {
        ByteString replay = ByteString.copyFromUtf8("batch-1");

        store.commitBatch(
                List.of(new Processed(event("RR-IT-1"), "Approved"), new Processed(event("RR-IT-2"), "Manual_Review")),
                List.of(new DeadLetter(event("RR-IT-DL"), "Salesforce NOT_FOUND: The requested resource does not exist")),
                TOPIC, replay);

        assertThat(store.processedAmong(List.of("RR-IT-1", "RR-IT-2", "RR-IT-DL", "RR-IT-unknown")))
                .containsExactlyInAnyOrder("RR-IT-1", "RR-IT-2");
        assertThat(jdbc.sql("SELECT reason FROM dead_letters WHERE external_id = ?").param("RR-IT-DL")
                .query(String.class).single()).contains("NOT_FOUND");
        assertThat(store.loadReplayId(TOPIC)).contains(replay);
    }

    @Test
    void secondCommitOfSameExternalIdIsIgnored() {
        store.commitBatch(List.of(new Processed(event("RR-IT-DUP"), "Approved")), List.of(), TOPIC, ByteString.copyFromUtf8("a"));
        store.commitBatch(List.of(new Processed(event("RR-IT-DUP"), "Rejected")), List.of(), TOPIC, ByteString.copyFromUtf8("b"));

        assertThat(jdbc.sql("SELECT status FROM processed_returns WHERE external_id = ?").param("RR-IT-DUP")
                .query(String.class).single()).isEqualTo("Approved");
        assertThat(store.loadReplayId(TOPIC)).contains(ByteString.copyFromUtf8("b"));
    }

    @Test
    void processedAmongEmptyInputNeedsNoQuery() {
        assertThat(store.processedAmong(List.of())).isEmpty();
    }

    @Test
    void replayIdRoundTripsAsBytesAndIsOverwritten() {
        // not valid UTF-8 on purpose: replay IDs are opaque bytes (the live org sends 29 bytes, not a number)
        ByteString first = ByteString.copyFrom(new byte[]{0, 0, 0, 0, 0, 0, (byte) 0xC3, (byte) 0x28});
        ByteString second = ByteString.copyFrom(new byte[]{0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0x01});

        store.saveReplayId(TOPIC, first);
        store.saveReplayId(TOPIC, second);

        assertThat(store.loadReplayId(TOPIC)).contains(second);
        assertThat(store.loadReplayId("/event/Other__e")).isEmpty();
    }

    @Test
    void contextHasJackson3Mapper() {
        assertThat(jsonMapper).isInstanceOf(tools.jackson.databind.json.JsonMapper.class);
    }
}
