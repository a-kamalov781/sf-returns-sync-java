package io.github.albert.returnsync.pubsub;

import com.google.protobuf.ByteString;
import com.salesforce.eventbus.protobuf.ConsumerEvent;
import com.salesforce.eventbus.protobuf.FetchRequest;
import com.salesforce.eventbus.protobuf.FetchResponse;
import com.salesforce.eventbus.protobuf.ProducerEvent;
import com.salesforce.eventbus.protobuf.PubSubGrpc;
import com.salesforce.eventbus.protobuf.ReplayPreset;
import com.salesforce.eventbus.protobuf.SchemaRequest;
import io.github.albert.returnsync.auth.JwtBearerTokenService;
import io.github.albert.returnsync.config.SalesforceProperties;
import io.github.albert.returnsync.processing.ReturnEvent;
import io.github.albert.returnsync.processing.ReturnProcessor;
import io.github.albert.returnsync.store.ReturnStore;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.apache.avro.AvroRuntimeException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DecoderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Pub/Sub API {@code Subscribe} client, modelled on the official Java Quick Start
 * (forcedotcom/pub-sub-api, java/src/main/java/genericpubsub/Subscribe.java).
 * <p>
 * gRPC calls {@code onNext} on its own network thread, which must never block. All work (decoding, REST,
 * DB, sending the next FetchRequest) is handed to one {@code worker} thread. One thread gives strict
 * ordering and removes the thread-safety question: the request {@link StreamObserver} is not thread-safe
 * and only the worker touches it. A {@code generation} counter makes callbacks of an old, cancelled
 * stream harmless, so they cannot trigger a second reconnect.
 */
@Component
public class PubSubSubscriber implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PubSubSubscriber.class);
    static final Metadata.Key<String> ERROR_CODE =
            Metadata.Key.of("error-code", Metadata.ASCII_STRING_MARSHALLER);
    /** e.g. sfdc.platform.eventbus.grpc.service.auth.error */
    static final String AUTH_ERROR = "auth.error";
    /** fetch.replayid.corrupted / fetch.replayid.validation.failed (replay ID older than 72 h or damaged). */
    static final String REPLAY_ID_ERROR = "replayid";

    private final SalesforceProperties props;
    private final JwtBearerTokenService tokens;
    private final ReturnProcessor processor;
    private final ReturnStore store;
    private final Supplier<ManagedChannel> channelFactory;
    private final long baseReconnectDelayMs;

    private final ScheduledExecutorService worker = newWorker();
    private final Map<String, Schema> schemaCache = new ConcurrentHashMap<>();

    private ManagedChannel channel;
    private PubSubGrpc.PubSubStub asyncStub;
    private PubSubGrpc.PubSubBlockingStub blockingStub;

    // Only the worker thread touches the fields below
    private StreamObserver<FetchRequest> requestStream;
    private long generation;
    private int attempt;
    private boolean forceEarliest;
    private volatile boolean running;

    @Autowired
    public PubSubSubscriber(SalesforceProperties props, JwtBearerTokenService tokens,
                            ReturnProcessor processor, ReturnStore store) {
        this(props, tokens, processor, store,
                () -> ManagedChannelBuilder.forAddress(props.pubsubHost(), props.pubsubPort())
                        .useTransportSecurity()                       // TLS
                        .build(),
                1_000);
    }

    /** For tests: an in-process channel instead of host/port, and a short reconnect delay. */
    PubSubSubscriber(SalesforceProperties props, JwtBearerTokenService tokens, ReturnProcessor processor,
                     ReturnStore store, Supplier<ManagedChannel> channelFactory, long baseReconnectDelayMs) {
        this.props = props;
        this.tokens = tokens;
        this.processor = processor;
        this.store = store;
        this.channelFactory = channelFactory;
        this.baseReconnectDelayMs = baseReconnectDelayMs;
    }

    @Override
    public void start() {
        channel = channelFactory.get();
        SalesforceCallCredentials creds = new SalesforceCallCredentials(tokens);
        asyncStub = PubSubGrpc.newStub(channel).withCallCredentials(creds);
        blockingStub = PubSubGrpc.newBlockingStub(channel).withCallCredentials(creds);
        running = true;
        post(this::subscribe);
    }

    /** Open a stream: CUSTOM from the stored replay ID, else LATEST (or EARLIEST after a replay ID error). */
    private void subscribe() {
        if (!running) return;
        long gen = ++generation;
        try {
            FetchRequest.Builder first = FetchRequest.newBuilder()
                    .setTopicName(props.topic())
                    .setNumRequested(props.batchSize());              // <= 100, enforced in SalesforceProperties
            Optional<ByteString> stored = store.loadReplayId(props.topic());
            if (forceEarliest) {
                first.setReplayPreset(ReplayPreset.EARLIEST);
                forceEarliest = false;
            } else if (stored.isPresent()) {
                first.setReplayPreset(ReplayPreset.CUSTOM).setReplayId(stored.get());
            } else {
                first.setReplayPreset(ReplayPreset.LATEST);
            }
            requestStream = asyncStub.subscribe(new ResponseObserver(gen));
            requestStream.onNext(first.build());
            log.info("Subscribed to {} with preset {}", props.topic(), first.getReplayPreset());
        } catch (Exception e) {
            log.warn("Cannot open subscription", e);
            reconnectLater();
        }
    }

    /** gRPC callbacks: do nothing on the network thread, just hand over to the worker. */
    private final class ResponseObserver implements StreamObserver<FetchResponse> {
        private final long gen;
        ResponseObserver(long gen) { this.gen = gen; }

        @Override public void onNext(FetchResponse r) { post(() -> { if (gen == generation) handle(r); }); }
        @Override public void onError(Throwable t)    { post(() -> { if (gen == generation) handleError(t); }); }
        @Override public void onCompleted()           { post(() -> { if (gen == generation) reconnectLater(); }); }
    }

    private void handle(FetchResponse response) {
        try {
            if (response.getEventsCount() == 0 && !response.getLatestReplayId().isEmpty()) {
                // keepalive: store the fresh replay ID, as the Subscribe RPC docs recommend
                store.saveReplayId(props.topic(), response.getLatestReplayId());
            }
            if (response.getPendingNumRequested() == 0) {
                // Must send a new FetchRequest within 60 s. Ask before processing (the official sample
                // notes this for slow processing): the deadline then doesn't depend on REST latency, and
                // at most one extra batch waits in the worker queue.
                requestStream.onNext(FetchRequest.newBuilder()
                        .setTopicName(props.topic())
                        .setNumRequested(props.batchSize())
                        .build());
            }
            for (ConsumerEvent ce : response.getEventsList()) {
                ReturnEvent event;
                try {
                    event = ReturnEvent.from(decode(ce.getEvent()), ce.getEvent().getId());
                } catch (IOException | AvroRuntimeException | ClassCastException e) {
                    // poison message: it will never decode, so retrying would block the stream forever
                    log.error("Undecodable event {}, sending to dead letters", ce.getEvent().getId(), e);
                    store.deadLetterAndSaveReplay(new ReturnEvent(null, null, null, ce.getEvent().getId()),
                            "undecodable event: " + e, props.topic(), ce.getReplayId());
                    continue;
                }
                processor.process(event, ce.getReplayId());
            }
            attempt = 0;
        } catch (Exception e) {
            // transient error after retries: replay ID was not advanced, events come again after restart
            log.warn("Batch failed, restarting stream from last saved replay ID", e);
            cancelStream("client restart");
            reconnectLater();
        }
    }

    private GenericRecord decode(ProducerEvent event) throws IOException {
        Schema schema = schemaCache.computeIfAbsent(event.getSchemaId(), id ->
                new Schema.Parser().parse(blockingStub
                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                        .getSchema(SchemaRequest.newBuilder().setSchemaId(id).build())
                        .getSchemaJson()));
        GenericDatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
        return reader.read(null, DecoderFactory.get().binaryDecoder(event.getPayload().toByteArray(), null));
    }

    private void handleError(Throwable t) {
        Status status = Status.fromThrowable(t);
        Metadata trailers = Status.trailersFromThrowable(t);
        String code = trailers == null ? null : trailers.get(ERROR_CODE);
        log.warn("Subscribe stream error: status={}, error-code={}", status, code);
        if ((code != null && code.contains(AUTH_ERROR)) || status.getCode() == Status.Code.UNAUTHENTICATED) {
            tokens.invalidate(tokens.peek());
        }
        if (code != null && code.contains(REPLAY_ID_ERROR)) {
            forceEarliest = true;   // replay ID older than 72 h or damaged: idempotency drops the duplicates
        }
        reconnectLater();
    }

    private void reconnectLater() {
        if (!running) return;
        generation++;                                         // the old stream is not listened to anymore
        long delayMs = Math.min(60_000, baseReconnectDelayMs << Math.min(attempt++, 6));
        delayMs += ThreadLocalRandom.current().nextLong(delayMs / 4 + 1);   // jitter
        log.info("Reconnecting in {} ms", delayMs);
        try {
            worker.schedule(this::subscribe, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("Worker stopped, not reconnecting");
        }
    }

    private void cancelStream(String reason) {
        if (requestStream == null) return;
        try {
            requestStream.onError(Status.CANCELLED.withDescription(reason).asRuntimeException());
        } catch (RuntimeException e) {
            log.debug("Stream already closed", e);
        }
    }

    private void post(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            log.debug("Worker stopped, dropping callback");
        }
    }

    @Override
    public void stop() {
        running = false;
        // Queued after the batch in progress: that batch finishes (result + replay ID committed), then the
        // stream is cancelled. Half-closing instead would leave the server stream open until channel timeout.
        post(() -> cancelStream("shutdown"));
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) worker.shutdownNow();
            if (channel != null) {
                channel.shutdown();
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (channel != null) channel.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static ScheduledExecutorService newWorker() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> new Thread(r, "pubsub-worker"));
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);   // drop a pending reconnect on stop
        return executor;
    }
}
