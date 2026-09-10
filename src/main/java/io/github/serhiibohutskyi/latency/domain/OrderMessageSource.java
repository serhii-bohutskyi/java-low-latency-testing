package io.github.serhiibohutskyi.latency.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A pre-encoded, cyclic supply of order messages, generated once and then replayed with
 * zero allocation.
 *
 * <p>Replaying a <i>single</i> fixed message would be simpler, and it would be wrong for
 * two reasons that the article names directly:
 *
 * <ul>
 *   <li><b>Profile pollution.</b> If the warm-up drives only one branch, C2 compiles a
 *       version specialised to that profile, and the first message taking the other path
 *       triggers an uncommon trap and deoptimisation. A benchmark fed one message shape
 *       hands you a false green. Here roughly 3% of messages are fat-finger rejects, so
 *       both sides of the risk branch stay warm.</li>
 *   <li><b>Unbounded state.</b> {@link RiskEngine} keeps a running net position. A fixed
 *       BUY message would walk that position into its limit after ~10k messages, after
 *       which every message is rejected and the benchmark quietly stops measuring the
 *       accept path. Sides alternate here, so the net position oscillates near zero.</li>
 * </ul>
 *
 * <p>The messages live back to back in one direct buffer, which also means the read
 * pattern walks memory rather than hitting the same cache line every time -- closer to a
 * real receive buffer, and it will not flatter the numbers.
 */
public final class OrderMessageSource {

    /** Number of distinct messages in the cycle. A power of two so the wrap is a mask. */
    public static final int CYCLE = 1024;

    private static final int MASK = CYCLE - 1;

    private final ByteBuffer messages;
    private int index;

    public OrderMessageSource() {
        this(20250910L);
    }

    public OrderMessageSource(long seed) {
        this.messages = ByteBuffer
                .allocateDirect(CYCLE * OrderEncoder.MESSAGE_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);

        // xorshift, so generation itself is deterministic and dependency-free
        long state = seed;
        OrderEncoder encoder = new OrderEncoder();

        for (int i = 0; i < CYCLE; i++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;

            long r = state >>> 1;

            // price walks around 185.25 in micros, +/- ~0.5
            long priceMicros = 185_250_000L + (r % 500_000L) - 250_000L;

            // ~3% fat-finger quantities, which RiskEngine rejects
            int quantity = (i % 32 == 7)
                    ? 50_000 + (int) (r % 1000)
                    : 100 + (int) (r % 400);

            byte side = (i & 1) == 0 ? Side.BUY : Side.SELL;

            encoder.encode(messages, 1_000_000L + i, priceMicros, quantity, side);
        }

        messages.clear();
    }

    /**
     * Positions the shared buffer at the next message and returns it, ready to decode.
     * Allocation-free; the returned buffer is the same instance every time.
     */
    public ByteBuffer next() {
        int i = index++ & MASK;
        messages.position(i * OrderEncoder.MESSAGE_SIZE);
        return messages;
    }

    /** Rewinds the cycle so repeated runs see identical input. */
    public void reset() {
        index = 0;
    }
}
