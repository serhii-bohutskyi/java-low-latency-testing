package com.bohutskyi.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The order gateway as one reusable unit of work: decode, risk check, encode.
 *
 * <pre>
 *   network message -&gt; decode -&gt; risk check -&gt; encode -&gt; exchange connection
 * </pre>
 *
 * <h2>Why this processes a batch rather than a single message</h2>
 *
 * <p>One message through this pipeline costs a few tens of nanoseconds. Run the
 * {@code clock} command and compare that against what the platform's clock can actually
 * resolve. On Windows, {@code System.nanoTime()} is backed by QueryPerformanceCounter at
 * about 10 MHz, so the smallest non-zero delta it can report is roughly 100 ns and around
 * 70% of back-to-back reads return the same value. On Linux with a TSC clocksource the
 * resolution is better, but a call still costs around 25 ns.
 *
 * <p>Either way, <b>a single message through this pipeline is smaller than the ruler.</b>
 * Timing one per iteration produces a column of zeroes and hundreds - a picture of the
 * clock, exactly the failure mode the article warns about when it explains why the JMH
 * benchmark uses {@code AverageTime} rather than {@code SampleTime}. The first run of this
 * project's JLBH workload reported a p50 of 0.001 us for precisely this reason.
 *
 * <p>So one scheduled arrival delivers a <i>burst</i> of {@code batchSize} messages, which
 * is also what a real market-data feed does. The measured unit is then comfortably above
 * the clock floor, and the latency being reported is the time to process a burst - which is
 * a meaningful quantity for a gateway, and an honest one to put a percentile on.
 *
 * <p>Set {@code -Dgateway.batch=1} to reproduce the degenerate case and see the effect for
 * yourself. That is worth doing once.
 */
public final class GatewayPipeline {

    /** Messages delivered per scheduled arrival. */
    public static final int DEFAULT_BATCH = Integer.getInteger("gateway.batch", 128);

    private final OrderDecoder decoder = new OrderDecoder();
    private final RiskEngine riskEngine = new RiskEngine();
    private final OrderEncoder encoder = new OrderEncoder();
    private final OrderMessageSource source = new OrderMessageSource();

    private final ByteBuffer output =
            ByteBuffer.allocateDirect(128).order(ByteOrder.LITTLE_ENDIAN);

    private final int batchSize;

    /** Pre-allocated flyweights, one per message in the burst. Never reallocated. */
    private final MutableOrder[] orders;
    private final boolean[] accepted;

    private long sink;

    public GatewayPipeline() {
        this(DEFAULT_BATCH);
    }

    public GatewayPipeline(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
        this.orders = new MutableOrder[this.batchSize];
        this.accepted = new boolean[this.batchSize];
        for (int i = 0; i < this.batchSize; i++) {
            orders[i] = new MutableOrder();
        }
    }

    public int batchSize() {
        return batchSize;
    }

    /**
     * Processes one burst, interleaved the way the real pipeline runs: each message goes
     * all the way through before the next one starts. This is the uninstrumented path, and
     * the one whose numbers are worth quoting.
     */
    public void processBatch() {
        for (int i = 0; i < batchSize; i++) {
            MutableOrder order = orders[i];
            decoder.decode(source.next(), order);

            if (riskEngine.accept(order)) {
                output.clear();
                encoder.encode(output, order);
                sink ^= output.getLong(0);
            }
        }
    }

    /**
     * Processes one burst as three separate passes, so a single timestamp can be taken at
     * each stage boundary for the whole burst.
     *
     * <p>Four {@code nanoTime()} calls per burst is about 116 ns of instrumentation on this
     * machine, against roughly a microsecond of work - a perfectly good trade for knowing
     * where a regression lives. Timing each stage of each message instead would need 128
     * calls per burst, which would cost several times the work being measured.
     *
     * <p>But note what had to change to make that trade: the instrumented path is
     * <b>restructured</b>, from one interleaved pass into three passes over the burst. That
     * changes the memory access pattern and the branch pattern, so it is not merely the
     * uninstrumented pipeline plus some timestamps. It is a second, closely related program.
     * This is a concrete reason - beyond the cost of the clock calls - not to quote numbers
     * from the instrumented run, and to use it only to answer "which stage moved".
     */
    public void processBatchStaged(StageTimes into) {
        long t0 = System.nanoTime();

        for (int i = 0; i < batchSize; i++) {
            decoder.decode(source.next(), orders[i]);
        }

        long t1 = System.nanoTime();

        for (int i = 0; i < batchSize; i++) {
            accepted[i] = riskEngine.accept(orders[i]);
        }

        long t2 = System.nanoTime();

        for (int i = 0; i < batchSize; i++) {
            if (accepted[i]) {
                output.clear();
                encoder.encode(output, orders[i]);
                sink ^= output.getLong(0);
            }
        }

        long t3 = System.nanoTime();

        into.decodeNs = t1 - t0;
        into.riskNs = t2 - t1;
        into.encodeNs = t3 - t2;
    }

    /** Simple carrier so the staged call allocates nothing. */
    public static final class StageTimes {
        public long decodeNs;
        public long riskNs;
        public long encodeNs;
    }

    public RiskEngine riskEngine() {
        return riskEngine;
    }

    public void resetCounters() {
        riskEngine.reset();
        source.reset();
    }

    /** Referenced so the JIT cannot eliminate the encode. */
    public long sink() {
        return sink;
    }
}
