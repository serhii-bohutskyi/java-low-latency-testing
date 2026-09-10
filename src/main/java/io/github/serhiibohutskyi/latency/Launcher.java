package io.github.serhiibohutskyi.latency;

import io.github.serhiibohutskyi.latency.co.CoordinatedOmissionDemo;
import io.github.serhiibohutskyi.latency.hdr.PercentileMathDemo;
import io.github.serhiibohutskyi.latency.platform.ClockProbe;
import io.github.serhiibohutskyi.latency.platform.HiccupMeter;

import java.util.Arrays;
import java.util.Locale;

/**
 * Single entry point for every level of the article.
 *
 * <p>Run with no arguments for the menu. The order the commands are listed in is the order
 * the article recommends running them: establish the platform floor, then the clock, then
 * isolate the hot path, then drive it open-loop, then sweep the rate.
 */
public final class Launcher {

    static {
        // Chronicle libraries (pulled in by JLBH) phone home with usage analytics and print
        // a startup announcement unless told not to. Disabled here rather than left to the
        // reader's command line, because a benchmark project should not be sending network
        // traffic in the middle of a latency measurement.
        setIfAbsent("chronicle.analytics.disable", "true");
        setIfAbsent("chronicle.announcer.disable", "true");
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || isHelp(args[0])) {
            usage();
            return;
        }

        String command = args[0].toLowerCase(Locale.ROOT);
        String[] rest = applySystemProperties(Arrays.copyOfRange(args, 1, args.length));

        switch (command) {
            case "hiccup" -> {
                banner("Platform floor (jHiccup-style)",
                        "The first thing to run on a new machine, not the last.");
                new HiccupMeter().runStandalone(intArg(rest, 0, 30));
            }

            case "clock" -> {
                banner("Clock probe",
                        "Measure the ruler before trusting anything measured with it.");
                new ClockProbe().run();
            }

            case "jmh" -> {
                banner("Level 1: JMH microbenchmarks",
                        "Service time at 100% utilisation of one thread. No queue can form here.");
                if (rest.length == 0) {
                    System.out.println("  No benchmark filter given. Examples:");
                    System.out.println();
                    System.out.println("    jmh OrderEncoderBenchmark");
                    System.out.println("    jmh OrderEncoderBenchmark -prof gc");
                    System.out.println("    jmh AllocationBenchmark -prof gc");
                    System.out.println("    jmh DeadCodeBenchmark");
                    System.out.println("    jmh NanoTimeBenchmark");
                    System.out.println("    jmh .* -f 1 -wi 3 -i 3      (quick, for a smoke test only)");
                    System.out.println();
                    System.out.println("  Running all benchmarks with the annotated settings takes a while.");
                    System.out.println("  Passing every argument through to org.openjdk.jmh.Main now.");
                    System.out.println();
                }
                org.openjdk.jmh.Main.main(rest);
            }

            case "co" -> {
                banner("Coordinated omission",
                        "The same run, measured three ways. Two of them are lying.");
                new CoordinatedOmissionDemo(
                        intArg(rest, 0, 100_000),   // target rate
                        intArg(rest, 1, 10),        // seconds
                        intArg(rest, 2, 10_000),    // stall, microseconds
                        intArg(rest, 3, 2_000)      // stall period, milliseconds
                ).run();
            }

            case "jlbh" -> {
                banner("Level 2: JLBH, open loop",
                        "Response time under a controlled arrival rate.");
                io.github.serhiibohutskyi.latency.jlbh.OrderGatewayBenchmark.main(rest);
            }

            case "stages" -> {
                banner("JLBH with stage probes",
                        "Locates a regression. Do not quote these numbers.");
                System.setProperty("gateway.bench.probes", "true");
                System.out.println("  Stage probes are ON: four nanoTime() calls per burst sit inside");
                System.out.println("  the window jlbh.sample() reports, and the pipeline is restructured into");
                System.out.println("  three passes so those timestamps have somewhere to go. Run 'jlbh' for");
                System.out.println("  the number you quote, and never add the three stage p99s together.");
                System.out.println();
                io.github.serhiibohutskyi.latency.jlbh.OrderGatewayBenchmark.main(rest);
            }

            case "sweep" -> {
                banner("Throughput sweep",
                        "The question is not how fast it is. It is where the tail turns upward.");
                io.github.serhiibohutskyi.latency.jlbh.ThroughputSweep.main(rest);
            }

            case "percentiles" -> {
                banner("Percentile arithmetic",
                        "Three claims about percentiles, each turned into a number.");
                new PercentileMathDemo().run();
            }

            default -> {
                System.out.println("Unknown command: " + command);
                System.out.println();
                usage();
                System.exit(2);
            }
        }
    }

    /**
     * Consumes any {@code -Dkey=value} arguments and sets them as system properties.
     *
     * <p>Written on the command line as {@code java -jar lab.jar jlbh -Dgateway.batch=1}, those
     * are program arguments, not JVM arguments: the JVM has already started, so it never sees
     * them and the setting silently does nothing. That is an easy hour to lose, so they are
     * honoured here instead.
     *
     * <p>This runs before any command class is touched, which matters because several settings
     * are read into {@code static final} fields at class-initialisation time.
     *
     * <p>Note that these reach JMH's forked JVMs only if the benchmark asks for them - a fork
     * is a fresh JVM. Use {@code jvmArgsAppend} for anything a benchmark needs.
     */
    private static String[] applySystemProperties(String[] args) {
        java.util.List<String> remaining = new java.util.ArrayList<>(args.length);

        for (String arg : args) {
            if (arg.startsWith("-D") && arg.length() > 2) {
                String body = arg.substring(2);
                int eq = body.indexOf('=');
                if (eq > 0) {
                    System.setProperty(body.substring(0, eq), body.substring(eq + 1));
                } else {
                    System.setProperty(body, "true");
                }
            } else {
                remaining.add(arg);
            }
        }
        return remaining.toArray(new String[0]);
    }

    private static void banner(String title, String subtitle) {
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  " + title);
        System.out.println("  " + subtitle);
        System.out.println("=".repeat(100));
        System.out.println();
    }

    private static boolean isHelp(String s) {
        return s.equals("-h") || s.equals("--help") || s.equals("help");
    }

    private static int intArg(String[] args, int index, int fallback) {
        if (index >= args.length) {
            return fallback;
        }
        try {
            return Integer.parseInt(args[index].replace("_", "").replace(",", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void usage() {
        System.out.println("""

            Java Low-Latency Testing Lab
            Runnable companion to "How I Test Low-Latency Java Code: From JMH to Tail Latency"

              Usage:  java -jar target/lab.jar <command> [args]

              Run them in this order - it is the order the article argues for.

              PLATFORM  establish the floor before measuring anything
                hiccup [seconds]          Idle-JVM platform floor, jHiccup-style. Default 30 s.
                                          If this box hiccups 4 ms, no Java change gets you under it.
                clock                     Cost and resolution of System.nanoTime() here.
                                          Decides what is measurable at all.

              LEVEL 1   isolate a hot path (closed loop, service time)
                jmh <filter> [jmh args]   JMH. Everything after the filter goes to JMH itself.
                                          jmh OrderEncoderBenchmark -prof gc
                                          jmh AllocationBenchmark -prof gc   <- gc.alloc.rate.norm
                                          jmh DeadCodeBenchmark              <- constant return trap
                                          jmh NanoTimeBenchmark

              LEVEL 2   drive it open loop (response time, queueing visible)
                co [rate] [secs] [stall_us] [period_ms]
                                          Coordinated omission, measured three ways at once.
                                          Default: 100000 10 10000 2000
                jlbh [args]               The article's gateway workload at a fixed arrival rate.
                stages [args]             Same, with per-stage probes on.
                sweep [args]              Sweep the arrival rate until the tail turns upward.

              ANALYSIS
                percentiles               Why averaging percentiles, composing them across
                                          stages, and quoting p99.99 from 10k samples are wrong.

              Results are written to ./results/*.hgrm - plot them at
              https://hdrhistogram.github.io/HdrHistogram/plotFiles.html

              A note before you read any number this prints: on a developer machine, running
              next to a browser, a container runtime and an IDE, the absolute figures describe
              the laptop as much as the code. Run 'hiccup' first so you know which is which.
            """);
    }
}
