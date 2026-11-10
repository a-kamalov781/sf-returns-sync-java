package io.github.albert.returnsync.processing;

import com.google.protobuf.ByteString;
import io.github.albert.returnsync.salesforce.PermanentSalesforceException;
import io.github.albert.returnsync.salesforce.SalesforceRestClient;
import io.github.albert.returnsync.salesforce.TransientSalesforceException;
import io.github.albert.returnsync.store.ReturnStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Business rule, idempotency and error classification: no network, no DB. */
@ExtendWith(MockitoExtension.class)
class ReturnProcessorTest {

    private static final String TOPIC = "/event/Return_Requested__e";
    private static final ByteString REPLAY = ByteString.copyFromUtf8("r1");

    @Mock ReturnStore store;
    @Mock SalesforceRestClient salesforce;
    ReturnProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ReturnProcessor(store, salesforce, TOPIC, Duration.ZERO);
    }

    @ParameterizedTest
    @CsvSource({
            "ORD-1, 100.00, Approved",
            "ORD-2, 500.00, Approved",
            "ORD-3, 500.01, Manual_Review",
            "'',    10.00,  Rejected"
    })
    void decidesStatusAndStoresResultWithReplay(String order, BigDecimal amount, String expected) {
        ReturnEvent e = new ReturnEvent("RR-1", order, amount, "evt-1");

        processor.process(e, REPLAY);

        verify(salesforce).updateStatus("RR-1", expected);
        verify(store).markProcessedAndSaveReplay(e, expected, TOPIC, REPLAY);
    }

    @Test
    void missingAmountIsRejected() {
        ReturnEvent e = new ReturnEvent("RR-1", "ORD-1", null, "evt-1");

        processor.process(e, REPLAY);

        verify(salesforce).updateStatus("RR-1", "Rejected");
    }

    @Test
    void duplicateIsSkippedButReplayAdvances() {
        when(store.isProcessed("RR-1")).thenReturn(true);

        processor.process(new ReturnEvent("RR-1", "ORD-1", BigDecimal.TEN, "evt-1"), REPLAY);

        verifyNoInteractions(salesforce);
        verify(store).saveReplayId(TOPIC, REPLAY);
    }

    @Test
    void transientErrorIsRetriedThenSucceeds() {
        doThrow(new TransientSalesforceException("503"))
                .doThrow(new TransientSalesforceException("503"))
                .doNothing()
                .when(salesforce).updateStatus(anyString(), anyString());

        processor.process(new ReturnEvent("RR-1", "ORD-1", BigDecimal.TEN, "evt-1"), REPLAY);

        verify(salesforce, times(3)).updateStatus("RR-1", "Approved");
        verify(store).markProcessedAndSaveReplay(any(), eq("Approved"), eq(TOPIC), eq(REPLAY));
    }

    @Test
    void transientErrorAfterAllAttemptsPropagatesAndReplayIsNotSaved() {
        doThrow(new TransientSalesforceException("503")).when(salesforce).updateStatus(anyString(), anyString());

        assertThrows(TransientSalesforceException.class, () ->
                processor.process(new ReturnEvent("RR-1", "ORD-1", BigDecimal.TEN, "evt-1"), REPLAY));

        verify(salesforce, times(ReturnProcessor.MAX_ATTEMPTS)).updateStatus(anyString(), anyString());
        verify(store, never()).saveReplayId(any(), any());
        verify(store, never()).markProcessedAndSaveReplay(any(), any(), any(), any());
    }

    @Test
    void permanentErrorGoesToDeadLetterWithoutRetry() {
        doThrow(new PermanentSalesforceException("404 NOT_FOUND")).when(salesforce).updateStatus(anyString(), anyString());

        processor.process(new ReturnEvent("RR-404", "ORD-1", BigDecimal.TEN, "evt-1"), REPLAY);

        verify(salesforce, times(1)).updateStatus(anyString(), anyString());
        verify(store).deadLetterAndSaveReplay(any(), contains("404"), eq(TOPIC), eq(REPLAY));
        verify(store, never()).markProcessedAndSaveReplay(any(), any(), any(), any());
    }
}
