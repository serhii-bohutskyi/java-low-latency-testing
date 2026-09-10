package io.github.serhiibohutskyi.latency.hdr;

import org.HdrHistogram.Histogram;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Reporting helpers shared by every command.
 *
 * <p>One caveat is baked into the formatting here, because it is easy to print misleading
 * precision: <b>HdrHistogram buckets values.</b> {@code getValueAtPercentile()} returns the
 * highest equivalent value of the bucket it lands in, so at three significant digits every
 * number is accurate to about 0.1%. Printing {@code p99.9 = 410 ns} implies a precision the
 * data structure does not carry, so these tables round to a sane number of digits and print
 * the sample count next to the percentiles.
 *
 * <p>The sample count is not decoration. p99.99 needs on the order of a million samples
 * before it means anything, and even then only about a hundred observations sit above it.
 * {@code max} is a single observation: quote it, because it is often the most interesting
 * thing in a run, but never optimise against it. It will not reproduce.
 */
public final class Percentiles {

    /** Percentiles reported everywhere in this project. */
    public static final double[] REPORTED = {50, 90, 99, 99.9, 99.99};

    private Percentiles() {
    }

    public static String us(long nanos) {
        double micros = nanos / 1000.0;
        if (micros >= 1000) {
            return String.format(Locale.ROOT, "%,.1f ms", micros / 1000.0);
        }
        if (micros >= 10) {
            return String.format(Locale.ROOT, "%,.1f us", micros);
        }
        return String.format(Locale.ROOT, "%.2f us", micros);
    }

    /** The classic vertical block: what the article prints when it says "the distribution". */
    public static void printBlock(PrintStream out, String title, Histogram h) {
        out.println();
        out.println("  " + title);
        out.println("  " + "-".repeat(Math.max(title.length(), 44)));
        out.printf(Locale.ROOT, "  %-10s %s%n", "count", String.format(Locale.ROOT, "%,d", h.getTotalCount()));
        out.printf(Locale.ROOT, "  %-10s %s%n", "mean", us((long) h.getMean()));
        for (double p : REPORTED) {
            long v = h.getValueAtPercentile(p);
            // how many observations actually sit above this percentile -- the number that
            // decides whether the percentile means anything at all
            long above = Math.round(h.getTotalCount() * (1.0 - p / 100.0));
            out.printf(Locale.ROOT, "  %-10s %-12s (%,d samples above)%n",
                    "p" + trim(p), us(v), above);
        }
        out.printf(Locale.ROOT, "  %-10s %s   <- one observation. Quote it, never optimise against it.%n",
                "max", us(h.getMaxValue()));
    }

    /** Header for the sweep / comparison tables. */
    public static String tableHeader(String firstColumn) {
        return String.format(Locale.ROOT, "  %-22s %10s %10s %10s %10s %10s %12s",
                firstColumn, "p50", "p90", "p99", "p99.9", "p99.99", "max");
    }

    public static String tableRow(String label, Histogram h) {
        return String.format(Locale.ROOT, "  %-22s %10s %10s %10s %10s %10s %12s",
                label,
                us(h.getValueAtPercentile(50)),
                us(h.getValueAtPercentile(90)),
                us(h.getValueAtPercentile(99)),
                us(h.getValueAtPercentile(99.9)),
                us(h.getValueAtPercentile(99.99)),
                us(h.getMaxValue()));
    }

    public static String rule(int width) {
        return "  " + "-".repeat(width);
    }

    /**
     * Writes the format the standard HdrHistogram plotter at
     * {@code http://hdrhistogram.github.io/HdrHistogram/plotFiles.html} expects.
     *
     * <p>{@code outputValueUnitScalingRatio = 1000.0} converts the recorded nanoseconds to
     * microseconds, which is the unit the rest of this project reports in.
     */
    public static void writeHgrm(Histogram h, Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (PrintStream ps = new PrintStream(Files.newOutputStream(file), false, "UTF-8")) {
                h.outputPercentileDistribution(ps, 1000.0);   // ns -> us
            }
        } catch (Exception e) {
            System.err.println("  ! could not write " + file + ": " + e);
        }
    }

    private static String trim(double p) {
        return p == Math.rint(p)
                ? String.valueOf((long) p)
                : String.valueOf(p);
    }
}
