package io.github.serhiibohutskyi.latency.domain;

/**
 * Immutable order. Convenient for tests and for JMH state; not used on the hot path,
 * where {@link MutableOrder} avoids the per-message allocation.
 */
public record Order(long orderId, long priceMicros, int quantity, byte side)
        implements OrderView {
}
