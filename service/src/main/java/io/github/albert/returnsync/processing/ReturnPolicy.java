package io.github.albert.returnsync.processing;

import java.math.BigDecimal;

/** Minimal business rule: just enough to have three outcomes. */
final class ReturnPolicy {
    static final BigDecimal MANUAL_REVIEW_THRESHOLD = new BigDecimal("500");

    static String decide(ReturnEvent e) {
        if (e.orderNumber() == null || e.orderNumber().isBlank() || e.amount() == null) return "Rejected";
        if (e.amount().compareTo(MANUAL_REVIEW_THRESHOLD) > 0) return "Manual_Review";
        return "Approved";
    }

    private ReturnPolicy() {}
}
