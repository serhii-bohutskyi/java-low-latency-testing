package com.bohutskyi.platform;

import com.bohutskyi.hdr.Percentiles;
import org.HdrHistogram.Histogram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Measure the ruler before trusting anything measured with it.
 *
 * <p>Every number in this project comes out of {@code System.nanoTime()}, so two of its
 * properties decide what is measurable at all:
 *
 * <ul>
 *   <li><b>Cost</b> - how long a call takes. On Linux this is a cheap vDSO read only when
 *       the clocksource is TSC and the CPU reports {@code constant_tsc} / {@code nonstop_tsc}.
 *       If the clocksource has fallen back to {@code hpet} or {@code xen}, the same call
 *       costs hundreds of nanoseconds and may trap into the kernel.</li>
 *   <li><b>Resolution</b> - the smallest non-zero difference it can report. If that is 100 ns,
 *       then nothing costing 30 ns is measurable one call at a time, no matter how many
 *       samples you take. This is the concrete reason the article amortises with
 *       {@code AverageTime} instead of sampling percentiles at nanosecond scale.</li>
 * </ul>
 *
 * <p>On Linux this also reads the current clocksource directly, because that single file is
 * the difference between a benchmark you can believe and one you cannot.
 */
public final class ClockProbe {

    private static final int SAMPLES = 2_000_000;

    public void run() {
        System.out.println("  Probing System.nanoTime() itself.");
        System.out.println();
        HiccupMeter.printEnvironment();
        System.out.println();

        reportClockSource();

        // Warm up: the first calls go through the interpreter and would dominate.
        for (int i = 0; i < 500_000; i++) {
            blackhole += System.nanoTime();
        }

        long cost = measureCost();
        long resolution = measureResolution();

        System.out.println();
        System.out.println("  Results");
        System.out.println(Percentiles.rule(72));
        System.out.printf(Locale.ROOT, "  %-34s %,d ns%n", "cost of one nanoTime() call", cost);
        System.out.printf(Locale.ROOT, "  %-34s %,d ns%n", "smallest non-zero delta", resolution);
        System.out.printf(Locale.ROOT, "  %-34s %,d ns%n", "cost of a stage probe (2 calls)", cost * 2);

        System.out.println();
        System.out.println("  What follows from this");
        System.out.println(Percentiles.rule(72));
        System.out.printf(Locale.ROOT,
                "  An operation costing less than about %,d ns cannot be timed one call at a%n",
                Math.max(cost, resolution));
        System.out.println("  time. Sampling its percentiles would describe this clock, not the code.");
        System.out.println("  That is why the encoder benchmark uses AverageTime, not SampleTime, and");
        System.out.println("  why the distribution comes from the level above instead.");
        System.out.println();
        System.out.printf(Locale.ROOT,
                "  Four probes per message, as in the instrumented JLBH task, costs about %,d ns,%n",
                cost * 4);
        System.out.println("  and those timestamps sit INSIDE the window the harness reports. At");
        System.out.printf(Locale.ROOT,
                "  200,000 msg/s that is roughly %,.1f ms of CPU per second spent reading the clock.%n",
                (cost * 4 * 200_000L) / 1e6);
        System.out.println("  Fine for locating a regression. Not a number to quote.");

        System.out.println("  (sink=" + blackhole + ")");
    }

    private long blackhole;

    /**
     * Amortised cost: time a long run of calls and divide. Timing a single call with the
     * same clock would be circular.
     */
    private long measureCost() {
        long start = System.nanoTime();
        long acc = 0;
        for (int i = 0; i < SAMPLES; i++) {
            acc += System.nanoTime();
        }
        long elapsed = System.nanoTime() - start;
        blackhole += acc;
        return elapsed / SAMPLES;
    }

    /**
     * The smallest non-zero delta between two consecutive reads. Also records the full
     * distribution of deltas, because a clock that mostly reports 0 and occasionally jumps
     * is telling you its resolution is coarser than the loop.
     */
    private long measureResolution() {
        Histogram deltas = new Histogram(3);
        long smallest = Long.MAX_VALUE;
        long previous = System.nanoTime();

        for (int i = 0; i < SAMPLES; i++) {
            long now = System.nanoTime();
            long delta = now - previous;
            previous = now;

            if (delta > 0) {
                deltas.recordValue(delta);
                if (delta < smallest) {
                    smallest = delta;
                }
            }
        }

        long zeroDeltas = SAMPLES - deltas.getTotalCount();
        System.out.printf(Locale.ROOT,
                "  %-34s %,d of %,d (%.1f%%)%n",
                "consecutive reads that were equal", zeroDeltas, SAMPLES,
                100.0 * zeroDeltas / SAMPLES);

        return smallest == Long.MAX_VALUE ? 0 : smallest;
    }

    private void reportClockSource() {
        Path current = Path.of("/sys/devices/system/clocksource/clocksource0/current_clocksource");
        if (!Files.exists(current)) {
            System.out.println("  clocksource            (Linux only; not readable on this OS)");
            System.out.println("                         On Linux, check:");
            System.out.println("                         cat /sys/devices/system/clocksource/clocksource0/current_clocksource");
            return;
        }
        try {
            String source = Files.readString(current).trim();
            System.out.printf(Locale.ROOT, "  %-22s %s%n", "clocksource", source);
            if (!"tsc".equals(source)) {
                System.out.println("  ! Not TSC. nanoTime() here may cost hundreds of ns and trap into the");
                System.out.println("  ! kernel. Do not trust microsecond-scale measurements on this box.");
            }
        } catch (IOException e) {
            System.out.println("  clocksource            (unreadable: " + e.getMessage() + ")");
        }
    }
}
