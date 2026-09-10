package io.github.serhiibohutskyi.latency.jmh;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Every measurement in the article rests on {@code System.nanoTime()}, so measure the
 * ruler before trusting anything measured with it.
 *
 * <p>Two numbers come out of this:
 *
 * <ul>
 *   <li>{@link #nanoTime()} -- the cost of one call. On Linux with a TSC clocksource this
 *       is a vDSO read of roughly 20-30 ns. If the clocksource has fallen back to
 *       {@code hpet} or {@code xen}, the same call costs hundreds of nanoseconds and may
 *       trap into the kernel. On Windows it goes through {@code QueryPerformanceCounter}.</li>
 *   <li>{@link #nanoTimePair()} -- two calls back to back, which is the shape of every
 *       stage probe. This is the per-probe overhead you are paying, and it sits
 *       <i>inside</i> the window your outer timer reports.</li>
 * </ul>
 *
 * <p>This is the concrete justification for choosing {@code AverageTime} over
 * {@code SampleTime} in {@link OrderEncoderBenchmark}: if the operation costs less than
 * the clock, the percentiles are a picture of the clock.
 *
 * <p>See also the {@code clock} command, which measures the clock's <i>resolution</i>
 * (the smallest non-zero delta it can report) rather than its cost.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 2)
@State(Scope.Thread)
public class NanoTimeBenchmark {

    @Benchmark
    public long nanoTime() {
        return System.nanoTime();
    }

    /** The cost of one stage probe: read the clock, do nothing, read it again. */
    @Benchmark
    public long nanoTimePair() {
        long t0 = System.nanoTime();
        long t1 = System.nanoTime();
        return t1 - t0;
    }

    @Benchmark
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
