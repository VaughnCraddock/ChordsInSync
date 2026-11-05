package com.music.bitchord.data.bpm

import kotlin.math.abs

/**
 * Tap tempo: the listener taps along with the beat, and this turns the tap
 * timestamps into a BPM.
 *
 * Deliberately deaf to everything but the timestamps, so it is unit-tested
 * on synthetic tap trains. The statistic is the median of the recent
 * intervals with a trimmed mean on top: a median shrugs off the one wild
 * interval every human tap train contains (the flinch, the double-tap),
 * and the trimmed mean then averages only the intervals that agreed with
 * the median, which is what gets a jittery human train to land within a
 * fraction of a BPM of the steady one.
 *
 * The rules, plainly:
 *  - At least [MIN_TAPS] taps before there is a value at all.
 *  - A pause longer than [RESET_GAP_MS] ends the train: the next tap
 *    starts a fresh measurement rather than poisoning this one with a
 *    two-second "interval".
 *  - Intervals outside 40–250 BPM never enter the statistic — too slow to
 *    be this tap train's beat, or a bounce rather than a beat.
 */
class TapTempo {

    private val taps = ArrayDeque<Long>()

    /** Records a tap at [nowMs] (any monotonic clock) and returns the current BPM, if there is one yet. */
    fun tap(nowMs: Long): Double? {
        val last = taps.lastOrNull()
        if (last != null && nowMs - last > RESET_GAP_MS) taps.clear()
        if (last != null && nowMs <= last) return bpm()
        taps.addLast(nowMs)
        while (taps.size > MAX_TAPS) taps.removeFirst()
        return bpm()
    }

    /** The BPM the taps so far support, or null when there aren't enough of them yet. */
    fun bpm(): Double? {
        if (taps.size < MIN_TAPS) return null
        val intervals = ArrayList<Long>(MAX_INTERVALS)
        val all = taps.zipWithNext { a, b -> b - a }
        for (interval in all.takeLast(MAX_INTERVALS)) {
            if (interval in MIN_INTERVAL_MS..MAX_INTERVAL_MS) intervals += interval
        }
        if (intervals.size < MIN_TAPS - 1) return null
        val median = median(intervals)
        // Trimmed mean around the median: keep what agrees with it, average
        // that — the median chose the neighbourhood, the mean does the
        // fine positioning inside it.
        val agreeing = intervals.filter { abs(it - median) <= median * TRIM_FRACTION }
        val centre = if (agreeing.size >= 2) agreeing.average() else median
        if (centre <= 0.0) return null
        return 60_000.0 / centre
    }

    /** Forgets every tap; the next one starts a new train. */
    fun reset() {
        taps.clear()
    }

    private fun median(values: List<Long>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle].toDouble()
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    companion object {
        /** Taps needed before a value exists: two intervals is the least a tempo can be read from. */
        const val MIN_TAPS = 3

        /** A pause this long ends the train (the spec's ~2 seconds). */
        const val RESET_GAP_MS = 2_000L

        /** Only the recent past speaks: the last this many taps are kept. */
        private const val MAX_TAPS = 24

        /** …and of their intervals, the last this many are what the statistic reads. */
        private const val MAX_INTERVALS = 8

        /** 250 BPM — faster intervals are a bounce or a double-tap, not this beat. */
        private const val MIN_INTERVAL_MS = 240L

        /** 40 BPM — slower is a pause the reset rule hasn't quite reached. */
        private const val MAX_INTERVAL_MS = 1_500L

        /** Intervals within this fraction of the median count as agreeing with it. */
        private const val TRIM_FRACTION = 0.25
    }
}
