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

## Results this project produced on a quiet developer laptop

Windows 11, 16 logical cores, Temurin JDK 21.0.9, G1. Every number below comes from **one sitting
with the machine deliberately quieted**: browser, IDE, Edge, Teams, WhatsApp, Phone Link and
Widgets all closed, idle CPU 3–6%. What could not be closed, and therefore sets the residual
floor: an editor, a shell, Windows Defender, and the usual system services.

**These are still not good absolute numbers and are not meant to be.** They are here so you can
tell whether your own run behaves similarly, and because several of them are more interesting than
a clean result would have been.

### What quieting the machine changed, and what it did not

The same suite had been run earlier with a browser and an IDE open. The comparison is the single
most useful thing in this README, because it says which numbers are properties of the code and
which are properties of the room:

| | noisy machine | quiet machine | moved? |
|---|---|---|---|
| encoder DIRECT | 2.672 ± **0.221** | 2.343 ± **0.044** | error bar 5× tighter |
| encoder HEAP | 3.578 ± **0.373** | 2.990 ± **0.021** | error bar 18× tighter |
| `nanoTime()` cost | 29.191 ns | 29.546 ns | no |
| clock resolution | 99 ns | 100 ns | no |
| idle-JVM floor, p99 | 2.0 ms | 2.0 ms | no |
| idle-JVM floor, max | 2.8 ms | 2.8 ms | no |
| sweep: rate it sustained | collapsed at ρ = 0.70 | held to ρ = 0.80 | yes, a lot |
| `stages` worst case | 62 ms | 331 µs | yes, ~200× |

Two conclusions, and they point in opposite directions.

The **measurements of small, CPU-bound things became far more precise** — the error bars collapsed,
and a comparison that was inconclusive on the noisy machine became decisive. That is the case for
benchmarking on a quiet box.

But the **platform floor did not move at all**. p99 2.0 ms and max 2.8 ms on an idle JVM, with the
machine busy or quiet. No amount of closing applications got this laptop below a ~3 ms floor,
because that floor is scheduler and power-management behaviour, not competition for CPU. If your
p99.99 target is under 3 ms, this machine cannot verify it however tidy you make it.

### The platform floor, and a failed attribution

`hiccup 60` on the quiet machine — an idle JVM, no benchmark, no load:

```
count      40,594
mean       491.5 us
p50        531.5 us
p90        999.9 us
p99        2.0 ms
p99.9      2.3 ms
p99.99     2.6 ms
max        2.8 ms
```

The earlier noisy session reported a **median of 140.7 µs** for the same command — better than the
quiet machine's 531.5 µs, which is the wrong way round. Worth chasing, so I did, and the chase is
more instructive than a tidy answer would have been.

`HiccupMeter` sleeps 1 ms and measures how late it wakes. The system timer resolution was 1.00 ms,
and a 1 ms tick predicts a median overshoot of about half a tick — almost exactly the 531 µs
observed. A background application holding a finer timer request would explain everything. So:
force the resolution finer and measure the same percentile again.

| condition | timer resolution | p50 | p99 | max |
|---|---|---|---|---|
| quiet | 1.00 ms | 531.5 µs | 2.0 ms | 2.8 ms |
| quiet, 0.5 ms forced | 0.50 ms | **500.0 µs** | 1.5 ms | 2.4 ms |
| quiet, 4 threads spinning | 1.00 ms | **998.9 µs** | 2.0 ms | 3.0 ms |

**The median did not move.** Halving the timer resolution changed p50 by 6%, so timer granularity
was not the cause. Keeping the cores awake — the C-state hypothesis, next on the list — made it
*worse*, not better, which is what contention predicts and not what core parking predicts.

So I cannot reproduce the 140.7 µs median, and I am not going to invent a mechanism for it. Two
hypotheses tested, two rejected, and this is exactly the discipline the article argues for: if the
percentile does not move, the correlation was not the cause, and you have just saved yourself from
optimising the wrong thing.

The part that *is* robust is the part that matters. Across all three conditions — quiet, finer
timer, four cores spinning — **p99 stayed at 1.5–2.0 ms and max at 2.4–3.0 ms.** The floor's
median is not a stable property of this machine. The floor's tail is, and the tail is what caps
your application.

### The clock is not free, and on Windows it is coarse

```
cost of one nanoTime() call        27 ns
smallest non-zero delta           100 ns
consecutive reads that were equal 1,386,001 of 2,000,000 (69.3%)
cost of a stage probe (2 calls)    54 ns
```

`System.nanoTime()` here is backed by QueryPerformanceCounter at about 10 MHz. **A single message
through this pipeline costs a few nanoseconds, so it is an order of magnitude smaller than the
ruler** — and 69% of consecutive reads return the *same value*. The first JLBH run of this project
duly reported a p50 of `0.001 us`: a picture of the clock, not of the encoder.

That is why one scheduled arrival delivers a *burst* of 128 messages (`-Dgateway.batch`), which is
also what a real market-data feed does. Run configuration 12 reproduces the degenerate
single-message case, and it is worth doing once.

These three figures were identical on the noisy machine (29 ns, 99 ns, 69.1%). Clock cost is a
property of the platform, not of the room.

For contrast, the same command on the Linux CI runner:

| | Windows 11 laptop | Linux CI runner |
|---|---|---|
| clocksource | QueryPerformanceCounter | `tsc` |
| cost of one call | 27 ns | 17 ns |
| smallest non-zero delta | **100 ns** | **18 ns** |
| consecutive reads that were equal | 69.3% | 0.0% |
| idle-JVM hiccup, max | 2.8 ms | 245 µs |

Five times the clock resolution and ten times the platform floor, for the same code. Which of the
two you measure on decides what is measurable at all — and the shared, virtualised CI runner is the
*better* machine here.

### The work is smaller than the clock that measures it

The whole decode → risk → encode pipeline behind a single `@Benchmark`, next to the cost of reading
the clock — both measured the same way in the same sitting:

```
Benchmark                            Mode  Cnt   Score   Error  Units
GatewayPipelineBenchmark.pipeline    avgt   24   7.410 ± 0.562  ns/op

NanoTimeBenchmark.currentTimeMillis  avgt   16   4.359 ± 0.484  ns/op
NanoTimeBenchmark.nanoTime           avgt   16  29.546 ± 0.877  ns/op
NanoTimeBenchmark.nanoTimePair       avgt   16  57.452 ± 0.753  ns/op
```

The entire pipeline costs **7.4 ns**. One `System.nanoTime()` call costs **29.5 ns**, and the
`t1 - t0` pair you would need in order to time the pipeline costs **57.5 ns** — nearly **eight times
the work being measured**.

So the obvious instrumentation is not a small overhead on this operation. It is the measurement
*replacing* the thing measured, and no amount of averaging recovers from it. This is the concrete
reason the encoder benchmark uses `AverageTime` rather than `SampleTime`: amortise across many
invocations, and never timestamp an individual one at this scale.

Note `currentTimeMillis` at 4.4 ns — about seven times cheaper than `nanoTime`, and useless for
latency because its resolution is milliseconds. Cheap and wrong is still wrong.

### JMH: what a quiet machine bought

```
Benchmark                     (bufferKind)  Mode  Cnt  Score   Error  Units
OrderEncoderBenchmark.encode        DIRECT  avgt   24  2.343 ± 0.044  ns/op
OrderEncoderBenchmark.encode          HEAP  avgt   24  2.990 ± 0.021  ns/op
```

DIRECT is about **28% faster**, and the intervals `[2.299, 2.387]` and `[2.969, 3.011]` are nowhere
near each other.

Compare the *same benchmark, same settings*, with a browser and an IDE open: `2.672 ± 0.221` and
`3.578 ± 0.373`. Those overlap far more, and a short smoke run on that machine
(`-f 1 -wi 3 -i 3`) gave `3.223 ± 1.392` against `3.851 ± 4.482` — completely inconclusive.

Three runs of one benchmark, three different verdicts:

| run | DIRECT | HEAP | conclusion supported |
|---|---|---|---|
| smoke, noisy machine | 3.223 ± 1.392 | 3.851 ± 4.482 | none — intervals overlap entirely |
| annotated, noisy machine | 2.672 ± 0.221 | 3.578 ± 0.373 | DIRECT faster, but ±14% error |
| annotated, quiet machine | 2.343 ± 0.044 | 2.990 ± 0.021 | DIRECT faster by 28%, ±2% error |

The smoke run is not *wrong*; it is **inconclusive**, and inconclusive looks exactly like a result
if you only read the Score column. Read the Error column first, every time — and note that the
environment moved the error bar by 18× while barely moving the score.

### Three of five "obvious" allocation regressions allocate nothing

`jmh AllocationBenchmark -prof gc`:

| Benchmark | Regression | ns/op | `gc.alloc.rate.norm` |
|---|---|---|---|
| `zeroAlloc` | none | 2.524 ± 0.142 | ≈ 10⁻⁵ B/op |
| `allocatesRecord` | `new Order(...)` per message | 2.491 ± 0.053 | **≈ 10⁻⁵ B/op** — scalar replaced |
| `usesBigDecimal` | `new BigDecimal(...)` | 2.753 ± 0.474 | **≈ 10⁻⁵ B/op** — scalar replaced |
| `autoboxes` | a `Long` outside the cache | 3.740 ± 0.145 | 24 B/op |
| `buildsString` | `String.valueOf` + concat | 20.156 ± 0.166 | 72 B/op |

The record and the `BigDecimal` never escape the benchmark method, so escape analysis proves they
cannot be observed and scalar replacement deletes them. The profiler then honestly reports
essentially zero bytes for code that plainly contains a `new`. Note `allocatesRecord` is even a
hair *faster* than `zeroAlloc`, well inside error — there is nothing left of the allocation to cost
anything.

Two consequences, and the second is the one that bites. Reading the source and counting allocations
is not a substitute for measuring — and a green `gc.alloc.rate.norm` in a microbenchmark does not
prove the same code allocates nothing in production, where the object may well escape into a queue,
a log line or a callback. Measure the shape you actually ship.

The two that do allocate show why the byte count matters more than the time. `autoboxes` costs
1.2 ns more than `zeroAlloc` and `buildsString` costs 17.6 ns more — but the number to put in CI is
24 B/op and 72 B/op, because those are deterministic while a rate is not. Turn them into a
prediction with the article's arithmetic: 24 B/op at 200k msg/s is 4.8 MB/s, a young collection
every 53 seconds on a 256 MB Eden, or every 14 minutes on a 4 GB one.

### The dead-code guard: three benchmarks that refuse to separate

```
                              quiet machine        noisy machine
DeadCodeBenchmark.blackholeConsumed  2.977 ± 0.553      2.684 ± 0.366
DeadCodeBenchmark.constantReturn     3.701 ± 0.381      2.951 ± 0.476
DeadCodeBenchmark.derivedReturn      3.044 ± 0.443      3.516 ± 0.232
```

`constantReturn` ends with `return buffer.position()`, which is always 21. By the usual telling that
is no guard at all and the JIT should fold the encode away, leaving a number far below the others.
It did not — and the ordering of the three **flips between runs**: `constantReturn` is the fastest
of the three on the noisy machine and the slowest on the quiet one.

So the honest reading is that these three are indistinguishable, and any story about which is
faster is a story about noise. (An earlier version of this README claimed `derivedReturn` was
reliably slowest because it does more work, and priced the guard at ~0.8 ns. The quiet run
contradicts it. The claim is withdrawn.)

The reason they cannot separate is in the benchmark body: all three write into a `ByteBuffer` held
in a field, so the writes are side effects on an object that outlives the method. Escape analysis
cannot prove they are unobservable, so they happen whatever the method returns. **The constant
return is harmless here.**

Which is exactly the qualification the article makes: a constant return is no guard *in a benchmark
whose body is pure computation*. Change the body to something that leaves no trace — arithmetic
into a local, a hash, a comparison — and the same constant return lets the whole thing vanish. The
guard costs nothing measurable, and it insures against a property of the body that is easy to
change by accident.

### Averaging five p99s was 733% wrong, in both directions

`percentiles` is a seeded simulation, so it is deterministic and produces the same table on any
machine — which makes it the one command here you can check your reading against rather than your
hardware:

```
  run                           p50        p90        p99      p99.9     p99.99          max
  ----------------------------------------------------------------------------------------------
  run 1                     15.4 us    25.1 us    58.6 us   105.3 us     3.3 ms       5.1 ms
  run 2                     15.4 us    25.2 us    58.8 us   310.3 us     3.7 ms       5.9 ms
  run 3                     15.4 us    25.2 us    59.1 us   511.0 us     3.8 ms       8.2 ms
  run 4 (bad patch)         15.5 us    27.4 us     2.3 ms     9.3 ms     9.9 ms      10.2 ms
  run 5                     15.4 us    25.0 us    58.9 us   502.3 us     3.7 ms       6.1 ms
  ----------------------------------------------------------------------------------------------
  MERGED (correct)          15.4 us    25.5 us    61.1 us     6.0 ms     9.6 ms      10.2 ms

                                              p99        p99.9
  mean of the five percentiles           509.1 us       2.2 ms
  percentile of the merged data           61.1 us       6.0 ms
  error from averaging                     733.0%       -64.3%
```

Averaging the five p99s overstates the true p99 by **733%** — the bad run drags the mean up.
Averaging the five p99.9s *understates* the true p99.9 by **64%** — four healthy runs dilute it.

The opposite signs are the point. Averaging percentiles is not a rough approximation with a known
direction you could correct for; it is an operation without meaning. `Histogram.add()` keeps every
observation and re-reads the percentile from the real distribution.

The same command shows that summing per-stage p99s lands on the wrong side in *either* direction
depending only on distribution shape: **+7%** high for light-tailed independent stages, **−15%** low
for heavy-tailed ones, and **−50%** low once the stages are correlated — which is precisely what a
safepoint or a descheduled thread produces. "Add the stage p99s" is not a conservative bound.

And on sample counts, from the same run: p99 settles by 1,000 samples, but p99.99 is still moving at
100,000 samples because it is computed from about ten observations. Quoting it there is quoting
noise.

### Coordinated omission: the same run, measured three ways

```
what was measured             p50        p90        p99      p99.9     p99.99          max
----------------------------------------------------------------------------------------------
1. service time           0.60 us    1.00 us    1.50 us    1.90 us    10.6 us      10.1 ms
2. response time          0.60 us    1.10 us    2.20 us     7.8 ms     9.8 ms      10.2 ms
3. HdrHistogram patch     0.60 us    1.00 us    1.60 us     7.5 ms     9.8 ms      10.1 ms

Samples above 5.0 ms:  service time 4      response time 2,193
achieved rate  99,999 bursts/s (target 100,000)  ok
```

Service time says p99.9 = **1.90 µs**. Response time says p99.9 = **7.8 ms**, about four thousand
times worse. Both describe the same run of the same code on the same machine, in the same second.

The last line is the mechanism: the application froze four times, and service time recorded exactly
four bad samples — while **2,193** arrivals were actually late. The measurement stopped sampling
precisely while the system was failing. That is coordinated omission, and note that no load
generator was involved: row 1 is what an ordinary `onMessage()` timer records.

Row 3 is the honest caveat. HdrHistogram's `recordValueWithExpectedInterval` lands close to the
truth *here* only because arrivals in this harness are perfectly uniform. It reconstructs samples
that were never taken; it cannot reproduce what a real backlog does to a system — buffers filling,
cache lines displaced, GC triggered by everything that piled up.

### The tail turns upward at half the sustainable rate

```
  rho    target/s  achieved/s       p50       p99     p99.9    p99.99       max p99.9/p50  1/(1-r)
----------------------------------------------------------------------------------------------------
 0.10     105,199     104,037   1.00 us   1.10 us   1.40 us   12.6 us   88.4 us      1.4x     1.1x
 0.30     315,597     311,932   1.00 us   1.10 us   1.63 us   11.5 us   57.6 us      1.6x     1.4x
 0.50     525,995     499,460   1.00 us   1.10 us   7.40 us  227.5 us  363.3 us      7.4x     2.0x  <- DID NOT KEEP UP
 0.70     736,393     713,778   1.00 us   1.50 us   12.9 us  235.0 us  303.4 us     12.9x     3.3x
 0.80     841,592     833,299   1.00 us   4.60 us  189.7 us  286.0 us  303.6 us    189.7x     5.0x
 0.90     946,791     911,844   24.2 ms   74.8 ms   76.9 ms   77.0 ms   77.1 ms      3.2x    10.0x
 0.95     999,390     907,900   49.4 ms  195.3 ms  202.1 ms  202.8 ms  202.8 ms      4.1x    20.0x  <- DID NOT KEEP UP
 1.05   1,104,589     911,271  187.2 ms  377.5 ms  383.8 ms  384.8 ms  384.8 ms      2.1x      inf  <- DID NOT KEEP UP
```

**p50 is pinned at 1.00 µs for the first five rows.** Every one of them would support the claim
"median latency is one microsecond". Meanwhile p99.9 goes 1.40 → 1.63 → 7.40 → 12.9 → 189.7 µs, and
by ρ = 0.90 the median itself has collapsed to 24.2 ms.

The knee is at about **ρ = 0.50** — half the sustainable rate, not 100%. That is the concrete
version of why "we're only at 70% CPU" is not the reassurance people think it is. And it is the
number to report: not a p99 detached from the rate it was measured at, but *the rate at which the
tail turns upward*.

Compare the `1/(1-ρ)` column, which is what a textbook M/M/1 queue predicts, against the measured
`p99.9/p50`. At ρ = 0.80 theory says the queue should be 5× longer; the measured tail ratio is
190×. The excess is service-time variability — for the same average service time, a more variable
one produces a much longer queue, which is why a component with a good p50 and a ragged p99
saturates earlier than one that is slower on average but tight.

Rows marked `DID NOT KEEP UP` have a divergent queue. That is not a steady state and its p99 does
not mean anything: read such a row as "cannot sustain this rate", never as "latency is N ms".

The floor is visible in this table too. At ρ = 0.10 the component is nearly idle, yet p99.99 is
12.6 µs and max is 88.4 µs. That is not queueing and not the encoder — it is the machine, and no
change to this code moves that column.

### The tail is not reproducible, and JLBH prints a column that says so

`jlbh` at 200,000 bursts/s, five runs of a million iterations, coordinated omission accounted for:

```
Percentile   run1         run2         run3         run4         run5      % Variation
50.0:            0.60         1.10         1.10         1.10         1.10         0.00
90.0:            1.10         1.10         1.10         1.10         1.10         0.00
99.0:            1.10         1.10         1.10         1.10         1.10         0.00
99.7:            1.20         1.20         1.10         1.10         1.10         5.70
99.9:            7.80         3.30         1.50         1.20         1.20        53.78
99.97:         482.82       160.51         4.30         2.90         3.00        97.31
99.99:        1267.71       764.93         8.69         9.20         8.01        98.44
worst:        1648.64       953.34        62.40        79.74        41.92        93.55

achieved arrival rate (last run)  199,646 bursts/s  (target 200,000)
```

Read the last column downwards. p50, p90 and p99 are perfectly reproducible at **0.00%** variation.
p99.9 moves by **53.78%**. p99.99 moves by **98.44%** — run 1's p99.99 is **158 times** run 5's,
from identical code on a quiet machine, minutes apart.

Runs 1 and 2 caught something; runs 3–5 did not. This is what "say how many samples are behind the
number" looks like in practice: a single p99.99 from a single run is not a property of the system,
it is one draw from a distribution that is itself enormously wide. If you are comparing two builds,
a 100× "regression" at p99.99 is well inside this noise — and the warm-up ordering is the obvious
suspect, since the two bad runs are the first two.

The achieved-rate line matters just as much: the run kept up, so these percentiles describe a
steady state. Had it fallen short, the queue would be divergent and none of the numbers would mean
anything.

### What the stage probes cost, measured

The same workload with `stages` (per-stage probes on) against `jlbh` (probes off), both on the quiet
machine:

| | probes off | probes on |
|---|---|---|
| p50 | 0.60 – 1.10 µs | 0.90 – 1.40 µs |
| p99 | 1.10 µs | 1.50 µs |
| p99.9 | 1.20 – 7.80 µs | 1.70 – 4.10 µs |
| p99.99 | 8.01 – 1,268 µs | 19.8 – 156 µs |
| worst | 41.9 – 1,649 µs | 122 – 331 µs |

The median cost is the honest part: about **+0.3 µs per burst**, which is the four extra
`nanoTime()` calls sitting inside the window `jlbh.sample()` reports. p99 pays +0.4 µs. That is the
tax you accept in exchange for knowing which stage moved, and it is why the instrumented run is not
the number to quote.

The tail is where I have to correct an earlier claim. On the noisy machine the instrumented run
looked catastrophic — a p99 of 25.7 ms and a worst of 62 ms — and this README previously concluded
that instrumentation makes the tail "a different system". **On the quiet machine that reverses:**
the instrumented run's worst case (331 µs) is *better* than the uninstrumented one's (1,649 µs),
because run-to-run outlier noise is far larger than the probes' cost. The 62 ms was the room, not
the probes.

The conclusion that survives is narrower and duller: probes cost a few hundred nanoseconds at the
median, and at the tail you cannot tell what they cost, because the tail is dominated by variance
you do not control. Still do not quote an instrumented run — but because it is measurably slower at
the median and the tail is unattributable, not because it becomes a different system.

The stage rows also demonstrate non-composition directly. Per-stage p99s were decode 0.70 µs, risk
0.50 µs and encode 0.40 µs, summing to **1.60 µs** — against a measured end-to-end p99 of
**1.50 µs**. Here the sum *overestimates*, which is scenario A from the `percentiles` command:
light-tailed, roughly independent stages whose bad moments rarely coincide. On a worse day the same
sum underestimates. The probes located where the time went; they did not add up to the answer.

---

## Read this before believing any number here

The `hiccup` command on the quiet development machine reported:

```
p50        531.5 us
p99        2.0 ms
p99.99     2.6 ms
max        2.8 ms
```

That is an **idle** JVM, on a machine with the browser and the IDE shut down. Nothing running, no
benchmark, no load — and it was already 2.8 ms late at worst. So on that machine no p99.99 below
roughly 3 ms means anything, no matter what the application does, and every microsecond-scale
figure above should be read as a *relative* comparison rather than an absolute latency.

That floor held at p99 2.0 ms whether the machine was busy, quiet, or running with a forced 0.5 ms
timer resolution, so it is not something you tidy your way out of.

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
