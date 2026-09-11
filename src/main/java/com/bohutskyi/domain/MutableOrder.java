package com.bohutskyi.domain;

/**
 * Mutable order flyweight, reused for every message.
 *
 * <p>This is the shape the article's pipeline uses: the decoder writes into an instance
 * that was allocated once at construction time, so decoding a message allocates nothing.
 * That is what makes {@code gc.alloc.rate.norm == 0} achievable for the whole pipeline.
 */
public final class MutableOrder implements OrderView {

    private long orderId;
    private long priceMicros;
    private int quantity;
    private byte side;

    @Override
    public long orderId() {
        return orderId;
    }

    @Override
    public long priceMicros() {
        return priceMicros;
    }

    @Override
    public int quantity() {
        return quantity;
    }

    @Override
    public byte side() {
        return side;
    }

    public MutableOrder orderId(long orderId) {
        this.orderId = orderId;
        return this;
    }

    public MutableOrder priceMicros(long priceMicros) {
        this.priceMicros = priceMicros;
        return this;
    }

    public MutableOrder quantity(int quantity) {
        this.quantity = quantity;
        return this;
    }

    public MutableOrder side(byte side) {
        this.side = side;
        return this;
    }

    /** Deliberately not {@code toString()} on the hot path -- this allocates. */
    @Override
    public String toString() {
        return "Order{id=" + orderId
                + ", price=" + (priceMicros / 1_000_000.0)
                + ", qty=" + quantity
                + ", side=" + Side.name(side) + '}';
    }
}
