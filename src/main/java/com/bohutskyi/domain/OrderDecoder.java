package com.bohutskyi.domain;

import java.nio.ByteBuffer;

/**
 * Decodes the wire format written by {@link OrderEncoder} into a reusable
 * {@link MutableOrder}.
 *
 * <p>Decoding into a caller-supplied flyweight rather than returning a new object is the
 * single most important allocation decision in this pipeline: returning an
 * {@code Order} here would allocate 32 bytes per message, which at 200k msg/s is
 * 6.4 MB/s -- enough to schedule a young collection on a small Eden every 40 seconds.
 */
public final class OrderDecoder {

    public void decode(ByteBuffer buffer, MutableOrder into) {
        into.orderId(buffer.getLong())
                .priceMicros(buffer.getLong())
                .quantity(buffer.getInt())
                .side(buffer.get());
    }
}
