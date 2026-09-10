package io.github.serhiibohutskyi.latency.platform;

import io.github.serhiibohutskyi.latency.hdr.Percentiles;
import org.HdrHistogram.Histogram;

import java.nio.file.Path;
import java.util.Locale;

/**
 * A jHiccup in about forty lines: sleep for a fixed interval, measure how late you actually
 * woke up, and record the difference.
 *
 * <p>What that captures is everything between your code and the hardware - JVM safepoint
 * pauses, GC, the OS scheduler, the hypervisor, SMI, CPU frequency and idle-state
 * transitions - without attributing any of it to the application, because the application
 * is doing nothing at all.
 *
 * <p>The usual framing is "reach for jHiccup when a spike is unexplained". The article
 * turns that around, and so does this project: <b>run it against an idle JVM on the target
 * machine before benchmarking anything.</b> If an idle JVM on this box shows a 4 ms hiccup,
 * then no amount of Java optimisation will get p99.99 below 4 ms here - and you have learned
 * that in sixty seconds, for zero engineering effort, instead of spending a week blaming
 * your own code. That makes it the first thing to run on a new machine, not the last.
 *
 * <p>Note the use of {@code recordValueWithExpectedInterval}. It is the right call
 * <i>here</i>, unlike in a load generator: if the thread is descheduled for 50 ms while
 * trying to sleep for 1 ms, then 50 separate 1 ms wake-ups were missed, and the
 * interpolation reconstructs exactly the samples a perfectly scheduled thread would have
 * taken. The measurement is of the schedule itself, so there is no service time being
 * guessed at. This is why jHiccup is not itself subject to coordinated omission.
 */
public final class HiccupMeter {

    private static final long RESOLUTION_NS = 1_000_000L;   // sleep 1 ms between samples

    private final Histogram hiccups = new Histogram(3);
    private volatile boolean running;
    private Thread thread;

    /** Runs in the foreground for {@code seconds} and prints a full report. */
    public void runStandalone(int seconds) {
        System.out.printf(Locale.ROOT,
                "  Measuring the platform floor for %d s on an otherwise idle JVM.%n", seconds);
        System.out.println("  Sleeping 1 ms at a time and recording how late each wake-up is.");
        System.out.println();
        printEnvironment();
        System.out.println();
        System.out.println("  running...");

        sample(seconds * 1000L);

        report();
    }

    /**
     * Starts a background jitter probe alongside another measurement. This is the same idea
     * as JLBH's {@code recordOSJitter(true)}: application latency and platform jitter from
     * the same run, so an unexplained end-to-end spike can often be attributed without
     * having to set up a second experiment.
     */
    public void startBackground() {
        running = true;
        thread = new Thread(() -> sampleUntilStopped(), "os-jitter-probe");
        thread.setDaemon(true);
        thread.start();
    }

    public Histogram stopBackground() {
        running = false;
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return hiccups;
    }

    public Histogram histogram() {
        return hiccups;
    }

    private void sample(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            one();
        }
    }

    private void sampleUntilStopped() {
        while (running) {
            one();
        }
    }

    private void one() {
        long before = System.nanoTime();
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        long after = System.nanoTime();

        long hiccupNs = (after - before) - RESOLUTION_NS;
        if (hiccupNs < 0) {
            hiccupNs = 0;
        }
        hiccups.recordValueWithExpectedInterval(hiccupNs, RESOLUTION_NS);
    }

    private void report() {
        Percentiles.printBlock(System.out, "platform hiccup (idle JVM)", hiccups);

        long floor = hiccups.getValueAtPercentile(99.99);
        long max = hiccups.getMaxValue();

        System.out.println();
        System.out.println("  What this means for everything you measure afterwards");
        System.out.println(Percentiles.rule(72));
        System.out.printf(Locale.ROOT,
                "  Your application p99.99 cannot be better than %s on this machine,%n",
                Percentiles.us(floor));
        System.out.println("  because an idle JVM here is already that late.");
        System.out.printf(Locale.ROOT,
                "  The worst single stall observed was %s.%n", Percentiles.us(max));
        System.out.println();

        if (max > 5_000_000L) {
            System.out.println("  This floor is high. On a developer machine that is normal and expected:");
            System.out.println("  a benchmark run next to Slack, Docker Desktop, Chrome, an antivirus scanner");
            System.out.println("  and three IDE processes produces a p99.99 that describes the laptop.");
            System.out.println("  Treat the numbers from the other commands as relative comparisons, not as");
            System.out.println("  absolute latency figures for your code.");
        } else if (max > 1_000_000L) {
            System.out.println("  A sub-5 ms floor is a reasonable general-purpose machine, but it is still");
            System.out.println("  far above what a tuned, isolated core would show.");
        } else {
            System.out.println("  A sub-millisecond floor. This box is quiet enough for microsecond-scale work.");
        }

        System.out.println();
        System.out.println("  If production runs in a container with a CPU quota, remember that benchmarking");
        System.out.println("  on a tuned bare-metal box tells you about a system you are not going to deploy.");
        System.out.println("  Reproduce the production configuration. If that configuration has a 4 ms floor,");
        System.out.println("  that is a finding, not an inconvenience.");

        Percentiles.writeHgrm(hiccups, Path.of("results", "platform-hiccup.hgrm"));
        System.out.println();
        System.out.println("  Wrote results/platform-hiccup.hgrm");
    }

    static void printEnvironment() {
        Runtime rt = Runtime.getRuntime();
        System.out.printf(Locale.ROOT, "  %-22s %s%n", "java.vendor", System.getProperty("java.vendor"));
        System.out.printf(Locale.ROOT, "  %-22s %s%n", "java.version", System.getProperty("java.version"));
        System.out.printf(Locale.ROOT, "  %-22s %s %s%n", "os",
                System.getProperty("os.name"), System.getProperty("os.version"));
        System.out.printf(Locale.ROOT, "  %-22s %s%n", "arch", System.getProperty("os.arch"));
        System.out.printf(Locale.ROOT, "  %-22s %d%n", "availableProcessors", rt.availableProcessors());
        System.out.printf(Locale.ROOT, "  %-22s %,d MB%n", "maxMemory", rt.maxMemory() / (1024 * 1024));

        String gc = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
                .stream()
                .map(java.lang.management.GarbageCollectorMXBean::getName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("unknown");
        System.out.printf(Locale.ROOT, "  %-22s %s%n", "collectors", gc);
    }
}
