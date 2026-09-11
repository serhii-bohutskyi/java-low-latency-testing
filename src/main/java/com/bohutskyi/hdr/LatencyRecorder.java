package com.bohutskyi.hdr;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.SingleWriterRecorder;

/**
 * The article's recorder, verbatim in spirit: record latencies without keeping millions of
 * raw {@code long}s, and without allocating on the hot path.
 *
 * <p>Three details are the whole point.
 *
 * <p><b>{@code SingleWriterRecorder}, not {@code Recorder}.</b> {@code Recorder} is the
 * many-writer implementation and records into an atomic histogram. When the recording
 * thread is the single hot thread -- the normal case for the systems this article is about
 * -- {@code SingleWriterRecorder} records into a plain {@link Histogram} and is materially
 * cheaper on the write path.
 *
 * <p><b>{@code getIntervalHistogram(reusable)}, not {@code getIntervalHistogram()}.</b> The
 * no-argument overload allocates a fresh {@link Histogram} every time you report. Handing
 * back the previous one recycles it. Writing about allocation-free hot paths and then
 * allocating once a second in the reporting path would be embarrassing.
 *
 * <p><b>A fixed ceiling means no resizing.</b> An auto-resizing histogram allocates and
 * copies while you are recording, which is exactly what you do not want. The cost is that
 * a value above the ceiling throws, so clamp it -- and count the clamps, because a
 * benchmark quietly saturating its ceiling is a benchmark reporting a fictional max.
 */
public final class LatencyRecorder {

    private static final long MAX_NS = 10_000_000_000L;   // 10 s ceiling

    private final SingleWriterRecorder recorder =
            new SingleWriterRecorder(MAX_NS, 3);

    private Histogram reusable;   // recycled across intervals

    private long clamped;

    public void record(long latencyNs) {
        // fixed ceiling means no resize on the hot path; clamp rather than throw
        if (latencyNs > MAX_NS) {
            clamped++;
            latencyNs = MAX_NS;
        } else if (latencyNs < 0) {
            latencyNs = 0;
        }
        recorder.recordValue(latencyNs);
    }

    /**
     * The HdrHistogram answer to coordinated omission -- and it is <b>not</b> the same idea
     * as an open-loop generator.
     *
     * <p>This records the observed value and then <i>synthesises</i> additional samples by
     * interpolating down to the expected interval: it fills in measurements you already
     * failed to take, assuming arrivals were uniform and would have backed up evenly. It is
     * a retroactive statistical patch applied to missing data. It is wrong when traffic is
     * bursty, and it cannot reproduce what a real backlog does to a system -- buffers
     * filling, cache lines displaced by queued work, GC triggered by everything that piled
     * up.
     *
     * <p>Use it to rescue a dataset you cannot re-collect. Do not use it instead of
     * generating load correctly.
     */
    public void recordWithExpectedInterval(long latencyNs, long expectedIntervalNs) {
        recorder.recordValueWithExpectedInterval(
                Math.min(latencyNs, MAX_NS), expectedIntervalNs);
    }

    /** Takes and resets the interval, recycling the previously returned histogram. */
    public Histogram takeInterval() {
        reusable = recorder.getIntervalHistogram(reusable);
        return reusable;
    }

    /** Number of samples that hit the ceiling. Non-zero means {@code max} is fictional. */
    public long clampedCount() {
        return clamped;
    }
}
