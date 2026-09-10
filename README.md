# Java Low-Latency Testing Lab

A runnable companion to the article **"How I Test Low-Latency Java Code: From JMH to Tail Latency"**.

Every claim the article makes is a command here that prints real numbers on your machine: JMH
microbenchmarks, an open-loop JLBH harness, a coordinated-omission demonstration measured three
ways at once, a throughput sweep that finds where the tail turns upward, a jHiccup-style platform
floor, and the percentile arithmetic that most benchmark reports get wrong.

```bash
mvn clean package
java -jar target/lab.jar
```

---

## Quick start

```bash
mvn clean package

java -jar target/lab.jar hiccup 30     # 1. what is this machine's floor?
java -jar target/lab.jar clock         # 2. what can the clock even resolve?
java -jar target/lab.jar percentiles   # 3. percentile arithmetic (fast, no benchmarking)
java -jar target/lab.jar jmh OrderEncoderBenchmark
java -jar target/lab.jar co            # 4. coordinated omission
java -jar target/lab.jar jlbh          # 5. open loop at a fixed arrival rate
java -jar target/lab.jar sweep         # 6. where does the tail turn upward?
```

Run them in that order. It is the order the article argues for: establish the platform floor and
the clock's resolution **before** measuring anything, because both of them cap what any later
number can mean.

### From IntelliJ IDEA

Open the folder as a Maven project. Fourteen shared run configurations are committed under
`.idea/runConfigurations/` and appear in the run dropdown already numbered in the intended order:

| | Configuration | What it shows |
|---|---|---|
| 0 | Build (mvn clean package) | Builds `target/lab.jar` |
| 1 | Platform floor (hiccup) | The latency floor of this machine |
| 2 | Clock probe | Cost and resolution of `System.nanoTime()` |
| 3 | Percentile arithmetic | Averaging, composing and under-sampling percentiles |
| 4 | JMH – encoder (DIRECT vs HEAP) | The article's microbenchmark |
| 5 | JMH – allocation (`-prof gc`) | `gc.alloc.rate.norm`, the CI-assertable number |
| 6 | JMH – dead code trap | What a constant return costs you |
| 7 | JMH – nanoTime cost | The ruler, measured |
| 8 | Coordinated omission | Service time vs response time, same run |
| 9 | JLBH – gateway open loop | Response time at a controlled arrival rate |
| 10 | JLBH – stage probes | Which stage moved (not a number to quote) |
| 11 | Throughput sweep | Where the tail turns upward |
| 12 | JLBH – batch=1 | The degenerate case: every percentile is a clock tick |
| 13 | JMH – all (quick smoke test) | Every benchmark, fast and statistically worthless |

If IDEA reports no benchmarks for the JMH configurations, run configuration 0 once. JMH generates
its harness with an annotation processor, and those generated classes have to exist before the
benchmarks can be found.

**Requirements:** JDK 17 or newer and Maven 3.8+. Built and verified on JDK 21.

---

## What each command demonstrates

### `hiccup [seconds]` — the platform floor

A jHiccup in about forty lines: sleep 1 ms, measure how late you actually woke up. That captures
JVM pauses *and* OS, hypervisor and hardware stalls, while the application does nothing at all.

The usual advice is to reach for jHiccup when a spike is unexplained. Turn it around: **run it
first.** If an idle JVM on this box shows a 3 ms hiccup, no amount of Java optimisation will get
your p99.99 below 3 ms here — and you have learned that in thirty seconds instead of spending a
week blaming your own code.

### `clock` — the ruler

Measures what one `System.nanoTime()` call costs and the smallest non-zero delta it can report.
On Linux it also reads the current clocksource, because `tsc` versus `hpet` is the difference
between a benchmark you can believe and one you cannot.

This command exists because it decides what is measurable at all. See "The clock is not free"
below for what it turned up on the development machine.

### `percentiles` — the arithmetic

Runs in a couple of seconds, no benchmarking involved, and makes three claims concrete:

1. **Percentiles do not average.** Five runs, one of which hit a bad patch. Averaging the five
   p99s overstates the true p99 several times over; averaging the five p99.9s *understates* the
   true p99.9. The errors have opposite signs, which is the real lesson — averaging percentiles
   is not an approximation with a correctable direction, it is an operation without meaning.
   `Histogram.add()` is the only correct way to combine runs.
2. **Percentiles do not compose across stages.** Three scenarios, same question each time.
3. **Sample counts matter.** p99.99 computed from ten observations is noise.
4. **HdrHistogram buckets values**, so printing `p99.9 = 410 ns` implies precision the data
   structure does not carry.

### `jmh <filter>` — Level 1, closed loop

Everything after the filter is passed straight to `org.openjdk.jmh.Main`.

- `OrderEncoderBenchmark` — the article's encoder, `@Param`-ed across direct and heap buffers.
- `AllocationBenchmark` — run it with `-prof gc` and read `gc.alloc.rate.norm`.
- `DeadCodeBenchmark` — returning a constant is no guard at all.
- `NanoTimeBenchmark` — the cost of the clock.
- `GatewayPipelineBenchmark` — the whole pipeline behind `@Benchmark`, to make the point that
  "microbenchmarks aren't realistic" is the wrong complaint. JMH measures a whole pipeline
  perfectly well. The real limit is that it is **closed-loop**: the next operation starts when
  the last one returns, so no queue can ever form, and what it reports is service time at 100%
  utilisation of one thread.

### `co` — coordinated omission

The centrepiece. Drives the pipeline open-loop at a fixed rate, freezes the application for 10 ms
every 2 seconds, and records **every arrival three ways from the same run**: service time (the
CO-afflicted number), response time (measured from the slot the message belonged to), and
HdrHistogram's `recordValueWithExpectedInterval` correction.

Arguments: `co [rate] [seconds] [stall_us] [stall_period_ms]`, default `co 100000 10 10000 2000`.

### `jlbh` / `stages` — Level 2, open loop

The article's gateway workload under JLBH at a controlled arrival rate, with
`accountForCoordinatedOmission(true)` and OS-jitter recording. `stages` turns on the per-stage
probes.

Tunable: `-Dgateway.throughput=200000 -Dgateway.iterations=1000000 -Dgateway.runs=5
-Dgateway.batch=128`.

### `sweep` — where the tail turns upward

Calibrates the component's capacity, then drives it open-loop at 10%, 30%, 50%, 70%, 80%, 90%,
95% and 105% of that rate. The output table carries a `1/(1-rho)` column — the queue-length
multiplier a textbook M/M/1 queue predicts — next to the measured `p99.9/p50` ratio, so you can
watch the measured tail outrun the theory. That gap is variability, which is exactly why a
component with a good p50 and a ragged p99 saturates earlier than one that is slower on average
but tight.

The question it answers is not "how fast is this component" but **"at what rate does the tail
turn upward"** — and that rate, not a single p99, is the number to report.

---

## Results this project produced on a developer laptop

Windows 11, 16 logical cores, Temurin JDK 21.0.9, G1, an ordinary machine with a browser and an
IDE open. **These are not good absolute numbers and are not meant to be** — see "Read this before
believing any number here" below. They are included so you can tell whether your own run is
behaving similarly, and because several of them are more interesting than a clean result would be.

### The clock is not free, and on Windows it is coarse

```
cost of one nanoTime() call        29 ns
smallest non-zero delta            99 ns
consecutive reads that were equal  1,381,629 of 2,000,000 (69.1%)
```

`System.nanoTime()` on Windows is backed by QueryPerformanceCounter at about 10 MHz. **A single
message through this pipeline costs a few nanoseconds, so it is smaller than the ruler.** The
first JLBH run of this project duly reported a p50 of `0.001 us` — a picture of the clock, not of
the encoder.

That is why one scheduled arrival delivers a *burst* of messages (`-Dgateway.batch`, default 128),
which is also what a real market-data feed does. Run configuration 12 reproduces the degenerate
single-message case, and it is worth doing once.

### Coordinated omission: the same run, measured three ways

```
what was measured             p50        p90        p99      p99.9     p99.99          max
----------------------------------------------------------------------------------------------
1. service time           0.20 us    0.30 us    0.40 us    0.70 us    5.00 us      10.1 ms
2. response time          0.20 us    0.30 us    2.30 us     7.7 ms     9.9 ms      10.4 ms
3. HdrHistogram patch     0.20 us    0.30 us    0.50 us     7.6 ms     9.8 ms      10.1 ms

Samples above 5.0 ms:  service time 4      response time 2,109
```

Service time says p99.9 = **0.70 µs**. Response time says p99.9 = **7.7 ms**, four orders of
magnitude worse. Both describe the same run of the same code on the same machine.

The last line is the mechanism: the application froze four times, and service time recorded
exactly four bad samples — while 2,109 arrivals were actually late. The measurement stopped
sampling precisely while the system was failing.

Row 3 is the honest caveat. HdrHistogram's interpolation lands close to the truth *here* only
because arrivals in this harness are perfectly uniform. It reconstructs samples that were never
taken; it cannot reproduce what a real backlog does to a system.

### The tail turns upward long before saturation

```
  rho    target/s  achieved/s      p50       p99     p99.9    p99.99  p99.9/p50  1/(1-r)
-----------------------------------------------------------------------------------------
 0.10     147,738     146,552  1.00 us   1.10 us   2.20 us   24.3 us      2.2x     1.1x
 0.30     443,214     432,070  1.00 us   1.10 us    1.2 ms    4.6 ms   1175.7x     1.4x
 0.50     738,691     713,144  1.00 us    1.1 ms    9.6 ms   10.4 ms   9592.8x     2.0x
 0.70   1,034,168     956,718  11.9 ms  152.3 ms  158.1 ms  158.6 ms     13.3x     3.3x  <- DID NOT KEEP UP
```

p50 is flat at 1.00 µs across the first three rows. Every one of them would support the claim
"median latency is one microsecond". Meanwhile p99.9 goes from 2.2 µs to 1.2 ms to 9.6 ms.

Note that the theory column says the queue should only be 1.4× longer at ρ = 0.30. The measured
tail ratio went up 500×. The excess is service-time variability, and it is why "we're only at 70%
CPU" is not the reassurance people think it is.

Rows marked `DID NOT KEEP UP` have a divergent queue. That is not a steady state, and its p99 does
not mean anything: read such a row as "cannot sustain this rate", never as "latency is N ms".

### Three of five "obvious" allocation regressions allocate nothing

`jmh AllocationBenchmark -prof gc`, JDK 21:

| Benchmark | Regression | `gc.alloc.rate.norm` |
|---|---|---|
| `zeroAlloc` | none | 0 B/op |
| `allocatesRecord` | `new Order(...)` per message | **≈0 B/op** — scalar replaced |
| `usesBigDecimal` | `new BigDecimal(...)` | **≈0 B/op** — scalar replaced |
| `autoboxes` | a `Long` outside the cache | 24 B/op |
| `buildsString` | `String.valueOf` + concat | 72 B/op |

The record and the `BigDecimal` never escape the benchmark method, so escape analysis proves they
cannot be observed and scalar replacement deletes them. The profiler then honestly reports 0 B for
code that plainly contains a `new`.

Two consequences, and the second is the one that bites. Reading the source and counting
allocations is not a substitute for measuring — and a green `gc.alloc.rate.norm` in a
microbenchmark does not prove the same code allocates nothing in production, where the object may
well escape into a queue, a log line or a callback. Measure the shape you actually ship.

Turn the number into a prediction with the article's arithmetic — young-GC interval ≈ Eden size ÷
allocation rate. 24 B/op at 200k msg/s is 4.8 MB/s: a collection every 53 seconds on a 256 MB
Eden, or every 14 minutes on a 4 GB one.

### JMH: the encoder

```
Benchmark                     (bufferKind)  Mode  Cnt  Score   Error  Units
OrderEncoderBenchmark.encode        DIRECT  avgt    3  3.223 ± 1.392  ns/op
OrderEncoderBenchmark.encode          HEAP  avgt    3  3.851 ± 4.482  ns/op
```

Note the error bars, which came from a deliberately short smoke-test run. `3.223 ± 1.392` and
`3.851 ± 4.482` overlap completely, so **this run does not show that DIRECT is faster than HEAP.**
It shows the two are indistinguishable at this sample size. Run configuration 4 uses the annotated
settings (3 forks, 5 warm-up and 8 measurement iterations) and takes a few minutes; use that
before drawing a conclusion.

---

## Read this before believing any number here

The `hiccup` command on the development machine reported:

```
p50        140.7 us
p99        2.0 ms
max        2.8 ms
```

That is an **idle** JVM. Nothing running, no benchmark, no load — and it was already 2.8 ms late
at worst. So on that machine no p99.99 below roughly 3 ms means anything, no matter what the
application does, and every microsecond-scale figure above should be read as a *relative*
comparison rather than an absolute latency.

This is the point of running `hiccup` first, and it is not a flaw in the project. If production
runs in a container with a CPU quota, then benchmarking on a tuned bare-metal box tells you about
a system you are not going to deploy. Reproduce the production configuration — and if that
configuration has a 4 ms floor, that is a finding, not an inconvenience.

The things that actually move a p99.9, in rough order of how often they are the culprit and how
rarely they are checked: **container CPU throttling** (`nr_throttled` and `throttled_time` in the
cgroup's `cpu.stat` — check this before anything else), **C-states**, **transparent huge pages**
set to `always`, **page faults** on first touch, and the fact that **pinning is not isolation**
(`taskset` does not stop the kernel scheduling interrupts onto your core).

---

## Project layout

```
domain/       OrderEncoder, OrderDecoder, RiskEngine, GatewayPipeline, OrderMessageSource
jmh/          Level 1: closed-loop microbenchmarks
jlbh/         Level 2: open-loop harness, stage probes, throughput sweep
co/           Coordinated omission, measured three ways at once
hdr/          LatencyRecorder, percentile reporting, percentile arithmetic
platform/     HiccupMeter (the floor), ClockProbe (the ruler)
```

Every class carries the reasoning in its Javadoc rather than in this README, so the explanation
sits next to the code it justifies.

Histograms are written to `results/*.hgrm`. Plot them at
<https://hdrhistogram.github.io/HdrHistogram/plotFiles.html> — the sweep writes one file per
utilisation, which makes the tail lifting off as ρ climbs quite vivid.

`mvn test` runs the correctness tests. A benchmark of broken code is worthless, and several of the
tests pin properties the benchmarks depend on: that the message source exercises both sides of the
risk branch, that the net position does not run away mid-run, and that the instrumented pipeline
computes the same result as the uninstrumented one.

## Tool versions

| Library | Version | Role |
|---|---|---|
| JMH | 1.37 | Isolated JVM microbenchmarks |
| JLBH | 2026.2 | Open-loop latency under controlled throughput |
| HdrHistogram | 2.2.2 | Recording and analysing latency distributions |

Chronicle's usage analytics and startup announcement are disabled in `Launcher`, because a
benchmark project should not be making network calls in the middle of a latency measurement.

## Not covered here

The article's diagnostic layer needs tools that are not JVM libraries and are mostly Linux-only:
**JFR** (`jcmd <pid> JFR.start settings=profile`), **async-profiler** (wall-clock mode for
off-CPU time; `-e cpu` is blind to a descheduled thread by construction), and safepoint logging
(`-Xlog:safepoint`, `-XX:+SafepointTimeout`). The `co` and `sweep` commands will produce outliers
for you to point them at.

And the step that is easy to skip: change exactly one variable and measure the same percentile
again. A correlation in time is not a cause. Without that step, a performance optimisation is an
assumption with a flame graph attached.

## Licence

MIT
