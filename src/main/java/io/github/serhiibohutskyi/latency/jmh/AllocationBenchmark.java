package io.github.serhiibohutskyi.latency.jmh;

import io.github.serhiibohutskyi.latency.domain.Order;
import io.github.serhiibohutskyi.latency.domain.OrderEncoder;
import io.github.serhiibohutskyi.latency.domain.Side;
import org.openjdk.jmh.annotations.*;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

/**
 * Run this one with {@code -prof gc} and read a single column:
 * <b>{@code gc.alloc.rate.norm} -- bytes allocated per operation.</b>
 *
 * <p>Unlike an allocation <i>rate</i>, that number is deterministic, which is what makes it
 * the right thing to assert on in CI. Each benchmark below is the same logical encode with
 * one realistic regression bolted on:
 *
 * <table border="1">
 *   <caption>Measured on JDK 21 (Temurin 21.0.9, G1). Your JIT may differ.</caption>
 *   <tr><th>Benchmark</th><th>Regression</th><th>alloc/op</th></tr>
 *   <tr><td>{@link #zeroAlloc()}</td><td>none</td><td>0 B</td></tr>
 *   <tr><td>{@link #allocatesRecord()}</td><td>a new {@code Order} per message</td><td><b>0 B</b> - scalar replaced</td></tr>
 *   <tr><td>{@link #usesBigDecimal()}</td><td>{@code new BigDecimal(...)} + two moves</td><td><b>0 B</b> - scalar replaced</td></tr>
 *   <tr><td>{@link #autoboxes()}</td><td>a {@code Long} outside the cache</td><td>24 B</td></tr>
 *   <tr><td>{@link #buildsString()}</td><td>{@code String.valueOf} + concat</td><td>72 B</td></tr>
 * </table>
 *
 * <p><b>Three of the five "obvious" regressions above allocate nothing, and that is the
 * most useful thing in this class.</b> The {@code new Order(...)} and the
 * {@code new BigDecimal(...)} never escape the benchmark method, so escape analysis proves
 * they cannot be observed and scalar replacement removes them entirely. The profiler then
 * honestly reports 0 B for code that plainly contains a {@code new}.
 *
 * <p>That is not a bug in the tool. It is the reason "read the source and count the
 * allocations" is not a substitute for measuring - and equally, the reason a green
 * {@code gc.alloc.rate.norm} in a microbenchmark does not prove the same code allocates
 * nothing in production, where the object may well escape into a queue, a log line or a
 * callback. Measure the shape you actually ship.
 *
 * <p>The two that survive are the two that genuinely escape: the boxed {@code Long} is
 * passed to a method, and the {@code String} outlives its construction. Those are the
 * regressions a CI assertion on this number would catch.
 *
 * <p>Turn {@code gc.alloc.rate.norm} into a prediction with the article's arithmetic:
 * <blockquote>young-GC interval &asymp; Eden size / allocation rate</blockquote>
 * 32 B/op at 200k msg/s is 6.4 MB/s. Into a 256 MB Eden that is a young collection every
 * 40 seconds; into a 4 GB Eden, one every ten minutes.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
@State(Scope.Thread)
public class AllocationBenchmark {

    private final OrderEncoder encoder = new OrderEncoder();
    private ByteBuffer buffer;

    /** Non-constant so the JIT cannot fold the regressions away at compile time. */
    private long orderId;

    @Setup(Level.Trial)
    public void setUp() {
        buffer = ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
        orderId = System.nanoTime() & 0xFFFF;
    }

    @Benchmark
    public long zeroAlloc() {
        buffer.clear();
        encoder.encode(buffer, ++orderId, 185_250_000L, 100, Side.BUY);
        return buffer.getLong(0) ^ buffer.position();
    }

    @Benchmark
    public long allocatesRecord() {
        buffer.clear();
        Order order = new Order(++orderId, 185_250_000L, 100, Side.BUY);
        encoder.encode(buffer, order);
        return buffer.getLong(0) ^ buffer.position();
    }

    @Benchmark
    public long autoboxes() {
        buffer.clear();
        // outside the -128..127 Long cache, so this is a real allocation
        Long boxed = ++orderId + 100_000L;
        encoder.encode(buffer, boxed, 185_250_000L, 100, Side.BUY);
        return buffer.getLong(0) ^ boxed.intValue();
    }

    @Benchmark
    public long buildsString() {
        buffer.clear();
        encoder.encode(buffer, ++orderId, 185_250_000L, 100, Side.BUY);
        String audit = "order=" + String.valueOf(orderId) + ",side=" + Side.name(Side.BUY);
        return buffer.getLong(0) ^ audit.length();
    }

    @Benchmark
    public long usesBigDecimal() {
        buffer.clear();
        BigDecimal price = new BigDecimal(185_250_000L).movePointLeft(6);
        encoder.encode(buffer, ++orderId, price.movePointRight(6).longValue(), 100, Side.BUY);
        return buffer.getLong(0) ^ buffer.position();
    }
}
