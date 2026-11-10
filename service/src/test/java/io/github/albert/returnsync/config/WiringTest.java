package io.github.albert.returnsync.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks that need no Docker: the Jackson 3 bean type and the num_requested limit. */
class WiringTest {

    @Test
    void springBoot4ProvidesJackson3JsonMapperBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).hasSingleBean(JsonMapper.class));
    }

    @Test
    void batchSizeAbove100IsRejected() {
        assertThatThrownBy(() -> props(101)).hasMessageContaining("1..100");
        assertThatThrownBy(() -> props(0)).hasMessageContaining("1..100");
        assertThat(props(100).batchSize()).isEqualTo(100);
    }

    private static SalesforceProperties props(int batchSize) {
        return new SalesforceProperties("https://login.salesforce.com", "https://login.salesforce.com",
                "id", "user", Path.of("key.pem"), "68.0", "api.pubsub.salesforce.com", 7443,
                "/event/Return_Requested__e", batchSize);
    }
}
