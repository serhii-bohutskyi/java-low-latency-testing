package io.github.serhiibohutskyi.latency.jlbh;

import io.github.serhiibohutskyi.latency.domain.GatewayPipeline;
import io.github.serhiibohutskyi.latency.hdr.Percentiles;
import net.openhft.chronicle.jlbh.JLBH;
import net.openhft.chronicle.jlbh.JLBHOptions;
import org.HdrHistogram.Histogram;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Throughput and latency have to be tested together.
 *
 * <p>A service showing p99 = 40 us at 50,000 msg/s tells you almost nothing about 100,000,
 * or 150,000, or 250,000. The reason has a shape: with utilisation
 * {@code rho = arrival rate / service rate}, expected queue length grows roughly as
 * {@code 1 / (1 - rho)}. That is a hyperbola, not a cliff, and the knee arrives well before
 * 100% utilisation - which is why "we're only at 70% CPU" is not the reassurance people
 * think it is.
 *
 * <p>Which is why this sweep is expressed in <b>utilisation</b>, not in round numbers.
 * Picking rates like 100k / 200k / 400k out of the air tells you nothing unless you already
 * know where capacity is: sweep below it and the tail never moves, sweep far above it and
 * every row is a divergent queue. So the run starts by measuring the component's service
 * rate closed-loop, then drives it open-loop at a series of fractions of that rate.
 *
 * <p>The 1/(1-rho) column is the theoretical queue-length multiplier at each utilisation.
 * Watching the measured p99.9 track it - and then overshoot it, because service time here is
 * variable rather than constant - is the whole argument of this section made visible:
 * <b>for the same average service time, a more variable service time produces a much longer
 * queue.</b> Queueing delay scales with the variability of arrivals and service, not just
 * their means. That is precisely why a component with a good p50 and a ragged p99 saturates
 * earlier than one that is slightly slower on average but tight.
 *
 * <p>The question this answers is not "how fast is this component" but <b>"at what rate does
 * the tail turn upward"</b> - and that rate, not a single p99 figure, is the number to report.
 */
public final class ThroughputSweep {

    /** Fractions of measured capacity to drive the component at. */
    private static final double[] UTILISATIONS =
            {0.10, 0.30, 0.50, 0.70, 0.80, 0.90, 0.95, 1.05};

    private ThroughputSweep() {
    }

    private record Row(double utilisation, int targetRate, double achievedRate,
                       long p50, long p99, long p999, long p9999, long max,
                       Histogram histogram) {

        double tailRatio() {
            return p50 == 0 ? Double.NaN : (double) p999 / p50;
        }

        boolean keptUp() {
            return achievedRate >= targetRate * 0.95;
        }

        /** The M/M/1 queue-length multiplier, for comparison against the measured tail. */
        double theoreticalMultiplier() {
            return utilisation >= 1.0 ? Double.POSITIVE_INFINITY : 1.0 / (1.0 - utilisation);
        }
    }

    public static void main(String[] args) {
        int runs = Integer.getInteger("sweep.runs", 3);
        double secondsPerRun = Double.parseDouble(System.getProperty("sweep.secondsPerRun", "2"));

        System.out.println("  Step 1 of 2: measuring the component's service rate closed-loop.");
        System.out.println("  (This is what JMH would report: service time at 100% utilisation of");
        System.out.println("  one thread. It is the denominator for every utilisation below.)");
        System.out.println();

        Capacity capacity = calibrate();

        System.out.printf(Locale.ROOT, "  burst size                 %d messages%n", capacity.batchSize());
        System.out.printf(Locale.ROOT, "  closed-loop service time   %s per burst%n",
                Percentiles.us(capacity.serviceTimeNs()));
        System.out.printf(Locale.ROOT, "  => service rate            %,d bursts/s   (what JMH would report)%n",
                capacity.serviceRate());
        System.out.printf(Locale.ROOT, "  open-loop sustainable rate %,d bursts/s   (what this harness can drive)%n",
                capacity.sustainableRate());
        System.out.printf(Locale.ROOT, "  => effective               %,d msg/s at saturation%n",
                (long) capacity.sustainableRate() * capacity.batchSize());
        System.out.printf(Locale.ROOT, "  harness overhead           %.0f%% of each iteration%n",
                100.0 * (1.0 - (double) capacity.sustainableRate() / capacity.serviceRate()));
        System.out.println();
        System.out.println("  The gap between those two rates is the harness itself: clock reads, schedule");
        System.out.println("  arithmetic, spin-waiting and sample recording. rho below is measured against");
        System.out.println("  the sustainable rate, because that is the rate a queue actually forms at.");
        System.out.println();
        System.out.println("  Step 2 of 2: driving it open-loop at fractions of that rate.");
        System.out.printf(Locale.ROOT, "  %d runs per rate, ~%.0f s per run.%n", runs, secondsPerRun);
        System.out.println();

        List<Row> rows = new ArrayList<>();

        for (double rho : UTILISATIONS) {
            int rate = (int) Math.max(1000, capacity.sustainableRate() * rho);
            int iterations = (int) Math.max(50_000, Math.min(2_000_000, rate * secondsPerRun));

            System.out.printf(Locale.ROOT,
                    "  [rho = %.2f] %,d bursts/s, %,d iterations x %d runs...%n",
                    rho, rate, iterations, runs);

            rows.add(runOne(rho, rate, iterations, runs));
        }

        printTable(rows, capacity);
    }

    // ------------------------------------------------------------------ calibration

    private record Capacity(long serviceTimeNs, int batchSize, int sustainableRate) {

        /** What a closed-loop measurement says the component alone could do. */
        int serviceRate() {
            return (int) (1_000_000_000L / Math.max(serviceTimeNs, 1));
        }
    }

    /**
     * Calibrates twice, because the two numbers are different and the difference matters.
     *
     * <p><b>Closed-loop service time</b> is what JMH would report: the component running
     * flat out with no arrival process. It is the honest measure of the code.
     *
     * <p><b>Open-loop sustainable rate</b> is what this harness can actually drive, measured
     * by aiming at a deliberately unreachable target and seeing what comes out. It is lower,
     * because every iteration also pays for the harness: reading the clock, computing the
     * next nominal arrival time, spinning until it arrives, and recording the sample.
     *
     * <p>The sweep uses the second number as its denominator. Using the first would label a
     * row "rho = 0.7" when the generator was already saturated, and every conclusion drawn
     * from the table would be wrong. This is the article's own warning turned into code:
     * at high rates the harness is doing real work per iteration, so suspect the generator
     * before concluding anything about the component.
     */
    private static Capacity calibrate() {
        GatewayPipeline pipeline = new GatewayPipeline();

        for (int i = 0; i < 300_000; i++) {
            pipeline.processBatch();
        }

        int iterations = 500_000;
        long best = Long.MAX_VALUE;

        // Three passes, keep the fastest: the fastest pass is the one least disturbed by
        // the scheduler, which is what "service time" should mean here.
        for (int pass = 0; pass < 3; pass++) {
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                pipeline.processBatch();
            }
            long elapsed = System.nanoTime() - start;
            best = Math.min(best, elapsed / iterations);
        }

        long serviceTimeNs = Math.max(best, 1);
        int sustainable = measureSustainableRate(serviceTimeNs);

        // Keep the JIT from folding the calibration loop away.
        if (pipeline.sink() == Long.MIN_VALUE) {
            System.out.println("unreachable " + pipeline.sink());
        }

        return new Capacity(serviceTimeNs, pipeline.batchSize(), sustainable);
    }

    /** Aims far above capacity and reports what the harness actually achieved. */
    private static int measureSustainableRate(long serviceTimeNs) {
        int unreachable = (int) Math.min(50_000_000L, 4_000_000_000L / serviceTimeNs);

        OrderGatewayLatencyTask task = new OrderGatewayLatencyTask().quiet();

        JLBHOptions options = new JLBHOptions()
                .warmUpIterations(200_000)
                .iterations(1_000_000)
                .throughput(unreachable)
                .runs(3)
                .recordOSJitter(false)
                .accountForCoordinatedOmission(true)
                .jlbhTask(task);

        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        new JLBH(options, sink, result -> { }).start();

        return (int) Math.max(1000, task.minAchievedRate());
    }

    // -------------------------------------------------------------------- one rate

    private static Row runOne(double rho, int rate, int iterations, int runs) {
        OrderGatewayLatencyTask task = new OrderGatewayLatencyTask().quiet();

        JLBHOptions options = new JLBHOptions()
                .warmUpIterations(Math.min(iterations, 200_000))
                .iterations(iterations)
                .throughput(rate)
                .runs(runs)
                .recordOSJitter(true)
                .accountForCoordinatedOmission(true)
                .jlbhTask(task);

        // JLBH prints a full percentile table per run. For a sweep that is thousands of
        // lines, so it goes into a buffer and only the summary row is printed.
        // -Dsweep.verbose=true shows the raw JLBH output for every rate.
        boolean verbose = Boolean.getBoolean("sweep.verbose");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream out = verbose
                ? System.out
                : new PrintStream(captured, true, StandardCharsets.UTF_8);

        new JLBH(options, out, result -> { }).start();

        // Percentiles come from the histogram the task recorded, merged across runs with
        // Histogram.add(). Not from averaging the per-run percentiles JLBH printed, and not
        // from JLBHResult's positional percentile mapping.
        Histogram merged = task.mergedHistogram();

        return new Row(rho, rate,
                task.minAchievedRate(),
                merged.getValueAtPercentile(50),
                merged.getValueAtPercentile(99),
                merged.getValueAtPercentile(99.9),
                merged.getValueAtPercentile(99.99),
                merged.getMaxValue(),
                merged.copy());
    }

    // ---------------------------------------------------------------------- report

    private static void printTable(List<Row> rows, Capacity capacity) {
        System.out.println();
        System.out.println("  Throughput sweep: end-to-end response time per burst");
        System.out.println("  (open loop, coordinated omission accounted for)");
        System.out.println("  " + "=".repeat(112));
        System.out.printf(Locale.ROOT, "  %5s %11s %11s %9s %9s %9s %9s %9s %9s %8s%n",
                "rho", "target/s", "achieved/s", "p50", "p99", "p99.9", "p99.99", "max",
                "p99.9/p50", "1/(1-r)");
        System.out.println("  " + "-".repeat(112));

        for (Row r : rows) {
            System.out.printf(Locale.ROOT,
                    "  %5.2f %11s %11s %9s %9s %9s %9s %9s %9s %8s%s%n",
                    r.utilisation(),
                    String.format(Locale.ROOT, "%,d", r.targetRate()),
                    String.format(Locale.ROOT, "%,.0f", r.achievedRate()),
                    fmt(r.p50()), fmt(r.p99()), fmt(r.p999()), fmt(r.p9999()), fmt(r.max()),
                    String.format(Locale.ROOT, "%.1fx", r.tailRatio()),
                    Double.isInfinite(r.theoreticalMultiplier())
                            ? "inf" : String.format(Locale.ROOT, "%.1fx", r.theoreticalMultiplier()),
                    r.keptUp() ? "" : "  <- DID NOT KEEP UP");
        }
        System.out.println("  " + "-".repeat(112));

        Row knee = findKnee(rows);
        System.out.println();
        if (knee == null) {
            System.out.println("  No knee found in this range: the tail did not turn upward at any");
            System.out.println("  utilisation tested. If even rho = 1.05 kept up, the calibration");
            System.out.println("  under-estimated capacity - the closed-loop measurement is taken with");
            System.out.println("  a hot cache and no arrival process, which flatters it.");
        } else {
            System.out.printf(Locale.ROOT,
                    "  The tail turns upward at about rho = %.2f, i.e. %,d bursts/s (%,d msg/s).%n",
                    knee.utilisation(), knee.targetRate(),
                    (long) knee.targetRate() * capacity.batchSize());
            System.out.println();
            System.out.println("  THAT is the number to report - not a single p99 figure detached from the");
            System.out.println("  rate it was measured at. Below this rate the component has headroom;");
            System.out.println("  above it, queueing dominates and the p99.9 stops describing your code.");

            if (knee.utilisation() <= 0.8) {
                System.out.println();
                System.out.printf(Locale.ROOT,
                        "  Note that this happened at %.0f%% utilisation, not at 100%%. This is the%n",
                        knee.utilisation() * 100);
                System.out.println("  concrete version of why \"we're only at 70% CPU\" is not reassuring.");
            }
        }

        System.out.println();
        System.out.println("  How to read the last two columns");
        System.out.println("  " + "-".repeat(72));
        System.out.println("  1/(1-rho) is the queue-length multiplier a textbook M/M/1 queue predicts.");
        System.out.println("  p99.9/p50 is what actually happened. Where the measured ratio outruns the");
        System.out.println("  theoretical one, the excess is variability: for the same average service");
        System.out.println("  time, a more variable service time produces a much longer queue. Queueing");
        System.out.println("  delay scales with the variability of arrivals and service, not just their");
        System.out.println("  means - which is why a ragged p99 saturates earlier than a slower but");
        System.out.println("  tighter one.");

        System.out.println();
        System.out.println("  Caveats that stay attached to this table");
        System.out.println("  " + "-".repeat(72));
        System.out.println("  - Rows marked DID NOT KEEP UP have a divergent queue. That is not a steady");
        System.out.println("    state and its p99 does not mean anything. Read such a row as 'cannot");
        System.out.println("    sustain this rate', never as 'latency is N us'.");
        System.out.println("  - Arrivals here are perfectly uniform, the friendliest possible case. Real");
        System.out.println("    market data is not. JLBH can apply a LatencyDistributor for bursty");
        System.out.println("    arrivals; expect the knee to move left when you do.");
        System.out.println("  - JLBH is a single-threaded, in-process, self-paced generator. While the");
        System.out.println("    task is blocked it is blocked too, and reconstructs the missed arrivals");
        System.out.println("    from the schedule. Far better founded than interpolation, but not the");
        System.out.println("    same as load genuinely arriving from outside the JVM.");
        System.out.println("  - At the highest rates the harness itself is doing real work per iteration.");
        System.out.println("    If a row fails to keep up at a rate where the maths says it should not,");
        System.out.println("    suspect the generator before concluding anything about the component.");

        Row lowest = rows.get(0);
        System.out.println();
        System.out.println("  The platform floor is visible in this table");
        System.out.println("  " + "-".repeat(72));
        System.out.printf(Locale.ROOT,
                "  At rho = %.2f the component is nearly idle, yet p99.99 is %s and max is %s.%n",
                lowest.utilisation(), fmt(lowest.p9999()), fmt(lowest.max()));
        System.out.println("  That is not queueing and it is not the encoder. It is the machine: scheduler");
        System.out.println("  latency, JVM safepoints, power management. Run 'hiccup' and compare - if the");
        System.out.println("  idle-JVM floor is the same order of magnitude, then no change to this code");
        System.out.println("  moves that column, and optimising against it would be wasted effort.");

        System.out.println();
        for (Row r : rows) {
            Percentiles.writeHgrm(r.histogram(), Path.of("results",
                    String.format(Locale.ROOT, "sweep-rho-%03d.hgrm", Math.round(r.utilisation() * 100))));
        }
        System.out.println("  Wrote results/sweep-rho-*.hgrm, one distribution per utilisation.");
        System.out.println("  Plot them together at hdrhistogram.github.io/HdrHistogram/plotFiles.html");
        System.out.println("  to see the tail lift off as rho climbs.");
    }

    /**
     * The knee is the first rate whose tail ratio has grown materially past the baseline
     * established at the lowest utilisation, or the first rate the component could not
     * sustain. Deliberately a crude heuristic: the table is the evidence, this is a pointer.
     */
    private static Row findKnee(List<Row> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        double baseline = rows.get(0).tailRatio();

        for (Row r : rows) {
            if (!r.keptUp()) {
                return r;
            }
            if (r.tailRatio() > baseline * 2.5) {
                return r;
            }
        }
        return null;
    }

    private static String fmt(long nanos) {
        return Percentiles.us(nanos);
    }
}
