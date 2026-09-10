package io.github.serhiibohutskyi.latency.jlbh;

import net.openhft.chronicle.jlbh.JLBH;
import net.openhft.chronicle.jlbh.JLBHOptions;

/**
 * The article's workload, verbatim.
 *
 * <p>The question this asks is not "how fast is the encoder" but "what happens to
 * order-processing latency when messages arrive at roughly 200,000 per second".
 *
 * <p>Reading the output:
 *
 * <ul>
 *   <li><b>Check the achieved rate first.</b> With coordinated-omission accounting on, JLBH
 *       does not slow down when it falls behind, so if the component is persistently slower
 *       than the target rate, measured latency grows without bound. That is the correct
 *       signal, but it means a divergent queue is not a steady state and its p99 does not
 *       mean anything. If the run did not achieve the rate you asked for, lower the rate
 *       before reading anything else.</li>
 *   <li><b>The OS jitter row is a separate probe</b>, not part of end-to-end latency. It is
 *       the platform's contribution measured during the same run - compare it against what
 *       the {@code hiccup} command reported for an idle JVM.</li>
 *   <li><b>{@code runs(5)} prints five runs.</b> Do not average their p99s. Percentiles do
 *       not average: there is no arithmetic that turns five percentiles back into a
 *       distribution. Run the {@code percentiles} command to see how wrong it gets.</li>
 * </ul>
 *
 * <p>Tunable from the command line, so the same class can serve the sweep:
 * {@code -Dgateway.throughput=200000 -Dgateway.iterations=1000000 -Dgateway.runs=5}.
 */
public final class OrderGatewayBenchmark {

    private OrderGatewayBenchmark() {
    }

    public static void main(String[] args) {
        int throughput = Integer.getInteger("gateway.throughput", 200_000);
        int iterations = Integer.getInteger("gateway.iterations", 1_000_000);
        int warmUp = Integer.getInteger("gateway.warmup", 300_000);
        int runs = Integer.getInteger("gateway.runs", 5);

        OrderGatewayLatencyTask task = new OrderGatewayLatencyTask();
        int batch = task.batchSize();

        System.out.printf("  target arrival rate  %,d bursts/s (one every %,d ns)%n",
                throughput, 1_000_000_000L / throughput);
        System.out.printf("  burst size           %d messages%n", batch);
        System.out.printf("  effective rate       %,d msg/s%n", (long) throughput * batch);
        System.out.printf("  iterations           %,d per run, %d runs%n", iterations, runs);
        System.out.printf("  warm-up              %,d iterations%n", warmUp);
        System.out.printf("  stage probes         %s%n",
                OrderGatewayLatencyTask.probesEnabled()
                        ? "ON (restructured pipeline; do not quote these numbers)" : "off");
        System.out.printf("  estimated duration   ~%.0f s%n%n",
                (double) iterations * runs / throughput + 5);
        System.out.println("  Latency below is the time to process one burst, not one message.");
        System.out.println("  A single message is smaller than this platform's clock resolution -");
        System.out.println("  run the 'clock' command to see by how much. Set -Dgateway.batch=1 to");
        System.out.println("  reproduce the degenerate case where every percentile is a clock tick.");
        System.out.println();

        JLBHOptions options = new JLBHOptions()
                .warmUpIterations(warmUp)
                .iterations(iterations)
                .throughput(throughput)
                .runs(runs)
                .recordOSJitter(true)
                .accountForCoordinatedOmission(true)
                .jlbhTask(task);

        new JLBH(options).start();

        double achieved = task.lastRunAchievedRate();

        System.out.println();
        System.out.println("  " + "-".repeat(76));
        System.out.printf("  achieved arrival rate (last run)  %,.0f bursts/s  (target %,d)%n",
                achieved, throughput);

        if (achieved < throughput * 0.95) {
            System.out.println();
            System.out.println("  ! The run did NOT achieve the requested rate.");
            System.out.println("  ! With coordinated-omission accounting on, JLBH does not slow down when it");
            System.out.println("  ! falls behind, so measured latency grows without bound. That is the correct");
            System.out.println("  ! signal, but a divergent queue is not a steady state and its p99 does not");
            System.out.println("  ! mean anything. Lower -Dgateway.throughput before reading the percentiles.");
        } else {
            System.out.println("  The run kept up, so the percentiles above describe a steady state.");
        }

        System.out.println("  " + "-".repeat(76));
        System.out.println();
        System.out.println("  Two reminders before quoting anything from the table above:");
        System.out.println("    - The OS Jitter row is a separate probe, not part of end-to-end latency.");
        System.out.println("      Compare it against what 'hiccup' reported for an idle JVM on this box.");
        System.out.println("    - Do not average the five per-run p99 columns. Percentiles do not average.");
        System.out.println("      Run 'percentiles' to see how large that error gets.");
    }
}
