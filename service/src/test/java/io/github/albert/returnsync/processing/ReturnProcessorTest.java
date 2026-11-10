package io.github.albert.returnsync.processing;

import com.google.protobuf.ByteString;
import io.github.albert.returnsync.processing.ReturnProcessor.Incoming;
import io.github.albert.returnsync.salesforce.PermanentSalesforceException;
import io.github.albert.returnsync.salesforce.SalesforceRestClient;
import io.github.albert.returnsync.salesforce.SalesforceRestClient.StatusUpdate;
import io.github.albert.returnsync.salesforce.SalesforceRestClient.UpdateResult;
import io.github.albert.returnsync.salesforce.TransientSalesforceException;
import io.github.albert.returnsync.store.ReturnStore;
import io.github.albert.returnsync.store.ReturnStore.DeadLetter;
import io.github.albert.returnsync.store.ReturnStore.Processed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Business rule, idempotency, batching and error classification: no network, no DB. */
@ExtendWith(MockitoExtension.class)
class ReturnProcessorTest {

    private static final String TOPIC = "/event/Return_Requested__e";
    private static final ByteString REPLAY = ByteString.copyFromUtf8("r-last");

    @Mock ReturnStore store;
    @Mock SalesforceRestClient salesforce;
    ReturnProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ReturnProcessor(store, salesforce, TOPIC, Duration.ZERO);
        lenient().when(store.processedAmong(anyCollection())).thenReturn(Set.of());
        lenient().when(salesforce.updateStatuses(anyList())).thenAnswer(inv -> {
            List<StatusUpdate> updates = inv.getArgument(0);
            return updates.stream().map(u -> new UpdateResult(u.externalId(), true, null, null)).toList();
        });
    }

    private static ReturnEvent event(String id, String order, BigDecimal amount) {
        return new ReturnEvent(id, order, amount, "evt-" + id);
    }

    @SuppressWarnings("unchecked")
    private List<Processed> committedProcessed() {
        ArgumentCaptor<List<Processed>> c = ArgumentCaptor.forClass(List.class);
        verify(store).commitBatch(c.capture(), anyList(), eq(TOPIC), eq(REPLAY));
        return c.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<DeadLetter> committedDeadLetters() {
        ArgumentCaptor<List<DeadLetter>> c = ArgumentCaptor.forClass(List.class);
        verify(store).commitBatch(anyList(), c.capture(), eq(TOPIC), eq(REPLAY));
        return c.getValue();
    }

    @ParameterizedTest
    @CsvSource({
            "ORD-1, 100.00, Approved",
            "ORD-2, 500.00, Approved",
            "ORD-3, 500.01, Manual_Review",
            "'',    10.00,  Rejected"
    })
    void decidesStatusAndCommitsItWithTheReplayId(String order, BigDecimal amount, String expected) {
        processor.processBatch(List.of(Incoming.of(event("RR-1", order, amount))), REPLAY);

        verify(salesforce).updateStatuses(List.of(new StatusUpdate("RR-1", expected)));
        assertThat(committedProcessed()).extracting(Processed::status).containsExactly(expected);
    }

    @Test
    void missingAmountIsRejected() {
        processor.processBatch(List.of(Incoming.of(event("RR-1", "ORD-1", null))), REPLAY);

        verify(salesforce).updateStatuses(List.of(new StatusUpdate("RR-1", "Rejected")));
    }

    @Test
    void wholeBatchGoesToSalesforceInOneCallInOrder() {
        processor.processBatch(List.of(
                Incoming.of(event("RR-1", "ORD-1", BigDecimal.ONE)),
                Incoming.of(event("RR-2", "ORD-2", new BigDecimal("900"))),
                Incoming.of(event("RR-3", "ORD-3", BigDecimal.TEN))), REPLAY);

        verify(salesforce, times(1)).updateStatuses(List.of(
                new StatusUpdate("RR-1", "Approved"),
                new StatusUpdate("RR-2", "Manual_Review"),
                new StatusUpdate("RR-3", "Approved")));
        assertThat(committedProcessed()).extracting(p -> p.event().externalId()).containsExactly("RR-1", "RR-2", "RR-3");
    }

    @Test
    void alreadyProcessedAreSkippedButReplayAdvances() {
        when(store.processedAmong(anyCollection())).thenReturn(Set.of("RR-1"));

        processor.processBatch(List.of(
                Incoming.of(event("RR-1", "ORD-1", BigDecimal.TEN)),
                Incoming.of(event("RR-2", "ORD-2", BigDecimal.TEN))), REPLAY);

        verify(salesforce).updateStatuses(List.of(new StatusUpdate("RR-2", "Approved")));
        assertThat(committedProcessed()).extracting(p -> p.event().externalId()).containsExactly("RR-2");
    }

    @Test
    void allDuplicatesMeansNoSalesforceCallButReplayStillSaved() {
        when(store.processedAmong(anyCollection())).thenReturn(Set.of("RR-1"));

        processor.processBatch(List.of(Incoming.of(event("RR-1", "ORD-1", BigDecimal.TEN))), REPLAY);

        verifyNoInteractions(salesforce);
        verify(store).commitBatch(List.of(), List.of(), TOPIC, REPLAY);
    }

    @Test
    void duplicateWithinTheSameBatchIsSentOnce() {
        processor.processBatch(List.of(
                Incoming.of(event("RR-1", "ORD-1", BigDecimal.TEN)),
                Incoming.of(event("RR-1", "ORD-1", BigDecimal.TEN))), REPLAY);

        verify(salesforce).updateStatuses(List.of(new StatusUpdate("RR-1", "Approved")));
    }

    @Test
    void perRecordFailureGoesToDeadLettersOthersSucceed() {
        when(salesforce.updateStatuses(anyList())).thenReturn(List.of(
                new UpdateResult("RR-1", true, null, null),
                new UpdateResult("RR-404", false, "NOT_FOUND", "The requested resource does not exist")));

        processor.processBatch(List.of(
                Incoming.of(event("RR-1", "ORD-1", BigDecimal.TEN)),
                Incoming.of(event("RR-404", "ORD-2", BigDecimal.TEN))), REPLAY);

        assertThat(committedProcessed()).extracting(p -> p.event().externalId()).containsExactly("RR-1");
        assertThat(committedDeadLetters()).singleElement()
                .satisfies(d -> assertThat(d.reason()).contains("NOT_FOUND"))
                .satisfies(d -> assertThat(d.event().externalId()).isEqualTo("RR-404"));
    }

    @Test
    void poisonAndMissingExternalIdGoToDeadLettersInTheSameCommit() {
        processor.processBatch(List.of(
                Incoming.poison("evt-bad", "undecodable event: EOF"),
                Incoming.of(new ReturnEvent(null, "ORD-1", BigDecimal.TEN, "evt-no-id")),
                Incoming.of(event("RR-1", "ORD-1", BigDecimal.TEN))), REPLAY);

        assertThat(committedDeadLetters()).extracting(DeadLetter::reason)
                .containsExactly("undecodable event: EOF", "missing External_Id__c");
        assertThat(committedProcessed()).extracting(p -> p.event().externalId()).containsExactly("RR-1");
    }

    @Test
    void transientErrorIsRetriedThenSucceeds() {
        when(salesforce.updateStatuses(anyList()))
                .thenThrow(new TransientSalesforceException("503"))
                .thenThrow(new TransientSalesforceException("503"))
                .thenReturn(List.of(new UpdateResult("RR-1", true, null, null)));

        processor.processBatch(List.of(Incoming.of(event("RR-1", "ORD-1", BigDecimal.TEN))), REPLAY);

        verify(salesforce, times(3)).updateStatuses(anyList());
        assertThat(committedProcessed()).hasSize(1);
    }

    @Test
    void transientErrorAfterAllAttemptsPropagatesAndNothingIsSaved() {
        when(salesforce.updateStatuses(anyList())).thenThrow(new TransientSalesforceException("503"));

        assertThrows(TransientSalesforceException.class, () ->
                processor.processBatch(List.of(Incoming.of(event("RR-1", "ORD-1", BigDecimal.TEN))), REPLAY));

        verify(salesforce, times(ReturnProcessor.MAX_ATTEMPTS)).updateStatuses(anyList());
        verify(store, never()).commitBatch(any(), any(), any(), any());
        verify(store, never()).saveReplayId(any(), any());
    }

    @Test
    void permanentFailureOfTheWholeCallDeadLettersTheBatchWithoutRetry() {
        when(salesforce.updateStatuses(anyList())).thenThrow(new PermanentSalesforceException("Salesforce 400: bad"));

        processor.processBatch(List.of(
                Incoming.of(event("RR-1", "ORD-1", BigDecimal.TEN)),
                Incoming.of(event("RR-2", "ORD-2", BigDecimal.TEN))), REPLAY);

        verify(salesforce, times(1)).updateStatuses(anyList());
        assertThat(committedDeadLetters()).hasSize(2).allSatisfy(d -> assertThat(d.reason()).contains("400"));
        assertThat(committedProcessed()).isEmpty();
    }
}
