package io.github.albert.returnsync.processing;

import com.google.protobuf.ByteString;
import io.github.albert.returnsync.salesforce.PermanentSalesforceException;
import io.github.albert.returnsync.salesforce.SalesforceRestClient;
import io.github.albert.returnsync.salesforce.TransientSalesforceException;
import io.github.albert.returnsync.store.ReturnStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Duplicate check -> decision -> Salesforce call (retrying transient errors only) -> one transaction
 * "result + replay ID". The REST call is deliberately outside the transaction: holding a DB connection
 * open during a network call is a classic mistake.
 */
@Service
public class ReturnProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReturnProcessor.class);
    static final int MAX_ATTEMPTS = 3;

    private final ReturnStore store;
    private final SalesforceRestClient salesforce;
    private final String topic;
    private final Duration initialBackoff;

    public ReturnProcessor(ReturnStore store, SalesforceRestClient salesforce,
                           @Value("${salesforce.topic}") String topic,
                           @Value("${returns.retry.initial-backoff:500ms}") Duration initialBackoff) {
        this.store = store;
        this.salesforce = salesforce;
        this.topic = topic;
        this.initialBackoff = initialBackoff;
    }

    public void process(ReturnEvent event, ByteString replayId) {
        if (store.isProcessed(event.externalId())) {          // redelivery (at-least-once) or EARLIEST replay
            log.info("Duplicate {}, skipping", event.externalId());
            store.saveReplayId(topic, replayId);
            return;
        }
        String status = ReturnPolicy.decide(event);
        try {
            callWithRetry(() -> salesforce.updateStatus(event.externalId(), status));
            store.markProcessedAndSaveReplay(event, status, topic, replayId);   // one transaction
        } catch (PermanentSalesforceException e) {
            store.deadLetterAndSaveReplay(event, e.getMessage(), topic, replayId); // one transaction
        }
        // TransientSalesforceException after all attempts goes up:
        // the subscriber restarts the stream from the last saved replay ID.
    }

    private void callWithRetry(Runnable call) {
        Duration delay = initialBackoff;
        for (int attempt = 1; ; attempt++) {
            try {
                call.run();
                return;
            } catch (TransientSalesforceException e) {
                if (attempt >= MAX_ATTEMPTS) throw e;
                log.warn("Transient error (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, e.getMessage());
                sleep(delay);
                delay = delay.multipliedBy(2);
            }
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientSalesforceException("interrupted", e);
        }
    }
}
