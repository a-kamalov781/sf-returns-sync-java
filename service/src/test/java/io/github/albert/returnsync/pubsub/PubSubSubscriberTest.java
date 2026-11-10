package io.github.albert.returnsync.pubsub;

import com.google.protobuf.ByteString;
import com.salesforce.eventbus.protobuf.ConsumerEvent;
import com.salesforce.eventbus.protobuf.FetchRequest;
import com.salesforce.eventbus.protobuf.FetchResponse;
import com.salesforce.eventbus.protobuf.ProducerEvent;
import com.salesforce.eventbus.protobuf.PubSubGrpc;
import com.salesforce.eventbus.protobuf.ReplayPreset;
import com.salesforce.eventbus.protobuf.SchemaInfo;
import com.salesforce.eventbus.protobuf.SchemaRequest;
import io.github.albert.returnsync.auth.JwtBearerTokenService;
import io.github.albert.returnsync.auth.SalesforceToken;
import io.github.albert.returnsync.config.SalesforceProperties;
import io.github.albert.returnsync.processing.ReturnEvent;
import io.github.albert.returnsync.processing.ReturnProcessor;
import io.github.albert.returnsync.processing.ReturnProcessor.Incoming;
import io.github.albert.returnsync.salesforce.TransientSalesforceException;
import io.github.albert.returnsync.store.ReturnStore;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Protocol rules of the Subscribe RPC against an in-process fake of the Pub/Sub API:
 * num_requested, replay presets, keepalive replay IDs, the next FetchRequest, error-code handling.
 */
class PubSubSubscriberTest {

    private static final String TOPIC = "/event/Return_Requested__e";
    private static final int BATCH = 25;
    private static final long WAIT_MS = 3_000;

    /** Same shape as a Salesforce platform event schema: nillable custom fields are unions with null. */
    private static final Schema SCHEMA = new Schema.Parser().parse("""
            {"type":"record","name":"Return_Requested__e","namespace":"com.sforce.eventbus","fields":[
              {"name":"CreatedDate","type":"long"},
              {"name":"CreatedById","type":"string"},
              {"name":"External_Id__c","type":["null","string"],"default":null},
              {"name":"Order_Number__c","type":["null","string"],"default":null},
              {"name":"Amount__c","type":["null","double"],"default":null}]}""");

    private final FakePubSub fake = new FakePubSub();
    private final BlockingQueue<Metadata> headers = new LinkedBlockingQueue<>();
    private final ReturnProcessor processor = mock(ReturnProcessor.class);
    private final ReturnStore store = mock(ReturnStore.class);
    private final JwtBearerTokenService tokens = mock(JwtBearerTokenService.class);
    private final SalesforceToken token = new SalesforceToken("tok-1", "https://acme.my.salesforce.com", "00D5g000000AbCdEAK");

    private Server server;
    private String channelName;
    private PubSubSubscriber subscriber;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        ServerInterceptor captureHeaders = new ServerInterceptor() {
            @Override
            public <Q, A> ServerCall.Listener<Q> interceptCall(ServerCall<Q, A> call, Metadata h,
                                                               ServerCallHandler<Q, A> next) {
                headers.add(h);
                return next.startCall(call, h);
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(fake, captureHeaders)).build().start();
        when(tokens.get()).thenReturn(token);
        when(tokens.peek()).thenReturn(token);
        when(store.loadReplayId(TOPIC)).thenReturn(Optional.empty());

        channelName = name;
        subscriber = newSubscriber(Duration.ofMinutes(1));
    }

    private PubSubSubscriber newSubscriber(Duration idleTimeout) {
        SalesforceProperties props = new SalesforceProperties("https://login.salesforce.com",
                "https://login.salesforce.com", "id", "user", Path.of("unused.pem"), "68.0",
                "unused", 0, TOPIC, BATCH);
        return new PubSubSubscriber(props, tokens, processor, store,
                () -> InProcessChannelBuilder.forName(channelName).directExecutor().build(), 10, idleTimeout);
    }

    @AfterEach
    void tearDown() {
        subscriber.stop();
        server.shutdownNow();
    }

    @Test
    void firstRequestIsLatestWithBatchSizeAndAuthHeaders() throws Exception {
        subscriber.start();

        FetchRequest first = nextRequest();
        assertThat(first.getTopicName()).isEqualTo(TOPIC);
        assertThat(first.getNumRequested()).isEqualTo(BATCH).isLessThanOrEqualTo(100);
        assertThat(first.getReplayPreset()).isEqualTo(ReplayPreset.LATEST);

        Metadata h = headers.poll(WAIT_MS, TimeUnit.MILLISECONDS);
        assertThat(h).isNotNull();
        assertThat(h.get(SalesforceCallCredentials.ACCESS_TOKEN)).isEqualTo("tok-1");
        assertThat(h.get(SalesforceCallCredentials.INSTANCE_URL)).isEqualTo("https://acme.my.salesforce.com");
        assertThat(h.get(SalesforceCallCredentials.TENANT_ID)).isEqualTo("00D5g000000AbCdEAK");
    }

    @Test
    void storedReplayIdResumesWithCustom() throws Exception {
        ByteString stored = ByteString.copyFrom(new byte[]{0, 0, 0, 0, 0, 1, (byte) 0xFE});
        when(store.loadReplayId(TOPIC)).thenReturn(Optional.of(stored));

        subscriber.start();

        FetchRequest first = nextRequest();
        assertThat(first.getReplayPreset()).isEqualTo(ReplayPreset.CUSTOM);
        assertThat(first.getReplayId()).isEqualTo(stored);
    }

    @Test
    void keepaliveSavesReplayIdAndAsksForMore() throws Exception {
        subscriber.start();
        nextRequest();
        ByteString latest = ByteString.copyFromUtf8("keepalive-1");

        fake.stream().onNext(FetchResponse.newBuilder().setLatestReplayId(latest).setPendingNumRequested(0).build());

        verify(store, timeout(WAIT_MS)).saveReplayId(TOPIC, latest);
        FetchRequest more = nextRequest();
        assertThat(more.getNumRequested()).isEqualTo(BATCH);
        assertThat(more.getTopicName()).isEqualTo(TOPIC);
    }

    @Test
    void eventIsDecodedAndProcessedWithItsReplayId() throws Exception {
        subscriber.start();
        nextRequest();
        ByteString replay = ByteString.copyFromUtf8("r-1");

        fake.stream().onNext(FetchResponse.newBuilder()
                .addEvents(event("evt-1", avro("RR-1", "ORD-1", 42.5), replay))
                .setLatestReplayId(replay)
                .setPendingNumRequested(BATCH - 1)        // server still owes events: no new request yet
                .build());

        verify(processor, timeout(WAIT_MS)).processBatch(
                List.of(Incoming.of(new ReturnEvent("RR-1", "ORD-1", BigDecimal.valueOf(42.5), "evt-1"))), replay);
        assertThat(fake.requests.poll(300, TimeUnit.MILLISECONDS)).isNull();
        verify(store, never()).saveReplayId(any(), any());    // not a keepalive: processor owns the replay ID
    }

    @Test
    void wholeFetchResponseIsOneBatchWithTheLastReplayId() throws Exception {
        subscriber.start();
        nextRequest();
        ByteString r1 = ByteString.copyFromUtf8("r-1"), r2 = ByteString.copyFromUtf8("r-2"), r3 = ByteString.copyFromUtf8("r-3");

        fake.stream().onNext(FetchResponse.newBuilder()
                .addEvents(event("evt-1", avro("RR-1", "ORD-1", 1.0), r1))
                .addEvents(event("evt-2", avro("RR-2", "ORD-2", 2.0), r2))
                .addEvents(event("evt-3", avro("RR-3", "ORD-3", 3.0), r3))
                .setPendingNumRequested(BATCH - 3).build());

        verify(processor, timeout(WAIT_MS)).processBatch(argThat(batch -> batch.size() == 3
                && batch.stream().map(i -> i.event().externalId()).toList().equals(List.of("RR-1", "RR-2", "RR-3"))), eq(r3));
        verify(processor, times(1)).processBatch(any(), any());
    }

    @Test
    void nextRequestIsSentBeforeSlowProcessingFinishes() throws Exception {
        doAnswer(inv -> { Thread.sleep(1_500); return null; }).when(processor).processBatch(any(), any());
        subscriber.start();
        nextRequest();
        ByteString replay = ByteString.copyFromUtf8("r-1");

        fake.stream().onNext(FetchResponse.newBuilder()
                .addEvents(event("evt-1", avro("RR-1", "ORD-1", 1.0), replay))
                .setPendingNumRequested(0).build());

        // arrives while the processor is still busy, so the 60 s rule does not depend on REST latency
        FetchRequest more = fake.requests.poll(1_000, TimeUnit.MILLISECONDS);
        assertThat(more).isNotNull();
        assertThat(more.getNumRequested()).isEqualTo(BATCH);
    }

    @Test
    void replayIdErrorResubscribesWithEarliest() throws Exception {
        when(store.loadReplayId(TOPIC)).thenReturn(Optional.of(ByteString.copyFromUtf8("too-old")));
        subscriber.start();
        assertThat(nextRequest().getReplayPreset()).isEqualTo(ReplayPreset.CUSTOM);

        fake.stream().onError(errorWithCode(Status.INVALID_ARGUMENT,
                "sfdc.platform.eventbus.grpc.subscription.fetch.replayid.corrupted"));

        FetchRequest retry = nextRequest();
        assertThat(retry.getReplayPreset()).isEqualTo(ReplayPreset.EARLIEST);
        assertThat(retry.getNumRequested()).isEqualTo(BATCH);
    }

    @Test
    void authErrorDropsTokenAndReconnects() throws Exception {
        subscriber.start();
        nextRequest();

        fake.stream().onError(errorWithCode(Status.UNAUTHENTICATED, "sfdc.platform.eventbus.grpc.service.auth.error"));

        verify(tokens, timeout(WAIT_MS)).invalidate(token);
        assertThat(nextRequest().getReplayPreset()).isEqualTo(ReplayPreset.LATEST);
    }

    @Test
    void undecodableEventGoesToDeadLetterAndStreamContinues() throws Exception {
        subscriber.start();
        nextRequest();
        ByteString replay = ByteString.copyFromUtf8("r-bad");

        fake.stream().onNext(FetchResponse.newBuilder()
                .addEvents(event("evt-bad", new byte[]{0x02}, replay))   // truncated Avro
                .setPendingNumRequested(0).build());

        // handed to the processor as a poison item, so it is dead-lettered in the same commit as its batch
        verify(processor, timeout(WAIT_MS)).processBatch(argThat(batch -> batch.size() == 1
                && batch.getFirst().poisonReason() != null
                && batch.getFirst().poisonReason().contains("undecodable")
                && "evt-bad".equals(batch.getFirst().event().eventId())), eq(replay));
        assertThat(nextRequest().getNumRequested()).isEqualTo(BATCH);   // same stream keeps going
        assertThat(fake.subscribeCalls).isEqualTo(1);                   // no restart for a poison message
    }

    @Test
    void transientFailureRestartsFromLastSavedReplayId() throws Exception {
        doThrow(new TransientSalesforceException("503")).when(processor).processBatch(any(), any());
        subscriber.start();
        nextRequest();
        ByteString saved = ByteString.copyFromUtf8("saved-before-failure");
        when(store.loadReplayId(TOPIC)).thenReturn(Optional.of(saved));

        fake.stream().onNext(FetchResponse.newBuilder()
                .addEvents(event("evt-1", avro("RR-1", "ORD-1", 1.0), ByteString.copyFromUtf8("r-1")))
                .setPendingNumRequested(BATCH - 1).build());

        FetchRequest resubscribe = nextRequest();
        assertThat(resubscribe.getReplayPreset()).isEqualTo(ReplayPreset.CUSTOM);
        assertThat(resubscribe.getReplayId()).isEqualTo(saved);
        assertThat(fake.subscribeCalls).isEqualTo(2);
    }

    @Test
    void silentStreamIsRestartedByTheIdleWatchdog() throws Exception {
        // Live finding: a half-open connection delivered nothing, no error, no onCompleted, for 47 minutes.
        subscriber.stop();                                       // the default one was never started
        subscriber = newSubscriber(Duration.ofMillis(300));
        ByteString stored = ByteString.copyFromUtf8("last-saved");
        when(store.loadReplayId(TOPIC)).thenReturn(Optional.of(stored));
        subscriber.start();
        nextRequest();                                           // server now stays silent

        FetchRequest resubscribe = nextRequest();
        assertThat(resubscribe.getReplayPreset()).isEqualTo(ReplayPreset.CUSTOM);
        assertThat(resubscribe.getReplayId()).isEqualTo(stored);
        assertThat(fake.subscribeCalls).isGreaterThanOrEqualTo(2);
    }

    @Test
    void keepalivesKeepTheWatchdogQuiet() throws Exception {
        subscriber.stop();                                       // the default one was never started
        subscriber = newSubscriber(Duration.ofMillis(400));
        subscriber.start();
        nextRequest();
        for (int i = 0; i < 6; i++) {                            // 6 x 150 ms = 900 ms > idle timeout
            fake.stream().onNext(FetchResponse.newBuilder().setLatestReplayId(ByteString.copyFromUtf8("k" + i))
                    .setPendingNumRequested(BATCH).build());
            Thread.sleep(150);
        }
        assertThat(fake.subscribeCalls).isEqualTo(1);
    }

    @Test
    void reconnectDelayGrowsAndNeverExceeds60Seconds() {
        assertThat(PubSubSubscriber.reconnectDelayMs(1_000, 0)).isBetween(1_000L, 1_250L);
        assertThat(PubSubSubscriber.reconnectDelayMs(1_000, 3)).isBetween(8_000L, 10_000L);
        for (int attempt = 0; attempt < 50; attempt++) {
            assertThat(PubSubSubscriber.reconnectDelayMs(1_000, attempt))
                    .isLessThanOrEqualTo(PubSubSubscriber.MAX_RECONNECT_DELAY_MS);
        }
    }

    // --- helpers ---

    private FetchRequest nextRequest() throws InterruptedException {
        FetchRequest r = fake.requests.poll(WAIT_MS, TimeUnit.MILLISECONDS);
        assertThat(r).as("FetchRequest within %d ms", WAIT_MS).isNotNull();
        return r;
    }

    private static ConsumerEvent event(String id, byte[] payload, ByteString replayId) {
        return ConsumerEvent.newBuilder()
                .setEvent(ProducerEvent.newBuilder().setId(id).setSchemaId("schema-1")
                        .setPayload(ByteString.copyFrom(payload)))
                .setReplayId(replayId)
                .build();
    }

    private static byte[] avro(String externalId, String order, double amount) throws Exception {
        GenericRecord rec = new GenericData.Record(SCHEMA);
        rec.put("CreatedDate", System.currentTimeMillis());
        rec.put("CreatedById", "0055g000000XyZaAAK");
        rec.put("External_Id__c", externalId);
        rec.put("Order_Number__c", order);
        rec.put("Amount__c", amount);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
        new GenericDatumWriter<GenericRecord>(SCHEMA).write(rec, enc);
        enc.flush();
        return out.toByteArray();
    }

    private static Throwable errorWithCode(Status status, String errorCode) {
        Metadata trailers = new Metadata();
        trailers.put(PubSubSubscriber.ERROR_CODE, errorCode);
        return status.withDescription(errorCode).asRuntimeException(trailers);
    }

    /** Fake Pub/Sub API: records every FetchRequest, exposes the latest server-side stream. */
    static final class FakePubSub extends PubSubGrpc.PubSubImplBase {
        final BlockingQueue<FetchRequest> requests = new LinkedBlockingQueue<>();
        volatile StreamObserver<FetchResponse> current;
        volatile int subscribeCalls;

        StreamObserver<FetchResponse> stream() { return current; }

        @Override
        public StreamObserver<FetchRequest> subscribe(StreamObserver<FetchResponse> responses) {
            current = responses;
            subscribeCalls++;
            return new StreamObserver<>() {
                @Override public void onNext(FetchRequest r) { requests.add(r); }
                @Override public void onError(Throwable t) { }
                @Override public void onCompleted() { }
            };
        }

        @Override
        public void getSchema(SchemaRequest request, StreamObserver<SchemaInfo> responses) {
            responses.onNext(SchemaInfo.newBuilder().setSchemaId(request.getSchemaId())
                    .setSchemaJson(SCHEMA.toString()).build());
            responses.onCompleted();
        }
    }
}
