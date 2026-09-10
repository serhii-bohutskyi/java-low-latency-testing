package io.github.serhiibohutskyi.latency.co;

import io.github.serhiibohutskyi.latency.domain.GatewayPipeline;
import io.github.serhiibohutskyi.latency.hdr.Percentiles;
import org.HdrHistogram.Histogram;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The benchmark bug worth mentioning in an interview, demonstrated on real numbers rather
 * than asserted.
 *
 * <p>The setup: drive the gateway pipeline open-loop at a fixed arrival rate, and freeze
 * the application periodically. Every arrival is then recorded <b>three different ways from
 * the same run</b>, so the three answers can be compared directly instead of being taken on
 * faith. (Each arrival delivers a burst of messages rather than one message; see
 * {@link GatewayPipeline} for why a single message is smaller than the clock can resolve.)
 *
 * <ol>
 *   <li><b>Service time</b> - nanoTime taken when work is dequeued, to when it finishes.
 *       This is the CO-afflicted number, and it is also what almost everybody's production
 *       instrumentation actually records. No load generator is involved and it is still
 *       coordinated omission.</li>
 *   <li><b>Response time</b> - measured from the time the message was <i>supposed</i> to
 *       arrive. This is what a caller experiences, and it is what
 *       {@code accountForCoordinatedOmission(true)} gives you in JLBH.</li>
 *   <li><b>HdrHistogram's correction</b> - {@code recordValueWithExpectedInterval} applied
 *       to the service-time samples. Deliberately included to show that it is a third,
 *       different answer: it reconstructs the missing samples by interpolation, so it lands
 *       near the response-time column when arrivals are uniform, and it is guessing.</li>
 * </ol>
 *
 * <p>The arithmetic the run should reproduce: at a target of one arrival every
 * {@code interval} ns, a stall of {@code S} ns hides roughly {@code S / interval} arrivals.
 * Five 10 ms stalls at 100k arrivals/s bury about 5,000 late arrivals, which is 0.5% of a
 * million-sample run - everything from p99.5 upward. Service time records five bad samples
 * instead of five thousand, so it still looks healthy at p99.9. That gap is the point.
 */
public final class CoordinatedOmissionDemo {

    private final int targetRate;
    private final int durationSeconds;
    private final long stallNs;
    private final long stallPeriodNs;

    public CoordinatedOmissionDemo(int targetRate, int durationSeconds,
                                   long stallMicros, long stallPeriodMillis) {
        this.targetRate = targetRate;
        this.durationSeconds = durationSeconds;
        this.stallNs = stallMicros * 1_000L;
        this.stallPeriodNs = stallPeriodMillis * 1_000_000L;
    }

    // --- the system under test -------------------------------------------------------

    private final GatewayPipeline pipeline = new GatewayPipeline();

    /**
     * A stall the application really experiences. Busy-spins rather than sleeping, so it
     * behaves like a GC pause or a descheduled thread: the thread is unavailable, whereas
     * sleeping would hand the core back and measure something friendlier than reality.
     */
    private void stall() {
        long until = System.nanoTime() + stallNs;
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }

    // --- the harness -----------------------------------------------------------------

    public void run() {
        long intervalNs = 1_000_000_000L / targetRate;
        long iterations = (long) targetRate * durationSeconds;

        System.out.printf(Locale.ROOT,
                "  target rate      %,d bursts/s  (one every %,d ns)%n", targetRate, intervalNs);
        System.out.printf(Locale.ROOT,
                "  duration         %d s  -> %,d bursts of %d = %,d messages%n",
                durationSeconds, iterations, pipeline.batchSize(),
                iterations * pipeline.batchSize());
        System.out.printf(Locale.ROOT,
                "  injected stall   %,d us every %,d ms%n",
                stallNs / 1000, stallPeriodNs / 1_000_000);
        System.out.printf(Locale.ROOT,
                "  bursts hidden    ~%,d per stall%n%n", stallNs / intervalNs);

        warmUp();

        Histogram service = new Histogram(3);
        Histogram response = new Histogram(3);
        Histogram patched = new Histogram(3);

        long start = System.nanoTime();
        long nextStall = start + stallPeriodNs;
        int stalls = 0;

        for (long i = 0; i < iterations; i++) {

            // The nominal arrival time of this message. Advanced by the fixed inter-arrival
            // gap on every iteration, regardless of how long the previous one took. That
            // single decision is what accountForCoordinatedOmission(true) means: once the
            // loop has fallen behind, this time is already in the past, the wait below
            // returns immediately, and the lateness lands in the measurement.
            long scheduled = start + i * intervalNs;

            while (System.nanoTime() < scheduled) {
                Thread.onSpinWait();
            }

            long dequeued = System.nanoTime();

            pipeline.processBatch();

            if (dequeued >= nextStall) {
                stall();
                nextStall += stallPeriodNs;
                stalls++;
            }

            long done = System.nanoTime();

            service.recordValue(done - dequeued);
            response.recordValue(Math.max(done - scheduled, 0));
            patched.recordValueWithExpectedInterval(done - dequeued, intervalNs);
        }

        long elapsed = System.nanoTime() - start;
        report(service, response, patched, iterations, elapsed, stalls);
    }

    private void warmUp() {
        System.out.println("  warming up (200k bursts, both risk branches)...");
        for (int i = 0; i < 200_000; i++) {
            pipeline.processBatch();
        }
        pipeline.resetCounters();
    }

    private void report(Histogram service, Histogram response, Histogram patched,
                        long iterations, long elapsedNs, int stalls) {

        double achieved = iterations / (elapsedNs / 1e9);

        System.out.printf(Locale.ROOT, "%n  achieved rate    %,.0f bursts/s (target %,d)  ",
                achieved, targetRate);
        if (achieved < targetRate * 0.97) {
            System.out.println("<- BEHIND TARGET");
            System.out.println("     The run did not keep up, so the queue never reached a steady state.");
            System.out.println("     A divergent queue's p99 does not mean anything. Lower the rate.");
        } else {
            System.out.println("ok");
        }
        System.out.printf(Locale.ROOT, "  stalls injected  %d%n", stalls);

        System.out.println();
        System.out.println(Percentiles.tableHeader("what was measured"));
        System.out.println(Percentiles.rule(94));
        System.out.println(Percentiles.tableRow("1. service time", service));
        System.out.println(Percentiles.tableRow("2. response time", response));
        System.out.println(Percentiles.tableRow("3. HdrHistogram patch", patched));
        System.out.println(Percentiles.rule(94));

        System.out.println();
        System.out.println("  1. service time      CO off. What your onMessage() instrumentation records.");
        System.out.println("  2. response time     CO on.  Measured from the slot the message belonged to.");
        System.out.println("  3. HdrHistogram      recordValueWithExpectedInterval over (1): interpolated,");
        System.out.println("                       not observed. Close here only because arrivals are uniform.");

        long threshold = stallNs / 2;
        long serviceBad = countAbove(service, threshold);
        long responseBad = countAbove(response, threshold);

        System.out.println();
        System.out.printf(Locale.ROOT,
                "  Samples above %s:  service time %,d      response time %,d%n",
                Percentiles.us(threshold), serviceBad, responseBad);
        System.out.printf(Locale.ROOT,
                "  Service time reported %,d bad samples where %,d bursts were actually late.%n",
                serviceBad, responseBad);
        System.out.println();
        System.out.println("  That ratio is coordinated omission: the measurement process stopped");
        System.out.println("  sampling precisely while the system was failing.");

        Percentiles.printBlock(System.out, "response time (the number to quote)", response);

        Path dir = Path.of("results");
        Percentiles.writeHgrm(service, dir.resolve("co-service-time.hgrm"));
        Percentiles.writeHgrm(response, dir.resolve("co-response-time.hgrm"));
        Percentiles.writeHgrm(patched, dir.resolve("co-hdr-patched.hgrm"));
        System.out.println();
        System.out.println("  Wrote results/co-*.hgrm  (sink=" + pipeline.sink() + ")");
        System.out.println("  Plot them at hdrhistogram.github.io/HdrHistogram/plotFiles.html");
    }

    private static long countAbove(Histogram h, long thresholdNs) {
        return h.getTotalCount() - h.getCountBetweenValues(0, thresholdNs);
    }
}
