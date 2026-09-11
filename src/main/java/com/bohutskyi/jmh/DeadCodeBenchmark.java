package com.bohutskyi.jmh;

import com.bohutskyi.domain.OrderEncoder;
import com.bohutskyi.domain.Side;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

/**
 * "Return the result" is advice people carry from examples where it is free to examples
 * where it is not. This benchmark makes the difference a number.
 *
 * <ul>
 *   <li>{@link #derivedReturn()} -- returns a value read back from the bytes just written.
 *       The JIT cannot drop the writes without folding the read too.</li>
 *   <li>{@link #constantReturn()} -- returns {@code buffer.position()}, which is always 21
 *       here. That is a constant, so it is no guard at all: the whole encode is eligible
 *       for elimination.</li>
 *   <li>{@link #blackholeConsumed(Blackhole)} -- the explicit form, for when the value is
 *       awkward to derive. This is what {@code Blackhole} is for.</li>
 * </ul>
 *
 * <p>Expect {@code constantReturn} to look implausibly fast. If it does not on your JDK,
 * that is informative too -- escape analysis and dead-code elimination are not contractual,
 * which is exactly why you should not rely on a constant return to protect a benchmark.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
@State(Scope.Thread)
public class DeadCodeBenchmark {

    private final OrderEncoder encoder = new OrderEncoder();
    private ByteBuffer buffer;

    @Setup(Level.Trial)
    public void setUp() {
        buffer = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Benchmark
    public long derivedReturn() {
        buffer.clear();
        encoder.encode(buffer, 42L, 185_250_000L, 100, Side.BUY);
        return buffer.getLong(0) ^ buffer.position();
    }

    @Benchmark
    public int constantReturn() {
        buffer.clear();
        encoder.encode(buffer, 42L, 185_250_000L, 100, Side.BUY);
        return buffer.position();   // always 21 -- a constant, and therefore no guard
    }

    @Benchmark
    public void blackholeConsumed(Blackhole bh) {
        buffer.clear();
        encoder.encode(buffer, 42L, 185_250_000L, 100, Side.BUY);
        bh.consume(buffer);
    }
}
