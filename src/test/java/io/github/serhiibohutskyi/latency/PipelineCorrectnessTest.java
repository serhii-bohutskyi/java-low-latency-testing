package io.github.serhiibohutskyi.latency;

import io.github.serhiibohutskyi.latency.domain.*;
import io.github.serhiibohutskyi.latency.hdr.LatencyRecorder;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A benchmark of broken code is worthless, so the pipeline gets tested like ordinary code.
 *
 * <p>These tests also pin the properties the benchmarks depend on: that the message source
 * exercises both risk branches, that the net position does not run away, and that the
 * encoded size really is the constant the dead-code benchmark relies on.
 */
class PipelineCorrectnessTest {

    @Test
    @DisplayName("encode then decode round-trips every field")
    void roundTrip() {
        OrderEncoder encoder = new OrderEncoder();
        OrderDecoder decoder = new OrderDecoder();

        ByteBuffer buffer = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
        Order original = new Order(987_654_321L, 185_250_000L, 1_234, Side.SELL);

        encoder.encode(buffer, original);
        assertEquals(OrderEncoder.MESSAGE_SIZE, buffer.position(),
                "encoded size is the constant DeadCodeBenchmark relies on");

        buffer.flip();
        MutableOrder decoded = new MutableOrder();
        decoder.decode(buffer, decoded);

        assertEquals(original.orderId(), decoded.orderId());
        assertEquals(original.priceMicros(), decoded.priceMicros());
        assertEquals(original.quantity(), decoded.quantity());
        assertEquals(original.side(), decoded.side());
    }

    @Test
    @DisplayName("direct and heap buffers produce identical bytes")
    void directAndHeapAgree() {
        OrderEncoder encoder = new OrderEncoder();

        ByteBuffer direct = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer heap = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);

        encoder.encode(direct, 42L, 185_250_000L, 100, Side.BUY);
        encoder.encode(heap, 42L, 185_250_000L, 100, Side.BUY);

        direct.flip();
        heap.flip();

        byte[] a = new byte[direct.remaining()];
        byte[] b = new byte[heap.remaining()];
        direct.get(a);
        heap.get(b);

        assertArrayEquals(a, b,
                "the @Param in OrderEncoderBenchmark must change performance, not behaviour");
    }

    @Test
    @DisplayName("risk engine rejects fat fingers and enforces the position limit")
    void riskChecks() {
        RiskEngine risk = new RiskEngine();

        assertTrue(risk.accept(new Order(1, 100_000L, 100, Side.BUY)));
        assertFalse(risk.accept(new Order(2, 100_000L, 0, Side.BUY)), "zero quantity");
        assertFalse(risk.accept(new Order(3, 100_000L, 999_999, Side.BUY)), "fat finger");
        assertFalse(risk.accept(new Order(4, 900_000_000_000L, 9_000, Side.BUY)), "notional");

        assertEquals(100, risk.netPosition());
        assertEquals(1, risk.acceptedCount());
        assertEquals(3, risk.rejectedCount());
    }

    @Test
    @DisplayName("message source exercises both risk branches and keeps the position bounded")
    void messageSourceProfile() {
        GatewayPipeline pipeline = new GatewayPipeline(64);

        for (int i = 0; i < 2_000; i++) {
            pipeline.processBatch();
        }

        RiskEngine risk = pipeline.riskEngine();
        long total = risk.acceptedCount() + risk.rejectedCount();

        assertTrue(risk.acceptedCount() > 0, "accept branch must be exercised");
        assertTrue(risk.rejectedCount() > 0, "reject branch must be exercised");

        double rejectRate = (double) risk.rejectedCount() / total;
        assertTrue(rejectRate > 0.01 && rejectRate < 0.20,
                "reject rate should be a realistic minority, was " + rejectRate);

        // If this drifts without bound, the benchmark silently stops measuring the accept
        // path partway through the run. That is the profile-pollution trap.
        assertTrue(Math.abs(risk.netPosition()) < 1_000_000,
                "net position must stay inside the limit, was " + risk.netPosition());
    }

    @Test
    @DisplayName("the staged path computes the same result as the interleaved one")
    void stagedMatchesInterleaved() {
        GatewayPipeline interleaved = new GatewayPipeline(64);
        GatewayPipeline staged = new GatewayPipeline(64);
        GatewayPipeline.StageTimes times = new GatewayPipeline.StageTimes();

        for (int i = 0; i < 500; i++) {
            interleaved.processBatch();
            staged.processBatchStaged(times);
        }

        assertEquals(interleaved.sink(), staged.sink(),
                "instrumented path must do the same work, only measured differently");
        assertEquals(interleaved.riskEngine().acceptedCount(),
                staged.riskEngine().acceptedCount());
    }

    @Test
    @DisplayName("recorder clamps rather than throwing, and reports the clamp")
    void recorderClamps() {
        LatencyRecorder recorder = new LatencyRecorder();

        recorder.record(1_000);
        recorder.record(-5);                 // must not throw
        recorder.record(999_999_999_999L);   // above the ceiling

        assertEquals(1, recorder.clampedCount(), "a saturated ceiling makes max fictional");

        Histogram h = recorder.takeInterval();
        assertEquals(3, h.getTotalCount());
    }

    @Test
    @DisplayName("merging histograms is not averaging percentiles")
    void mergeIsNotAverage() {
        Histogram good = new Histogram(3);
        Histogram bad = new Histogram(3);

        // 5% of the bad run is slow, so its own p99 sits deep in the slow branch.
        for (int i = 0; i < 100_000; i++) {
            good.recordValue(1_000);
            bad.recordValue(i < 95_000 ? 1_000 : 10_000_000);
        }

        long averaged = (good.getValueAtPercentile(99) + bad.getValueAtPercentile(99)) / 2;

        Histogram merged = new Histogram(3);
        merged.add(good);
        merged.add(bad);
        long actual = merged.getValueAtPercentile(99);

        // Averaging halves the tail, because the healthy run contributes a p99 of 1 us to
        // a mean that then gets reported as though it described the combined data.
        assertTrue(averaged < actual * 0.6,
                "averaging should badly understate the merged tail: averaged=" + averaged
                        + " merged=" + actual);
    }
}
