package io.github.serhiibohutskyi.latency.domain;

/**
 * Order side, kept as a {@code byte} on the wire.
 *
 * <p>The constants exist so call sites read well; the hot path passes the raw byte
 * so that no enum lookup, and no {@code values()} array clone, happens per message.
 */
public final class Side {

    public static final byte BUY = 1;
    public static final byte SELL = 2;

    private Side() {
    }

    public static String name(byte side) {
        return switch (side) {
            case BUY -> "BUY";
            case SELL -> "SELL";
            default -> "UNKNOWN(" + side + ")";
        };
    }
}
