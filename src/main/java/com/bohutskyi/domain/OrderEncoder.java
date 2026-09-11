package com.bohutskyi.domain;

import java.nio.ByteBuffer;

/**
 * Encodes an order into a fixed-layout binary message.
 *
 * <p>Wire layout, little endian, 21 bytes:
 * <pre>
 *   offset  size  field
 *   0       8     orderId
 *   8       8     priceMicros
 *   16      4     quantity
 *   20      1     side
 * </pre>
 *
 * <p>Deliberately simplified: no length prefix, no checksum, no schema version. The point
 * is to have a hot path that is a handful of nanoseconds of real work, so that the
 * measurement problems in the article become visible rather than being drowned out.
 */
public final class OrderEncoder {

    /** Encoded size in bytes. {@code buffer.position()} is always this after an encode. */
    public static final int MESSAGE_SIZE = 21;

    public void encode(
            ByteBuffer buffer,
            long orderId,
            long priceMicros,
            int quantity,
            byte side) {

        buffer.putLong(orderId);
        buffer.putLong(priceMicros);
        buffer.putInt(quantity);
        buffer.put(side);
    }

    public void encode(ByteBuffer buffer, OrderView order) {
        encode(buffer,
                order.orderId(),
                order.priceMicros(),
                order.quantity(),
                order.side());
    }
}
