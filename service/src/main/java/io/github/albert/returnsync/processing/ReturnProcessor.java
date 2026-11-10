package io.github.albert.returnsync.processing;

import com.google.protobuf.ByteString;
import io.github.albert.returnsync.salesforce.PermanentSalesforceException;
import io.github.albert.returnsync.salesforce.SalesforceRestClient;
import io.github.albert.returnsync.salesforce.SalesforceRestClient.StatusUpdate;
import io.github.albert.returnsync.salesforce.SalesforceRestClient.UpdateResult;
import io.github.albert.returnsync.salesforce.TransientSalesforceException;
import io.github.albert.returnsync.store.ReturnStore;
import io.github.albert.returnsync.store.ReturnStore.DeadLetter;
import io.github.albert.returnsync.store.ReturnStore.Processed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One FetchResponse at a time: drop duplicates -> decide -> one Salesforce call for the whole batch (retrying
 * transient errors only) -> one transaction "results + dead letters + replay ID". The REST call is deliberately
 * outside the transaction: holding a DB connection open during a network call is a classic mistake.
 */
@Service
public class ReturnProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReturnProcessor.class);
    static final int MAX_ATTEMPTS = 3;

    /** An event as the subscriber hands it over: decoded, or a reason why it can't be (poison message). */
    public record Incoming(ReturnEvent event, String poisonReason) {
        public static Incoming of(ReturnEvent event) { return new Incoming(event, null); }
        public static Incoming poison(String eventId, String reason) {
            return new Incoming(new ReturnEvent(null, null, null, eventId), reason);
        }
    }

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

    /**
     * @param lastReplayId replay ID of the last event in {@code batch}; saved only together with the results
     * @throws TransientSalesforceException after all attempts: nothing saved, the stream restarts from the last
     *                                      saved replay ID and the batch comes again
     */
    public void processBatch(List<Incoming> batch, ByteString lastReplayId) {
        List<DeadLetter> deadLetters = new ArrayList<>();
        Map<String, ReturnEvent> todo = new LinkedHashMap<>();
        for (Incoming in : batch) {
            ReturnEvent e = in.event();
            if (in.poisonReason() != null) {
                deadLetters.add(new DeadLetter(e, in.poisonReason()));
            } else if (e.externalId() == null || e.externalId().isBlank()) {
                deadLetters.add(new DeadLetter(e, "missing External_Id__c"));
            } else if (todo.putIfAbsent(e.externalId(), e) != null) {
                log.info("Duplicate {} within the batch, skipping", e.externalId());
            }
        }
        Set<String> alreadyDone = store.processedAmong(todo.keySet());  // redelivery (at-least-once) or EARLIEST
        for (String id : alreadyDone) {
            log.info("Duplicate {}, skipping", id);
            todo.remove(id);
        }

        List<Processed> processed = new ArrayList<>();
        if (!todo.isEmpty()) {
            List<ReturnEvent> events = List.copyOf(todo.values());
            List<StatusUpdate> updates = events.stream()
                    .map(e -> new StatusUpdate(e.externalId(), ReturnPolicy.decide(e)))
                    .toList();
            try {
                List<UpdateResult> results = callWithRetry(() -> salesforce.updateStatuses(updates));
                for (int i = 0; i < events.size(); i++) {
                    UpdateResult r = results.get(i);
                    if (r.success()) {
                        processed.add(new Processed(events.get(i), updates.get(i).status()));
                    } else {
                        deadLetters.add(new DeadLetter(events.get(i),
                                "Salesforce " + r.errorCode() + ": " + r.message()));
                    }
                }
            } catch (PermanentSalesforceException e) {        // the whole call was rejected: all of it goes
                for (ReturnEvent ev : events) deadLetters.add(new DeadLetter(ev, e.getMessage()));
            }
        }
        store.commitBatch(processed, deadLetters, topic, lastReplayId);   // one transaction
        if (!deadLetters.isEmpty()) log.warn("{} event(s) sent to dead letters", deadLetters.size());
    }

    private <T> T callWithRetry(Supplier<T> call) {
        Duration delay = initialBackoff;
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
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
