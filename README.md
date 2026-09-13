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

### `report` — regenerate this file's results

Runs the whole suite and writes each table back into README.md between its `BEGIN GENERATED` and
`END GENERATED` markers, so the numbers in the file are the numbers your machine measured rather
than something transcribed by hand. Every command runs in its own JVM, exactly as you would run it.

```bash
java -jar target/lab.jar report                 # full suite, ~20 minutes
java -jar target/lab.jar report --only clock,co # just those blocks
java -jar target/lab.jar report --quick         # reduced settings, to check the plumbing only
```

It reads back its own `hiccup` result and warns you if an idle JVM on this machine was more than
5 ms late, because in that case the machine was busy and none of the run's numbers are worth
committing.

The prose between the tables is hand-written and deliberately does not restate their numbers, so
regenerating on a different machine updates the file without contradicting the text around it.
Cross-run comparisons that no single run can reproduce live in the **Experiment log** at the end,
dated and explicitly not regenerated.

---

## Results

Every table in this section is **generated**, not transcribed. `lab.jar report` runs the suite and
writes each table back into this file between its `BEGIN GENERATED` / `END GENERATED` markers, so
what you read here is what the machine measured. The prose between the tables is hand-written and
deliberately avoids restating their numbers — it explains what to look for, and the table tells you
what happened on the machine that last ran it.

<!-- BEGIN GENERATED: env -->
*Generated by `lab.jar report` on 2026-09-14 — Windows 11 10.0, 16 logical cores, OpenJDK 64-Bit Server VM 21.0.9, G1 Young Generation, G1 Concurrent GC, G1 Old Generation.*
<!-- END GENERATED: env -->

**These are not good absolute numbers and are not meant to be.** They are here so you can tell
whether your own run behaves similarly. Regenerate them on your own machine and the interesting
question is not whether your figures match mine — they will not — but whether the *relationships*
hold: whether your clock is coarser than your work, whether your floor caps your target, and where
your tail turns upward.

One consequence of generating them: the run that produced the tables below is only as quiet as the
machine happened to be. **Read the `hiccup` block first every time.** If its tail is far above what
the "Read this before believing any number here" section describes, the machine was busy and every
microsecond-scale figure in the rest of the section inherits that noise.

### The platform floor

`hiccup` on an idle JVM — no benchmark, no load, nothing but a sleeping thread measuring how late
it wakes:

<!-- BEGIN GENERATED: hiccup -->
```
count      38,539
mean       587.7 us
p50        597.5 us     (19,270 samples above)
p90        1.0 ms       (3,854 samples above)
p99        2.0 ms       (385 samples above)
p99.9      2.5 ms       (39 samples above)
p99.99     3.0 ms       (4 samples above)
max        3.3 ms   <- one observation. Quote it, never optimise against it.
```
<!-- END GENERATED: hiccup -->

This is the ceiling on everything else. Whatever the tail says here, no application p99.99 on this
machine can be better than it, no matter what the code does — so run it before writing a benchmark,
not after one confuses you. The usual advice is to reach for jHiccup when a spike is unexplained;
turn it around and establish the floor first, in sixty seconds, before spending a week attributing
the platform to your own code.

Two things are worth knowing about this particular number, both learned the hard way.

**Its tail is a far more stable property of the machine than its median.** Across a quiet machine, a
machine with a forced 0.5 ms timer resolution, and a machine with four threads spinning, p99 stayed
in a narrow band while the median moved by 2×. The tail is also the half that caps you, which is
convenient.

**Its median resisted attribution.** See the experiment log below: two plausible mechanisms were
tested and both were rejected. That is the article's discipline working as intended — if the
percentile does not move when you change the variable, the correlation was not the cause.

### The clock is not free, and on Windows it is coarse

<!-- BEGIN GENERATED: clock -->
```
cost of one nanoTime() call        28 ns
smallest non-zero delta            100 ns
consecutive reads that were equal  1,393,854 of 2,000,000 (69.7%)
cost of a stage probe (2 calls)    56 ns
```
<!-- END GENERATED: clock -->

`System.nanoTime()` on Windows is backed by QueryPerformanceCounter at about 10 MHz. Compare the
"smallest non-zero delta" line against the per-message cost in the JMH tables below: **a single
message through this pipeline is an order of magnitude smaller than the ruler**, and a large
fraction of consecutive clock reads return the *same value*.

The first JLBH run of this project duly reported a p50 of `0.001 us` — a picture of the clock, not
of the encoder. That is why one scheduled arrival delivers a *burst* of 128 messages
(`-Dgateway.batch`), which is also what a real market-data feed does. Run configuration 12
reproduces the degenerate single-message case, and it is worth doing once to see the failure
directly.

These figures barely move between a busy machine and a quiet one, because clock cost is a property
of the platform rather than of the room. What changes them is the platform itself — see the Linux
comparison in the experiment log.

### The work is smaller than the clock that measures it

The whole decode → risk → encode pipeline behind a single `@Benchmark`, next to the cost of reading
the clock, both measured the same way in the same run:

<!-- BEGIN GENERATED: clockcost -->
```
Benchmark                          Mode  Cnt  Score   Error  Units
GatewayPipelineBenchmark.pipeline  avgt   24  6.957 ± 0.411  ns/op

Benchmark                            Mode  Cnt   Score   Error  Units
NanoTimeBenchmark.currentTimeMillis  avgt   16   3.623 ± 0.203  ns/op
NanoTimeBenchmark.nanoTime           avgt   16  27.943 ± 0.320  ns/op
NanoTimeBenchmark.nanoTimePair       avgt   16  54.699 ± 0.682  ns/op
```
<!-- END GENERATED: clockcost -->

Read those two tables against each other, because the comparison is the whole point:
`nanoTimePair` is the `t1 - t0` you would need in order to time the pipeline, and it costs several
times what the pipeline itself costs.

So the obvious instrumentation is not a small overhead on this operation. It is the measurement
*replacing* the thing measured, and no amount of averaging recovers from it. This is the concrete
reason the encoder benchmark uses `AverageTime` rather than `SampleTime`: amortise across many
invocations, and never timestamp an individual one at this scale.

Note `currentTimeMillis`, which is markedly cheaper than `nanoTime` and useless for latency because
its resolution is milliseconds. Cheap and wrong is still wrong.

### JMH: the encoder, and reading the Error column first

<!-- BEGIN GENERATED: encoder -->
```
Benchmark                     (bufferKind)  Mode  Cnt  Score   Error  Units
OrderEncoderBenchmark.encode        DIRECT  avgt   24  2.438 ± 0.095  ns/op
OrderEncoderBenchmark.encode          HEAP  avgt   24  3.001 ± 0.103  ns/op
```
<!-- END GENERATED: encoder -->

Before reading the Score column, do the arithmetic the Error column is asking you to do: turn each
row into an interval, `Score ± Error`, and check whether the two intervals overlap. If they do,
**this run does not show that one buffer kind is faster than the other** — it shows they are
indistinguishable at this sample size on this machine, which is a different statement and a much
weaker one.

That check is not academic here. The same benchmark, at the same annotated settings, has produced
both verdicts on this laptop depending only on what else was running; and a short smoke run
(configuration 13, `-f 1 -wi 3 -i 3`) produces error bars several times larger again. The
experiment log below records three runs and three different conclusions.

The lesson that survives every re-run: a smoke run is not *wrong*, it is **inconclusive**, and
inconclusive looks exactly like a result if you only read the Score column.

### Allocation: the control, the invisible one, and the loud one

<!-- BEGIN GENERATED: allocation -->
| Benchmark | Regression | ns/op | `gc.alloc.rate.norm` |
|---|---|---|---|
| `zeroAlloc` | none — this is the control | 2.597 ± 0.162 | ≈ 10⁻⁵ B/op |
| `allocatesRecord` | `new Order(...)` per message | 2.620 ± 0.159 | ≈ 10⁻⁵ B/op — **scalar replaced** |
| `usesBigDecimal` | `new BigDecimal(...)` | 2.605 ± 0.135 | ≈ 10⁻⁵ B/op — **scalar replaced** |
| `autoboxes` | a `Long` outside the cache | 3.741 ± 0.225 | 24.000 ± 0.001 B/op |
| `buildsString` | `String.valueOf` + concat | 21.087 ± 1.495 | 72.000 ± 0.001 B/op |
<!-- END GENERATED: allocation -->

`zeroAlloc` is the control, not a regression, so of the **four** actual regressions, the two marked
*scalar replaced* allocate essentially nothing. Neither the record nor the `BigDecimal` escapes the
benchmark method, so escape analysis proves it cannot be observed and scalar replacement deletes
it. The profiler then honestly reports about zero bytes for code that plainly contains a `new` —
and the allocating version lands within a hair of the control in either direction, well inside
error, because there is nothing left of the allocation to cost anything.

Two consequences, and the second is the one that bites. Reading the source and counting allocations
is not a substitute for measuring — and a green `gc.alloc.rate.norm` in a microbenchmark does not
prove the same code allocates nothing in production, where the object may well escape into a queue,
a log line or a callback. Measure the shape you actually ship.

**`autoboxes` is the row that makes the argument for CI.** It allocates 24 bytes per operation —
real, unambiguous, and the kind of thing that accumulates for hours in a gateway — while costing
about a nanosecond more than the control. That time difference is inside the noise of a casual run,
and nothing in the source tells you a `Long` fell outside the cache. You would not catch it by eye
in review, and you would not catch it in the ns/op column either.

You *would* catch it in `gc.alloc.rate.norm`, which goes from about zero to exactly 24.000 B/op
with an error of ±0.001. It is per-operation and deterministic, unlike an allocation *rate*, which
is precisely why it is the right thing to assert on in CI: a commit that introduces an autobox, a
`String.valueOf`, a stream or an escaping `BigDecimal` moves it off zero, and the build can fail on
that one number. `buildsString` is the easy case by comparison — loud in both columns, so you would
have noticed anyway.

Turn the number into a prediction with the article's arithmetic — young-GC interval ≈ Eden ÷
allocation rate. 24 B/op at 200k msg/s is 4.8 MB/s: a young collection every 53 seconds on a
256 MB Eden, or every 14 minutes on a 4 GB one.

### The dead-code guard: three benchmarks that refuse to separate

<!-- BEGIN GENERATED: deadcode -->
```
Benchmark                            Mode  Cnt  Score   Error  Units
DeadCodeBenchmark.blackholeConsumed  avgt   16  2.794 ± 0.426  ns/op
DeadCodeBenchmark.constantReturn     avgt   16  2.956 ± 0.230  ns/op
DeadCodeBenchmark.derivedReturn      avgt   16  2.659 ± 0.511  ns/op
```
<!-- END GENERATED: deadcode -->

`constantReturn` ends with `return buffer.position()`, which is always 21. By the usual telling that
is no guard at all and the JIT should fold the encode away, leaving a number far below the other
two. It does not — and across repeated runs of this project **the ordering of the three keeps
changing**, which is the real finding: they are indistinguishable, and any story about which is
fastest is a story about noise. Check the intervals in the table above and you will usually find
they overlap.

The reason they cannot separate is in the benchmark body. All three write into a `ByteBuffer` held
in a field, so the writes are side effects on an object that outlives the method. Escape analysis
cannot prove they are unobservable, so they happen whatever the method returns. **The constant
return is harmless here.**

Which is exactly the qualification the article makes: a constant return is no guard *in a benchmark
whose body is pure computation*. Change the body to something that leaves no trace — arithmetic
into a local, a hash, a comparison — and the same constant return lets the whole thing vanish. The
guard costs nothing measurable, and it insures against a property of the body that is easy to
change by accident.

### Averaging five p99s is 733% wrong, in both directions

`percentiles` is a seeded simulation, so unlike everything else here it is **deterministic and
identical on every machine** — which makes it the one command that checks your reading rather than
your hardware. Its numbers are therefore safe to quote directly:

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

And on sample counts: p99 settles by a thousand samples, but p99.99 is still moving at a hundred
thousand, because it is computed from about ten observations. Quoting it there is quoting noise.

### Coordinated omission: the same run, measured three ways

<!-- BEGIN GENERATED: co -->
```
what was measured             p50        p90        p99      p99.9     p99.99          max
----------------------------------------------------------------------------------------------
1. service time           0.60 us    0.80 us    1.10 us    1.60 us    6.20 us      10.1 ms
2. response time          0.60 us    0.90 us    1.30 us     7.8 ms     9.8 ms      10.2 ms
3. HdrHistogram patch     0.60 us    0.80 us    1.10 us     7.5 ms     9.8 ms      10.1 ms
----------------------------------------------------------------------------------------------

1. service time      CO off. What your onMessage() instrumentation records.
2. response time     CO on.  Measured from the slot the message belonged to.
3. HdrHistogram      recordValueWithExpectedInterval over (1): interpolated,
                     not observed. Close here only because arrivals are uniform.

Samples above 5.0 ms:  service time 4      response time 2,187
achieved rate    99,999 bursts/s (target 100,000)  ok
```
<!-- END GENERATED: co -->

Rows 1 and 2 are the same run of the same code on the same machine, in the same second. Compare
their p99.9 columns: service time reports microseconds, response time reports milliseconds, and the
gap is typically three to four orders of magnitude.

The `Samples above` line is the mechanism, and it is the most useful line in the block. The
application froze a handful of times; service time recorded exactly that handful of bad samples,
while *thousands* of arrivals were actually late. The measurement stopped sampling precisely while
the system was failing. Note that no load generator was involved — row 1 is what an ordinary
`onMessage()` timer records, which is why coordinated omission is not only a load-testing problem.

Row 3 is the honest caveat. HdrHistogram's `recordValueWithExpectedInterval` lands close to row 2
*here* only because arrivals in this harness are perfectly uniform. It reconstructs samples that
were never taken; it cannot reproduce what a real backlog does to a system — buffers filling, cache
lines displaced, GC triggered by everything that piled up.

### The tail turns upward long before saturation

<!-- BEGIN GENERATED: sweep -->
```
  rho    target/s  achieved/s       p50       p99     p99.9    p99.99       max p99.9/p50  1/(1-r)
----------------------------------------------------------------------------------------------------------------
 0.10     158,437     154,900   1.00 us   1.10 us   2.59 us  125.1 us  293.6 us      2.6x     1.1x
 0.30     475,311     453,168   1.00 us   1.10 us   54.6 us  326.7 us  408.3 us     54.6x     1.4x
 0.50     792,185     769,300   1.00 us   8.60 us  182.1 us  322.8 us  404.2 us    182.1x     2.0x
 0.70   1,109,059     917,355   70.6 ms  348.4 ms  359.4 ms  360.4 ms  360.7 ms      5.1x     3.3x  <- DID NOT KEEP UP
 0.80   1,267,496     890,677  206.8 ms  650.6 ms  667.9 ms  669.5 ms  669.5 ms      3.2x     5.0x  <- DID NOT KEEP UP
 0.90   1,425,933   1,046,112   13.7 ms  446.2 ms  468.2 ms  470.3 ms  470.5 ms     34.1x    10.0x  <- DID NOT KEEP UP
 0.95   1,505,152     915,331  286.5 ms  831.0 ms  854.6 ms  857.2 ms  857.2 ms      3.0x    20.0x  <- DID NOT KEEP UP
 1.05   1,663,589     901,569  321.4 ms  985.1 ms 1,013.4 ms 1,016.1 ms 1,016.6 ms      3.2x      inf  <- DID NOT KEEP UP
```
<!-- END GENERATED: sweep -->

Read the p50 column down first. It typically stays flat across the first several rows while p99.9
climbs by orders of magnitude — every one of those rows would support the claim "median latency is
one microsecond", and every one of them describes a differently healthy system.

The knee — the rate where p99.9 starts climbing away from p50 — is the number to report. Not a p99
detached from the rate it was measured at. On this laptop it has landed around half the sustainable
rate rather than anywhere near 100%, which is the concrete version of why "we're only at 70% CPU"
is not the reassurance people think it is.

Compare the last two columns. `1/(1-ρ)` is the queue-length multiplier a textbook M/M/1 queue
predicts; `p99.9/p50` is what actually happened. Where the measured ratio outruns the theoretical
one — usually by a lot — the excess is service-time variability. For the same average service time,
a more variable one produces a much longer queue, which is why a component with a good p50 and a
ragged p99 saturates earlier than one that is slower on average but tight.

#### Past the knee it stops being a tail problem

The bottom of that table is where the argument of the whole article becomes visible, so it is worth
reading rather than truncating.

At low utilisation this is a *tail* story: the median is flat and the damage is confined to p99.9
and beyond. At high ρ that stops being true — **the median itself goes to milliseconds, then tens
and hundreds of milliseconds.** There is no longer a fast path with a ragged tail; every request is
slow, because every request is waiting behind the ones in front of it.

Now read the `achieved/s` column against `target/s` in those rows. **Achieved throughput flattens:
asking for more work does not get more work done**, it only makes the schedule fall further behind.
That is the diverging queue from the coordinated-omission section, measured rather than described —
work arrives on a clock that does not care how busy you are, the backlog grows without bound, and
every arrival inherits the lateness of everything queued ahead of it.

So past that point the percentiles are not measuring the component at all: **they are measuring how
long the run lasted.** A longer run at the same ρ would report a worse p50 from identical code,
because the queue simply had more time to grow. This is why rows marked `DID NOT KEEP UP` carry no
latency information — read them as "cannot sustain this rate", never as "latency is N ms".

The floor is visible in this table too. At the lowest ρ the component is nearly idle, yet p99.99
and max are far above p50. That is not queueing and not the encoder; it is the machine. Compare it
against the `hiccup` block — if they are the same order of magnitude, no change to this code moves
that column.

### The tail is not reproducible, and JLBH prints a column that says so

<!-- BEGIN GENERATED: jlbh -->
```
-- end to end --
Percentile   run1         run2         run3         run4         run5      % Variation
50.0:            0.60         1.00         1.00         1.00         1.00         0.00
90.0:            0.60         1.00         1.00         1.00         1.10         6.30
99.0:            1.10         1.10         1.10         1.10         1.10         0.00
99.7:            1.10         1.10         1.10         1.10         1.20         5.70
99.9:            1.20         1.10         1.10         1.10        10.70        85.31
99.97:           5.00         4.50         3.40         4.90        99.97        94.98
99.99:          14.10        10.90        10.42        19.62       320.00        95.20
worst:         141.06        75.39        44.48       348.67       519.68        87.69

achieved arrival rate (last run)  198,985 bursts/s  (target 200,000)
```
<!-- END GENERATED: jlbh -->

Read the `% Variation` column downwards. It is 0.00 or close to it through the upper rows, and
enormous — often above 50%, sometimes above 95% — from p99.9 down. Deep in the tail, one run can
differ from another by an order of magnitude or more, from identical code on the same machine
minutes apart.

Take that 0.00 as **JLBH's own statistic over the runs it treats as comparable, not as raw
agreement between the run columns.** Check the per-run figures yourself: in these runs the p50 row
is usually not identical across all five, and a first run that differs from the rest is exactly the
shape warm-up leaves behind. The honest reading of a 0.00 row is "stable to within a bucket once
the JVM has settled", not "every run produced the same number".

This is what "say how many samples are behind the number" looks like in practice. A single p99.99
from a single run is not a property of the system; it is one draw from a distribution that is itself
enormously wide. If you are comparing two builds, a large tail "regression" may be entirely inside
this noise — merge the histograms and compare those, and never average the per-run percentiles.

The achieved-rate line matters just as much: if the run kept up, the percentiles describe a steady
state. Had it fallen short, the queue would be divergent and none of them would mean anything.

### What the stage probes cost

The same workload with per-stage probes on. Compare it against the `jlbh` block above, which is the
identical workload with probes off:

<!-- BEGIN GENERATED: stages -->
```
-- end to end --
Percentile   run1         run2         run3         run4         run5      % Variation
50.0:            0.90         0.90         1.40         1.40         1.40        27.04
90.0:            1.40         1.40         1.40         1.40         1.40         0.00
99.0:            1.50         1.50         1.70         1.70         1.50         8.15
99.7:            1.60         1.50         1.70         1.90         1.60        15.08
99.9:            2.70         1.80         1.70         2.50         1.80        23.81
99.97:           9.49         7.30         4.90         6.30         5.40        24.60
99.99:          27.87        28.13        10.10        15.41        14.00        54.35
worst:         171.78       191.74        49.34       108.93        53.44        65.80

-- decode --
Percentile   run1         run2         run3         run4         run5      % Variation
50.0:            0.40         0.40         0.60         0.60         0.60        25.02
90.0:            0.60         0.60         0.60         0.60         0.60         0.00
99.0:            0.70         0.70         0.70         0.70         0.70         0.00
99.7:            0.70         0.70         0.70         0.70         0.70         0.00
99.9:            0.80         0.70         0.70         0.90         0.70        15.98
99.97:           1.00         0.90         0.80         1.60         0.90        40.00
99.99:           3.30         2.20         2.60         3.80         2.50        32.61
worst:         146.69        33.09        39.87        56.64        52.54        32.18

-- encode --
Percentile   run1         run2         run3         run4         run5      % Variation
50.0:            0.30         0.30         0.40         0.40         0.40        18.16
90.0:            0.40         0.40         0.40         0.40         0.40         0.00
99.0:            0.40         0.40         0.50         0.50         0.40        14.27
99.7:            0.50         0.40         0.50         0.60         0.40        25.02
99.9:            0.50         0.50         0.50         0.80         0.50        28.59
99.97:           0.60         0.50         0.50         1.40         0.50        54.56
99.99:           2.00         1.50         0.60         1.60         1.30        52.61
worst:          29.98        72.83        33.09        40.00        40.38        44.47

-- risk --
Percentile   run1         run2         run3         run4         run5      % Variation
50.0:            0.20         0.20         0.40         0.40         0.40        40.00
90.0:            0.40         0.40         0.40         0.40         0.40         0.00
99.0:            0.50         0.50         0.50         0.50         0.50         0.00
99.7:            0.50         0.50         0.50         0.60         0.50        11.81
99.9:            0.50         0.50         0.50         0.70         0.50        21.08
99.97:           0.60         0.50         0.60         1.40         0.50        54.56
99.99:           2.00         1.30         1.20         1.90         0.70        53.32
worst:          56.13        52.29        13.81        84.86        46.78        77.43

achieved arrival rate (last run)  199,658 bursts/s  (target 200,000)
```
<!-- END GENERATED: stages -->

At the median the cost is small, honest and roughly what the `clock` block predicts: four extra
`nanoTime()` calls per burst, sitting **inside** the window `jlbh.sample()` reports. That is the tax
you accept in exchange for knowing which stage moved, and it is why the instrumented run is never
the number to quote.

At the tail, be careful about what you conclude. An earlier version of this README claimed
instrumentation turns the tail into "a different system", on the strength of a run where probes-on
looked catastrophically worse. A quieter re-run reversed it — probes-on had the *better* worst case
— because run-to-run outlier noise is far larger than anything the probes cost. The claim was
withdrawn. What survives is narrower and duller: probes cost a few hundred nanoseconds at the
median, and at the tail you cannot attribute the difference at all.

The per-stage blocks also let you test composition directly. Add the three stage p99s and compare
the sum against the end-to-end p99. The relationship is not fixed: on a light-tailed, roughly
independent run like this one the sum lands close to the measured value or a little above it, which
is the friendliest case and the one that tempts people to trust it. Introduce heavy tails or
correlated stalls — a safepoint, a descheduled thread — and the same sum falls far below, as the
`percentiles` command demonstrates with all three shapes side by side. The probes locate where the
time went. They do not reliably add up to the answer.

---

## Experiment log

These are records of *past* runs, kept because the comparisons are the point and no single run can
reproduce them. They are **not** regenerated by `lab.jar report`, and they are dated so you can
tell how stale they are. Everything above this section is current; everything in it is history.

### Busy machine vs quiet machine (2026-09-12)

The same suite, run with a browser and an IDE open, then again with the machine deliberately
quieted — browser, IDE, Edge, Teams, WhatsApp, Phone Link and Widgets closed, idle CPU 3–6%:

| | busy | quiet | moved? |
|---|---|---|---|
| encoder DIRECT | 2.672 ± **0.221** | 2.343 ± **0.044** | error bar 5× tighter |
| encoder HEAP | 3.578 ± **0.373** | 2.990 ± **0.021** | error bar 18× tighter |
| `nanoTime()` cost | 29.191 ns | 29.546 ns | no |
| clock resolution | 99 ns | 100 ns | no |
| idle-JVM floor, p99 | 2.0 ms | 2.0 ms | no |
| idle-JVM floor, max | 2.8 ms | 2.8 ms | no |
| sweep: rate it sustained | collapsed at ρ = 0.70 | held to ρ = 0.80 | yes, a lot |
| `stages` worst case | 62 ms | 331 µs | yes, ~200× |

Two conclusions, pointing in opposite directions. Measurements of small, CPU-bound things became
far more **precise** — the error bars collapsed, and a comparison that was inconclusive became
decisive. But the **platform floor did not move at all**: p99 2.0 ms and max 2.8 ms either way,
because that floor is scheduler and power-management behaviour, not competition for CPU. Closing
applications buys you precision, not a lower floor.

### One benchmark, three runs, three verdicts (2026-09-12)

`OrderEncoderBenchmark`, same code throughout:

| run | DIRECT | HEAP | conclusion supported |
|---|---|---|---|
| smoke, busy machine | 3.223 ± 1.392 | 3.851 ± 4.482 | none — intervals overlap entirely |
| annotated, busy machine | 2.672 ± 0.221 | 3.578 ± 0.373 | DIRECT faster, ±14% error |
| annotated, quiet machine | 2.343 ± 0.044 | 2.990 ± 0.021 | DIRECT faster by 28%, ±2% error |

The environment moved the error bar by 18× while barely moving the score.

### The floor's median resisted attribution (2026-09-12)

A quiet run reported an idle-JVM median of 531.5 µs where an earlier busy session had reported
140.7 µs — better on the busier machine, which is the wrong way round.

`HiccupMeter` sleeps 1 ms and measures how late it wakes. System timer resolution was 1.00 ms, and
a 1 ms tick predicts a median overshoot of about half a tick — almost exactly the 531 µs observed.
A background application holding a finer timer request would explain everything. So: change that
one variable and measure the same percentile again.

| condition | timer resolution | p50 | p99 | max |
|---|---|---|---|---|
| quiet | 1.00 ms | 531.5 µs | 2.0 ms | 2.8 ms |
| quiet, 0.5 ms forced | 0.50 ms | **500.0 µs** | 1.5 ms | 2.4 ms |
| quiet, 4 threads spinning | 1.00 ms | **998.9 µs** | 2.0 ms | 3.0 ms |

**The median did not move.** Halving the timer resolution changed p50 by 6%, so timer granularity
was not the cause. Keeping the cores awake — the C-state hypothesis, next on the list — made it
*worse*, which is what contention predicts and not what core parking predicts.

So the 140.7 µs median is not reproducible and no mechanism is offered for it. Two hypotheses
tested, two rejected, which is exactly the discipline the article argues for: if the percentile does
not move, the correlation was not the cause, and you have just saved yourself from optimising the
wrong thing. Note what *was* robust — p99 stayed at 1.5–2.0 ms and max at 2.4–3.0 ms across all
three conditions. The floor's median is not a stable property of this machine; its tail is.

### Windows against a Linux CI runner (2026-09-10)

The `clock` and `hiccup` commands on the GitHub Actions runner, for contrast:

| | Windows 11 laptop | Linux CI runner |
|---|---|---|
| clocksource | QueryPerformanceCounter | `tsc` |
| cost of one call | 27–29 ns | 17 ns |
| smallest non-zero delta | **~100 ns** | **18 ns** |
| consecutive reads that were equal | ~69% | 0.0% |
| idle-JVM hiccup, max | 2.8 ms | 245 µs |

Five times the clock resolution and ten times the platform floor, for the same code — and the
shared, virtualised CI runner is the *better* machine here. Which of the two you measure on decides
what is measurable at all.

---

## Read this before believing any number here

The `hiccup` block above is an **idle** JVM: no benchmark, no load, a machine with the browser and
the IDE shut down. On this laptop its tail has consistently landed in the low milliseconds, so no
p99.99 below roughly 3 ms means anything here however tidy you make the machine — and every
microsecond-scale figure in this file should be read as a *relative* comparison rather than an
absolute latency.

That floor held at the same order of magnitude whether the machine was busy, quiet, or running with
a forced 0.5 ms timer resolution (see the experiment log), so it is not something you tidy your way
out of. Closing applications buys precision in the CPU-bound measurements, not a lower floor.

This is the point of running `hiccup` first, and it is not a flaw in the project. If production runs
in a container with a CPU quota, then benchmarking on a tuned bare-metal box tells you about a
system you are not going to deploy. Reproduce the production configuration — and if that
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
