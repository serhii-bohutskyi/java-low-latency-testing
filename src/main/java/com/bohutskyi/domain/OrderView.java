package com.bohutskyi.domain;

/**
 * Read-only view of an order.
 *
 * <p>Both the immutable {@link Order} record and the reusable {@link MutableOrder}
 * flyweight implement this, so the encoder can accept either without the hot path
 * having to allocate an {@code Order} per message.
 */
public interface OrderView {

    long orderId();

    /** Price in micros (fixed point). Never a {@code double}, never a {@code BigDecimal}. */
    long priceMicros();

    int quantity();

    /** {@code 1} = buy, {@code 2} = sell. See {@link Side}. */
    byte side();
}
