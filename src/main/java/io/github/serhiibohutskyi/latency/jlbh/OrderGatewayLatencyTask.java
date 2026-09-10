package io.github.serhiibohutskyi.latency.jlbh;

import io.github.serhiibohutskyi.latency.domain.GatewayPipeline;
import io.github.serhiibohutskyi.latency.domain.RiskEngine;
import io.github.serhiibohutskyi.latency.hdr.LatencyRecorder;
import net.openhft.chronicle.core.util.NanoSampler;
import net.openhft.chronicle.jlbh.JLBH;
import net.openhft.chronicle.jlbh.JLBHTask;
import org.HdrHistogram.Histogram;

/**
 * Level 2: the whole order gateway, driven open-loop.
 *
 * <p>The difference between this and {@code GatewayPipelineBenchmark} is one arrow.
 * JMH is closed-loop: the next operation begins when the previous one returns, so no queue
 * can ever form. JLBH has a clock instead - it maintains a schedule independent of how long
 * the work takes, so work arriving while the system is busy queues up, exactly as it would
 * in production. That is the only way a benchmark can observe queueing, and therefore the
 * only way it can report response time rather than service time.
 *
 * <p>Each scheduled arrival delivers a burst of messages rather than one message; see
 * {@link GatewayPipeline} for why that is necessary rather than merely convenient. The
 * latency reported is the time to process a burst.
 *
 * <p><b>The stage probes are behind a flag.</b> Enable them with
 * {@code -Dgateway.bench.probes=true}, or use the {@code stages} command. Probes off gives
 * the end-to-end number to quote; probes on tells you which stage moved. Do not quote the
 * instrumented number, and do not add the three stage p99s together and expect the
 * end-to-end p99 - run the {@code percentiles} command to see how badly that fails.
 */
public final class OrderGatewayLatencyTask implements JLBHTask {

    private static final boolean STAGE_PROBES =
            Boolean.getBoolean("gateway.bench.probes");

    private final GatewayPipeline pipeline = new GatewayPipeline();
    private final GatewayPipeline.StageTimes stageTimes = new GatewayPipeline.StageTimes();

    private JLBH jlbh;
    private NanoSampler decodeProbe;
    private NanoSampler riskProbe;
    private NanoSampler encodeProbe;

    // Achieved-rate tracking. "The first thing to check is whether the run actually
    // achieved the rate you asked for" - so measure it rather than assuming it.
    private long measuredCount;
    private long firstSampleNs;
    private long lastSampleNs;
    private volatile double lastRunRate;
    private volatile double minRunRate = Double.MAX_VALUE;

    /** Suppresses per-run chatter when many runs are driven back to back (the sweep). */
    private boolean quiet;

    // The article's own recorder, wired into the article's own harness.
    //
    // JLBH does report percentiles, but its JLBHResult API maps a variable-length percentile
    // array positionally onto a fixed nine-constant enum. When a run produces six percentile
    // columns rather than eight, get999thPercentile() silently returns the p99.99 value and
    // get9999thPercentile() returns zero. Reading percentiles from a histogram this task
    // owns removes that whole class of problem, and it is what the article recommends doing
    // in a custom harness anyway.
    private final LatencyRecorder recorder = new LatencyRecorder();
    private final Histogram allRuns = new Histogram(3);
    private final Histogram lastRun = new Histogram(3);

    public static boolean probesEnabled() {
        return STAGE_PROBES;
    }

    public int batchSize() {
        return pipeline.batchSize();
    }

    /** Bursts per second actually achieved during the most recently completed run. */
    public double lastRunAchievedRate() {
        return lastRunRate;
    }

    /**
     * The slowest run's achieved rate. The conservative number: a short run can catch a
     * quiet moment and a warm cache, and calibrating against that optimistic figure labels
     * later rows with a utilisation the generator never actually reached.
     */
    public double minAchievedRate() {
        return minRunRate == Double.MAX_VALUE ? lastRunRate : minRunRate;
    }

    public OrderGatewayLatencyTask quiet() {
        this.quiet = true;
        return this;
    }

    @Override
    public void init(JLBH jlbh) {
        this.jlbh = jlbh;

        if (STAGE_PROBES) {
            decodeProbe = jlbh.addProbe("decode");
            riskProbe = jlbh.addProbe("risk");
            encodeProbe = jlbh.addProbe("encode");
        }
    }

    @Override
    public void run(long startTimeNS) {
        // startTimeNS is the time this iteration was SUPPOSED to start.
        // It is not System.nanoTime(). That is the whole story.
        //
        // With accountForCoordinatedOmission(true), JLBH advances this nominal time by the
        // fixed inter-arrival gap on every iteration, regardless of how long the previous
        // one took. If the loop has fallen behind, the next iteration fires immediately
        // carrying an already-past start time, so the subtraction below includes the
        // lateness. With the flag off, JLBH re-bases the schedule to "now" whenever it is
        // behind, and you get service time instead.

        if (firstSampleNs == 0L) {
            firstSampleNs = System.nanoTime();
        }
        measuredCount++;

        if (!STAGE_PROBES) {
            pipeline.processBatch();

            lastSampleNs = System.nanoTime();
            long latency = lastSampleNs - startTimeNS;
            jlbh.sample(latency);
            recorder.record(latency);
            return;
        }

        pipeline.processBatchStaged(stageTimes);

        decodeProbe.sampleNanos(stageTimes.decodeNs);
        riskProbe.sampleNanos(stageTimes.riskNs);
        encodeProbe.sampleNanos(stageTimes.encodeNs);

        lastSampleNs = System.nanoTime();
        long latency = lastSampleNs - startTimeNS;
        jlbh.sample(latency);
        recorder.record(latency);
    }

    @Override
    public void runComplete() {
        long span = lastSampleNs - firstSampleNs;
        lastRunRate = span > 0 ? measuredCount / (span / 1e9) : 0.0;
        if (lastRunRate > 0) {
            minRunRate = Math.min(minRunRate, lastRunRate);
        }

        // Take the interval, keep a copy for "the last run", and merge into the running
        // total. Merging with add() rather than averaging the per-run percentiles is the
        // whole point of the percentile section of the article.
        Histogram interval = recorder.takeInterval();
        lastRun.reset();
        lastRun.add(interval);
        allRuns.add(interval);

        measuredCount = 0;
        firstSampleNs = 0L;
        lastSampleNs = 0L;
    }

    /** Distribution of the most recently completed run. */
    public Histogram lastRunHistogram() {
        return lastRun;
    }

    /** All measured runs merged with {@code Histogram.add()} - never averaged. */
    public Histogram mergedHistogram() {
        return allRuns;
    }

    /**
     * JLBH calls this once warm-up is finished, which is the hook the article points at:
     * you can assert that steady state was reached before measurement begins, rather than
     * assuming it. A warm-up that does not exercise the production type and branch profile
     * is worse than no warm-up, because it hands you a false green.
     */
    @Override
    public void warmedUp() {
        RiskEngine risk = pipeline.riskEngine();
        long total = risk.acceptedCount() + risk.rejectedCount();
        long rejected = risk.rejectedCount();

        if (!quiet) {
            System.out.printf("%n[warm-up complete] %,d messages, %,d accepted, %,d rejected (%.1f%%)%n",
                    total, risk.acceptedCount(), rejected,
                    total == 0 ? 0.0 : 100.0 * rejected / total);
        }

        if (rejected == 0 || rejected == total) {
            System.out.println("[warm-up WARNING] only one side of the risk branch was exercised.");
            System.out.println("[warm-up WARNING] C2 has compiled against a profile the real workload");
            System.out.println("[warm-up WARNING] will not match, and the first message down the other");
            System.out.println("[warm-up WARNING] path will deoptimise. This is profile pollution.");
        }
        if (!quiet) {
            System.out.println();
        }

        measuredCount = 0;
        firstSampleNs = 0L;
        lastSampleNs = 0L;

        // Discard anything recorded during warm-up: those samples describe a JVM that was
        // still compiling, and mixing them into the measurement is how a benchmark reports
        // a tail that belongs to the interpreter.
        recorder.takeInterval();
        allRuns.reset();
        lastRun.reset();
    }

    @Override
    public void complete() {
        if (!quiet) {
            System.out.println("[task] sink=" + pipeline.sink()
                    + " netPosition=" + pipeline.riskEngine().netPosition());
        }
    }
}
