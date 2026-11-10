package io.github.albert.returnsync.store;

import com.google.protobuf.ByteString;
import io.github.albert.returnsync.processing.ReturnEvent;
import io.github.albert.returnsync.pubsub.PubSubSubscriber;
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

    @Test
    void secondInsertOfSameExternalIdIsIgnored() {
        ReturnEvent e = new ReturnEvent("RR-IT-1", "ORD-1", BigDecimal.TEN, "evt-1");

        assertThat(store.markProcessedAndSaveReplay(e, "Approved", TOPIC, ByteString.copyFromUtf8("a"))).isTrue();
        assertThat(store.markProcessedAndSaveReplay(e, "Approved", TOPIC, ByteString.copyFromUtf8("b"))).isFalse();
        assertThat(store.isProcessed("RR-IT-1")).isTrue();
        assertThat(store.isProcessed("RR-IT-unknown")).isFalse();
    }

    @Test
    void replayIdRoundTripsAsBytesAndIsOverwritten() {
        // not valid UTF-8 on purpose: replay IDs are opaque bytes
        ByteString first = ByteString.copyFrom(new byte[]{0, 0, 0, 0, 0, 0, (byte) 0xC3, (byte) 0x28});
        ByteString second = ByteString.copyFrom(new byte[]{0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0x01});

        store.saveReplayId(TOPIC, first);
        store.saveReplayId(TOPIC, second);

        assertThat(store.loadReplayId(TOPIC)).contains(second);
        assertThat(store.loadReplayId("/event/Other__e")).isEmpty();
    }

    @Test
    void deadLetterAndReplayAreWrittenTogether() {
        ReturnEvent e = new ReturnEvent("RR-IT-DL", "ORD-1", BigDecimal.TEN, "evt-2");
        ByteString replay = ByteString.copyFromUtf8("dl");

        store.deadLetterAndSaveReplay(e, "Salesforce 404: NOT_FOUND", TOPIC, replay);

        assertThat(jdbc.sql("SELECT reason FROM dead_letters WHERE external_id = ?").param("RR-IT-DL")
                .query(String.class).single()).contains("404");
        assertThat(store.loadReplayId(TOPIC)).contains(replay);
    }

    @Test
    void contextHasJackson3Mapper() {
        assertThat(jsonMapper).isInstanceOf(tools.jackson.databind.json.JsonMapper.class);
    }
}
