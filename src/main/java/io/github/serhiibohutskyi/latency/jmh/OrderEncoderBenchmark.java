package io.github.serhiibohutskyi.latency.jmh;

import io.github.serhiibohutskyi.latency.domain.OrderEncoder;
import io.github.serhiibohutskyi.latency.domain.Side;
import org.openjdk.jmh.annotations.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

/**
 * Level 1: isolating a hot path.
 *
 * <p>Five things here are load-bearing, and each one is doing a specific job:
 *
 * <ol>
 *   <li><b>Warm-up iterations</b> -- a cold JVM is a different machine. Classes are still
 *       loading and C2 has not yet compiled against a settled profile.</li>
 *   <li><b>Three forks</b> -- a single JVM can settle into one particular compilation and
 *       stay there. Three forks tell you whether you measured your code or one JVM's mood.
 *       {@code jvmArgsAppend} matters too: three forks running a heap and collector that
 *       production does not use are three consistent measurements of the wrong machine.</li>
 *   <li><b>The buffer is allocated in {@code @Setup}</b>, not in the benchmark method, or
 *       this would measure allocation and encoding together. {@code buffer.clear()} <i>is</i>
 *       inside the measured method deliberately: real code resets too.</li>
 *   <li><b>The returned value is derived from the bytes just written</b>, creating a data
 *       dependency the JIT cannot eliminate without folding the writes too. See
 *       {@link DeadCodeBenchmark} for what happens when you get this wrong.</li>
 *   <li><b>The parameter changes the generated code.</b> {@code @Param} on the quantity
 *       would be pointless -- {@code putInt(100)} and {@code putInt(10000)} cost the same.
 *       Direct versus heap {@link ByteBuffer} is a genuine and frequently surprising
 *       difference in exactly this kind of encoder.</li>
 * </ol>
 *
 * <p><b>Mode is {@code AverageTime}, not {@code SampleTime}</b>, even though this article
 * is about distributions. {@code SampleTime} produces percentiles by timestamping
 * individual invocations, and {@code System.nanoTime()} costs roughly 25 ns and cannot
 * resolve below about 30 ns (measure it on your box with {@link NanoTimeBenchmark}).
 * This encode is a handful of nanoseconds. At that scale the percentiles would describe
 * the clock, not the encoder.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"
})
@State(Scope.Thread)
public class OrderEncoderBenchmark {

    public enum BufferKind { DIRECT, HEAP }

    private final OrderEncoder encoder = new OrderEncoder();

    /**
     * A private field works here: JMH's generated harness sets {@code @Param} fields via
     * {@code getDeclaredField} + {@code setAccessible}, not by direct assignment. Made
     * public only so the field is visible to a reader skimming the generated code.
     */
    @Param({"DIRECT", "HEAP"})
    public BufferKind bufferKind;

    private ByteBuffer buffer;

    @Setup(Level.Trial)
    public void setUp() {
        buffer = bufferKind == BufferKind.DIRECT
                ? ByteBuffer.allocateDirect(64)
                : ByteBuffer.allocate(64);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    @Benchmark
    public long encode() {
        buffer.clear();

        encoder.encode(
                buffer,
                42L,
                185_250_000L,
                100,
                Side.BUY);

        // derived from the work, not a constant
        return buffer.getLong(0) ^ buffer.position();
    }
}
