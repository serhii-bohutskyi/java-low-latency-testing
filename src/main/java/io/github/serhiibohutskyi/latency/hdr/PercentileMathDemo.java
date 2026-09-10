package io.github.serhiibohutskyi.latency.hdr;

import org.HdrHistogram.Histogram;

import java.util.Locale;
import java.util.Random;

/**
 * Three claims about percentiles that are easy to state and easy to ignore, each turned
 * into a number you can watch be wrong.
 *
 * <ol>
 *   <li><b>Percentiles do not average.</b> Five runs do not give you a p99 by taking the
 *       mean of five p99 values. There is no arithmetic that turns five percentiles back
 *       into a distribution. Merge the histograms with {@code Histogram.add()} and read the
 *       percentile off the merged result. The same applies across shards and across hosts.</li>
 *   <li><b>Percentiles do not compose across stages.</b> If a request passes through three
 *       stages that each have p99 = X, the request's p99 is worse than X, and how much worse
 *       depends on the shape of the distributions and on how correlated the stages are.
 *       <p>It is often said that adding the per-stage p99s at least gives you a safe upper
 *       bound. {@link #compositionIsWrong()} shows that this is only true for light-tailed,
 *       independent stages. For heavy-tailed stages the sum comes out <i>below</i> the real
 *       end-to-end p99, and for correlated stages it comes out far below. The sum is not a
 *       bound in either direction; it is a quantity with no fixed relationship to the
 *       answer.</p></li>
 *   <li><b>Say how many samples are behind the number.</b> p99.99 needs on the order of a
 *       million samples before it means anything, and even then only about a hundred
 *       observations sit above it.</li>
 * </ol>
 *
 * <p>The distributions here are synthetic on purpose. Real measurements would make the
 * effects noisier without making them clearer, and the point is arithmetic, not hardware.
 */
public final class PercentileMathDemo {

    private static final long SEED = 20250910L;

    public void run() {
        averagingIsWrong();
        compositionIsWrong();
        sampleCountsMatter();
        bucketingIsApproximate();
    }

    // ---------------------------------------------------------------- 1. averaging

    private void averagingIsWrong() {
        header("1. Percentiles do not average");

        System.out.println("  Five runs of the same service. Four are healthy; one run hit a bad patch,");
        System.out.println("  which is exactly the situation where you most need the number to be right.");
        System.out.println();

        Random rnd = new Random(SEED);
        Histogram[] runs = new Histogram[5];

        for (int i = 0; i < runs.length; i++) {
            boolean badRun = (i == 3);
            runs[i] = syntheticRun(rnd, badRun);
        }

        System.out.println(Percentiles.tableHeader("run"));
        System.out.println(Percentiles.rule(94));

        double sumP99 = 0;
        double sumP999 = 0;
        for (int i = 0; i < runs.length; i++) {
            System.out.println(Percentiles.tableRow(
                    "run " + (i + 1) + (i == 3 ? " (bad patch)" : ""), runs[i]));
            sumP99 += runs[i].getValueAtPercentile(99);
            sumP999 += runs[i].getValueAtPercentile(99.9);
        }
        System.out.println(Percentiles.rule(94));

        Histogram merged = new Histogram(3);
        for (Histogram run : runs) {
            merged.add(run);   // this is the only correct way to combine them
        }
        System.out.println(Percentiles.tableRow("MERGED (correct)", merged));
        System.out.println(Percentiles.rule(94));

        long avgP99 = (long) (sumP99 / runs.length);
        long avgP999 = (long) (sumP999 / runs.length);
        long realP99 = merged.getValueAtPercentile(99);
        long realP999 = merged.getValueAtPercentile(99.9);

        System.out.println();
        System.out.printf(Locale.ROOT, "  %-34s %12s %12s%n", "", "p99", "p99.9");
        System.out.printf(Locale.ROOT, "  %-34s %12s %12s%n",
                "mean of the five percentiles", Percentiles.us(avgP99), Percentiles.us(avgP999));
        System.out.printf(Locale.ROOT, "  %-34s %12s %12s%n",
                "percentile of the merged data", Percentiles.us(realP99), Percentiles.us(realP999));
        System.out.printf(Locale.ROOT, "  %-34s %11.1f%% %11.1f%%%n",
                "error from averaging",
                100.0 * (avgP99 - realP99) / realP99,
                100.0 * (avgP999 - realP999) / realP999);

        System.out.println();
        System.out.println("  Note the two error figures have opposite signs, and that is the real lesson.");
        System.out.println("  Averaging the five p99s overstates the true p99 by several times, because");
        System.out.println("  the bad run's p99 is enormous and it drags the mean up. Averaging the five");
        System.out.println("  p99.9s understates the true p99.9, because four healthy runs dilute it.");
        System.out.println();
        System.out.println("  So averaging percentiles is not a rough approximation with a known direction");
        System.out.println("  you could correct for. It is an operation without meaning: there is no");
        System.out.println("  arithmetic that turns five percentiles back into a distribution.");
        System.out.println("  Histogram.add() keeps every observation and re-reads the percentile from the");
        System.out.println("  real distribution. Same for shards, same for hosts, same for anything.");
    }

    // ------------------------------------------------------------- 2. composition

    private void compositionIsWrong() {
        header("2. Percentiles do not compose across stages");

        System.out.println("  Three pipeline stages, three different shapes, same question each time:");
        System.out.println("  if you know each stage's p99, what is the request's p99?");
        System.out.println();

        int samples = 2_000_000;

        // Scenario A: light-tailed stages. Jitter is roughly symmetric noise around a mean,
        // with no rare catastrophic branch.
        Scenario light = new Scenario("A. light-tailed, independent");
        // Scenario B: the shape real stages actually have -- a tight body plus a rare, much
        // slower branch (a cache miss, a slow path, a lock acquired under contention).
        Scenario heavy = new Scenario("B. heavy-tailed, independent");
        // Scenario C: the same heavy stages, but a shared shock hits all three stages of the
        // same request at once. A safepoint, a descheduled thread, a cold cache.
        Scenario correlated = new Scenario("C. heavy-tailed, correlated");

        // A separate stream per scenario. Drawing all three from one shared Random would
        // interleave draws in a fixed pattern, and java.util.Random is an LCG whose
        // successive values are not independent enough to survive that: scenarios B and C
        // use identical stage distributions, and sharing a stream made their stage p99s
        // differ by 30%. Two "identical" rows disagreeing is a measurement bug, not a
        // finding -- which is the same discipline the rest of this project is about.
        Random lightRnd = new Random(SEED + 1);
        Random heavyRnd = new Random(SEED + 2);
        Random corrRnd = new Random(SEED + 3);
        Random shockRnd = new Random(SEED + 4);

        for (int i = 0; i < samples; i++) {
            light.record(lightStage(lightRnd), lightStage(lightRnd), lightStage(lightRnd), 0);

            heavy.record(heavyStage(heavyRnd), heavyStage(heavyRnd), heavyStage(heavyRnd), 0);

            long shock = shockRnd.nextInt(200) == 0 ? 20_000 + shockRnd.nextInt(40_000) : 0;
            correlated.record(heavyStage(corrRnd), heavyStage(corrRnd), heavyStage(corrRnd), shock);
        }

        System.out.printf(Locale.ROOT, "  %-30s %12s %12s %12s %10s%n",
                "scenario", "stage p99", "sum of p99s", "actual p99", "sum is");
        System.out.println(Percentiles.rule(84));
        light.printRow();
        heavy.printRow();
        correlated.printRow();
        System.out.println(Percentiles.rule(84));

        System.out.println();
        System.out.println("  Read the last column. The sum of the per-stage p99s lands on the wrong side");
        System.out.println("  in different directions depending only on the shape of the distributions:");
        System.out.println();
        System.out.println("  A  Light-tailed and independent: the sum OVER-estimates. Three stages rarely");
        System.out.println("     have their bad moment on the same request, so the errors partly cancel.");
        System.out.println("     This is the case people have in mind when they call the sum a safe bound.");
        System.out.println();
        System.out.println("  B  Heavy-tailed and independent: the sum UNDER-estimates. Once a single");
        System.out.println("     stage's rare slow branch dominates, the chance that SOME stage takes it is");
        System.out.println("     roughly three times the per-stage chance, so the end-to-end tail is pushed");
        System.out.println("     deeper into that branch than any one stage's p99 ever reaches.");
        System.out.println();
        System.out.println("  C  Correlated: the sum under-estimates badly. Shared shocks are exactly what");
        System.out.println("     a safepoint, a GC pause or a descheduled thread produce, and they hit");
        System.out.println("     every stage of the same request together.");
        System.out.println();
        System.out.println("  So 'add the stage p99s' is not a conservative upper bound you can lean on.");
        System.out.println("  It is a quantity with no fixed relationship to the answer. The only thing");
        System.out.println("  that holds in general is the weak form: the request's p99 is worse than any");
        System.out.println("  single stage's p99, and how much worse depends on shape and correlation.");
        System.out.println();
        System.out.println("  This is the concrete limit on what per-stage probes can tell you. They");
        System.out.println("  locate a regression. They do not compose into an end-to-end number, so you");
        System.out.println("  still have to measure end to end.");
    }

    /** One composition scenario: three stages plus an optional shared shock. */
    private static final class Scenario {

        private final String name;
        private final Histogram stage = new Histogram(3);
        private final Histogram endToEnd = new Histogram(3);

        Scenario(String name) {
            this.name = name;
        }

        void record(long a, long b, long c, long sharedShock) {
            stage.recordValue(a);
            stage.recordValue(b);
            stage.recordValue(c);
            endToEnd.recordValue(a + b + c + sharedShock * 3);
        }

        void printRow() {
            long p99 = stage.getValueAtPercentile(99);
            long sum = p99 * 3;
            long actual = endToEnd.getValueAtPercentile(99);

            String verdict = sum > actual
                    ? String.format(Locale.ROOT, "+%.0f%% high", 100.0 * (sum - actual) / actual)
                    : String.format(Locale.ROOT, "%.0f%% low", 100.0 * (sum - actual) / actual);

            System.out.printf(Locale.ROOT, "  %-30s %12s %12s %12s %10s%n",
                    name,
                    Percentiles.us(p99),
                    Percentiles.us(sum),
                    Percentiles.us(actual),
                    verdict);
        }
    }

    /** Symmetric noise, no rare branch. */
    private static long lightStage(Random rnd) {
        return 3_000 + (long) Math.abs(rnd.nextGaussian() * 300);
    }

    /**
     * Tight body plus a genuinely heavy 2% branch, drawn from a Pareto tail rather than a
     * Gaussian one.
     *
     * <p>The shape matters more than it looks. With a Gaussian "slow branch" the tail is
     * still thin, and the sum of three stages behaves almost normally, so the sum of the
     * p99s comes out high. Real latency tails are not Gaussian: they are heavy, and for
     * heavy tails P(sum &gt; x) approaches n * P(one stage &gt; x), which pushes the
     * end-to-end p99 past what any single stage's p99 predicts.
     *
     * <p>The branch is 2%, not 1%, on purpose. At exactly 1% the p99 sits precisely on the
     * boundary between the body and the branch, so it flips between the two by chance and
     * two statistically identical scenarios report visibly different stage p99s. That is a
     * knife-edge in the measurement, not a property of the system.
     */
    private static long heavyStage(Random rnd) {
        if (rnd.nextInt(50) == 0) {
            // Pareto, alpha = 1.1: heavy enough that the tail dominates the sum.
            double u = Math.max(rnd.nextDouble(), 1e-9);
            return 4_000 + (long) (4_000 * (Math.pow(u, -1.0 / 1.1) - 1.0));
        }
        return 3_000 + (long) Math.abs(rnd.nextGaussian() * 200);
    }

    // ----------------------------------------------------------- 3. sample counts

    private void sampleCountsMatter() {
        header("3. Say how many samples are behind the number");

        Random rnd = new Random(SEED + 2);
        int[] sizes = {1_000, 10_000, 100_000, 1_000_000, 10_000_000};

        System.out.printf(Locale.ROOT, "  %-14s %12s %12s %12s %14s%n",
                "samples", "p99", "p99.9", "p99.99", "obs above p99.99");
        System.out.println(Percentiles.rule(70));

        for (int size : sizes) {
            Histogram h = new Histogram(3);
            for (int i = 0; i < size; i++) {
                h.recordValue(latencySample(rnd));
            }
            long above = Math.round(size * 0.0001);
            System.out.printf(Locale.ROOT, "  %-14s %12s %12s %12s %14s%n",
                    String.format(Locale.ROOT, "%,d", size),
                    Percentiles.us(h.getValueAtPercentile(99)),
                    Percentiles.us(h.getValueAtPercentile(99.9)),
                    Percentiles.us(h.getValueAtPercentile(99.99)),
                    above == 0 ? "none" : String.format(Locale.ROOT, "~%,d", above));
        }

        System.out.println();
        System.out.println("  All five rows sample the same distribution. The p99 column settles quickly.");
        System.out.println("  The p99.99 column is still moving at 100,000 samples, because it is being");
        System.out.println("  computed from about ten observations. Quoting it there is quoting noise.");
        System.out.println();
        System.out.println("  max is always a single observation. Quote it - it is often the most");
        System.out.println("  interesting thing in a run - but never optimise against it. It will not");
        System.out.println("  reproduce.");
    }

    // ------------------------------------------------------------- 4. bucketing

    private void bucketingIsApproximate() {
        header("4. HdrHistogram buckets values");

        Histogram h = new Histogram(3);
        long[] probes = {410, 1_234, 41_000, 987_654};

        System.out.printf(Locale.ROOT, "  %-16s %-16s %-16s %s%n",
                "recorded", "reported back", "bucket width", "error");
        System.out.println(Percentiles.rule(70));

        for (long v : probes) {
            h.recordValue(v);
            long reported = h.highestEquivalentValue(v);
            long width = h.sizeOfEquivalentValueRange(v);
            System.out.printf(Locale.ROOT, "  %-16s %-16s %-16s %.3f%%%n",
                    String.format(Locale.ROOT, "%,d ns", v),
                    String.format(Locale.ROOT, "%,d ns", reported),
                    String.format(Locale.ROOT, "%,d ns", width),
                    100.0 * (reported - v) / v);
        }

        System.out.println();
        System.out.println("  getValueAtPercentile() returns the highest equivalent value of the bucket");
        System.out.println("  it lands in. At three significant digits every number is accurate to about");
        System.out.println("  0.1%. Printing 'p99.9 = 410 ns' implies a precision the data structure does");
        System.out.println("  not carry - so report it as 410 ns +/- a bucket, or just do not print the");
        System.out.println("  last digit as though it were meaningful.");
    }

    // ------------------------------------------------------------------ helpers

    /** A plausible latency distribution: tight body, log-normal-ish tail, rare stalls. */
    private static long latencySample(Random rnd) {
        double u = rnd.nextDouble();
        long v;
        if (u < 0.90) {
            v = 15_000 + (long) (rnd.nextGaussian() * 3_000);
        } else if (u < 0.999) {
            v = 25_000 + (long) (Math.abs(rnd.nextGaussian()) * 20_000);
        } else {
            v = 300_000 + (long) (Math.abs(rnd.nextGaussian()) * 2_000_000);
        }
        // A 5-sigma draw on the low side goes negative, and at 10M samples that happens.
        // HdrHistogram throws on negatives, so clamp -- the same discipline as the ceiling
        // clamp in LatencyRecorder, at the other end of the range.
        return Math.max(v, 1);
    }

    private static Histogram syntheticRun(Random rnd, boolean badRun) {
        Histogram h = new Histogram(3);
        for (int i = 0; i < 200_000; i++) {
            long v = latencySample(rnd);
            if (badRun && rnd.nextInt(100) == 0) {
                v += 2_000_000 + rnd.nextInt(8_000_000);
            }
            h.recordValue(Math.max(v, 1));
        }
        return h;
    }

    private static void header(String title) {
        System.out.println();
        System.out.println("  " + "=".repeat(94));
        System.out.println("  " + title);
        System.out.println("  " + "=".repeat(94));
        System.out.println();
    }
}
