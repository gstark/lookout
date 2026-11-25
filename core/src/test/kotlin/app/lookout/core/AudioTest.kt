package app.lookout.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioTest {
    private val sr = 48_000

    /** Generates mono 16-bit PCM of [durationMs] filled by [sampleAt](index, timeSec). */
    private fun synth(durationMs: Int, sampleRate: Int = sr, sampleAt: (Int, Double) -> Double): Pcm {
        val n = (durationMs.toLong() * sampleRate / 1000L).toInt()
        val buf = ShortArray(n) { i ->
            val v = sampleAt(i, i.toDouble() / sampleRate)
            (v * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return Pcm(buf, sampleRate)
    }

    @Test fun fftFindsExactBinForPureCosine() {
        val n = 256
        val k = 17
        val re = DoubleArray(n) { i -> kotlin.math.cos(2.0 * PI * k * i / n) }
        val im = DoubleArray(n)
        Audio.fft(re, im)
        val mags = DoubleArray(n / 2 + 1) { i -> hypot(re[i], im[i]) }
        val peakBin = mags.indices.maxBy { mags[it] }
        assertEquals(k, peakBin)
        assertEquals(n / 2.0, mags[k], 1e-6)
        for (i in 0..n / 2) if (i != k) assertTrue(mags[i] < 1e-6, "bin $i leaked ${mags[i]}")
    }

    @Test fun fullScaleOneKilohertzSineGivesExpectedLevelAndFrequency() {
        val pcm = synth(1_000) { _, t -> sin(2.0 * PI * 1000.0 * t) }
        val r = Audio.analyze(pcm)
        assertNoLongDecimals(r)
        assertEquals(sr, r["sample_rate"]!!.jsonPrimitive.int)
        assertEquals(0L, r["from_ms"]!!.jsonPrimitive.long)
        assertEquals(1_000L, r["to_ms"]!!.jsonPrimitive.long)
        assertEquals(1_000L, r["duration_ms"]!!.jsonPrimitive.long)
        assertEquals(-3.0, r["rms_dbfs"]!!.jsonPrimitive.double, 0.1)
        assertEquals(0.0, r["peak_dbfs"]!!.jsonPrimitive.double, 0.1)
        val res = r["fft_resolution_hz"]!!.jsonPrimitive.double
        val mainHz = r["main_hz"]!!.jsonPrimitive.double
        assertTrue(abs(mainHz - 1000.0) <= res, "main_hz=$mainHz res=$res")
        assertEquals(listOf("hz", "rel_db"), r["peaks_columns"]!!.jsonArray.map { it.jsonPrimitive.content })
        val peaks = r["peaks"]!!.jsonArray
        assertTrue(peaks.isNotEmpty())
        assertEquals(mainHz, peaks[0].jsonArray[0].jsonPrimitive.double, 0.1)
        assertEquals(0.0, peaks[0].jsonArray[1].jsonPrimitive.double, 0.1)
    }

    @Test fun silenceGivesLowLevelAndNoEvents() {
        val pcm = Pcm(ShortArray(sr), sr)
        val r = Audio.analyze(pcm)
        assertNoLongDecimals(r)
        assertEquals(-96.0, r["rms_dbfs"]!!.jsonPrimitive.double)
        assertEquals(-96.0, r["peak_dbfs"]!!.jsonPrimitive.double)
        assertEquals(-96.0, r["noise_floor_dbfs"]!!.jsonPrimitive.double)
        assertTrue(r["events"]!!.jsonArray.isEmpty())
        assertNull(r["main_hz"])
        assertNull(r["period_ms"])
    }

    @Test fun threeRegularBurstsReportThreeEventsAndPeriod() {
        // Low background (-60 dBFS) + 3 chirps at 500 ms, 1500 ms, 2500 ms (each 120 ms at 2400 Hz).
        val starts = listOf(500, 1500, 2500)
        val pcm = synth(3_200) { i, t ->
            val ms = i * 1000.0 / sr
            val noise = 0.001 * sin(2.0 * PI * 60.0 * t)
            val chirp = if (starts.any { ms in it.toDouble()..(it + 120.0) }) 0.5 * sin(2.0 * PI * 2400.0 * t) else 0.0
            noise + chirp
        }
        val r = Audio.analyze(pcm)
        assertNoLongDecimals(r)
        assertEquals(listOf("from_ms", "to_ms", "peak_dbfs", "main_hz"), r["events_columns"]!!.jsonArray.map { it.jsonPrimitive.content })
        val events = r["events"]!!.jsonArray
        assertEquals(3, events.size, "events=$events")
        for (idx in 0 until 3) {
            val ev = events[idx].jsonArray
            val from = ev[0].jsonPrimitive.long
            val to = ev[1].jsonPrimitive.long
            val peak = ev[2].jsonPrimitive.double
            val hz = ev[3].jsonPrimitive.double
            assertTrue(abs(from - starts[idx]) <= 25, "event $idx from=$from")
            assertTrue(abs(to - (starts[idx] + 120)) <= 25, "event $idx to=$to")
            assertTrue(peak in -7.0..-5.0, "event $idx peak=$peak")
            assertTrue(abs(hz - 2400.0) <= 20.0, "event $idx hz=$hz")
        }
        val period = r["period_ms"]?.jsonPrimitive?.long
        assertNotNull(period, "regular chirps should report period_ms")
        assertTrue(abs(period - 1_000L) <= 40L, "period=$period")
    }

    @Test fun irregularBurstsOmitPeriod() {
        val starts = listOf(300, 800, 2300)
        val pcm = synth(3_000) { i, t ->
            val ms = i * 1000.0 / sr
            if (starts.any { ms in it.toDouble()..(it + 100.0) }) 0.5 * sin(2.0 * PI * 1800.0 * t) else 0.0
        }
        val r = Audio.analyze(pcm)
        assertEquals(3, r["events"]!!.jsonArray.size)
        assertNull(r["period_ms"], "irregular spacing must not report period_ms")
    }

    @Test fun shortGapMergesIntoOneEventAndLongGapSplits() {
        // Two 100 ms bursts 60 ms apart (<= 100 ms gap) -> 1 event; third burst 300 ms later -> 2nd event.
        val pcm = synth(1_200) { i, t ->
            val ms = i * 1000.0 / sr
            val on = ms in 100.0..200.0 || ms in 260.0..360.0 || ms in 660.0..760.0
            if (on) 0.4 * sin(2.0 * PI * 1200.0 * t) else 0.0
        }
        val events = Audio.analyze(pcm)["events"]!!.jsonArray
        assertEquals(2, events.size, "events=$events")
        assertTrue(events[0].jsonArray[0].jsonPrimitive.long in 80..120)
        assertTrue(events[0].jsonArray[1].jsonPrimitive.long in 340..400)
        assertTrue(events[1].jsonArray[0].jsonPrimitive.long in 640..680)
    }

    @Test fun marksReportAfterLevelAndDelayToFirstEvent() {
        // Mark 1 at 500 ms -> tone starts 120 ms later (at 620 ms) at 1500 Hz.
        // Mark 2 at 1500 ms -> silence after.
        val pcm = synth(2_200) { i, t ->
            val ms = i * 1000.0 / sr
            if (ms in 620.0..820.0) 0.5 * sin(2.0 * PI * 1500.0 * t) else 0.0
        }
        val marks = listOf(Mark(500L, "servo_go"), Mark(1500L, "servo_idle"))
        val r = Audio.analyze(pcm, fromMs = 0L, marks = marks)
        assertNoLongDecimals(r)
        assertEquals(
            listOf("label", "t_ms", "after_rms_dbfs", "after_peak_dbfs", "event_delay_ms", "event_hz"),
            r["marks_columns"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val rows = r["marks"]!!.jsonArray
        assertEquals(2, rows.size)

        val row0 = rows[0].jsonArray
        assertEquals("servo_go", row0[0].jsonPrimitive.content)
        assertEquals(500L, row0[1].jsonPrimitive.long)
        assertTrue(row0[2].jsonPrimitive.double > -20.0, "after_rms=${row0[2]}")
        assertTrue(row0[3].jsonPrimitive.double in -7.0..-5.0, "after_peak=${row0[3]}")
        val delay = row0[4].jsonPrimitive.long
        assertTrue(abs(delay - 120L) <= 20L, "expected ~120 ms delay, got $delay")
        assertTrue(abs(row0[5].jsonPrimitive.double - 1500.0) <= 20.0, "event_hz=${row0[5]}")

        val row1 = rows[1].jsonArray
        assertEquals("servo_idle", row1[0].jsonPrimitive.content)
        assertEquals(1500L, row1[1].jsonPrimitive.long)
        assertEquals(-96.0, row1[2].jsonPrimitive.double)
        assertEquals(JsonNull, row1[4])
        assertEquals(JsonNull, row1[5])
    }

    @Test fun loudnessIsCappedAndRawLiftsTheCap() {
        // Use 8 kHz so a 10 s buffer is tiny and fast in tests.
        val pcm10 = synth(10_000, sampleRate = 8_000) { _, t -> 0.25 * sin(2.0 * PI * 440.0 * t) }
        val compact = Audio.analyze(pcm10)
        assertEquals(50, compact["loudness_step_ms"]!!.jsonPrimitive.int)
        assertTrue(compact["loudness"]!!.jsonArray.size <= Audio.MAX_LOUDNESS_POINTS)
        assertEquals(200, compact["loudness"]!!.jsonArray.size)

        val raw = Audio.analyze(pcm10, raw = true)
        assertEquals(Audio.BASE_STEP_MS, raw["loudness_step_ms"]!!.jsonPrimitive.int)
        assertEquals(500, raw["loudness"]!!.jsonArray.size)

        val pcm60 = synth(60_000, sampleRate = 8_000) { _, _ -> 0.0 }
        val compact60 = Audio.analyze(pcm60)
        assertEquals(300, compact60["loudness_step_ms"]!!.jsonPrimitive.int)
        assertEquals(200, compact60["loudness"]!!.jsonArray.size)
    }

    @Test fun eventsCapKeepsStrongestInTimeOrder() {
        // 60 short pulses, each 40 ms on / 160 ms off (200 ms period, 12 s total) at 8 kHz.
        val pcm = synth(12_000, sampleRate = 8_000) { i, t ->
            val ms = i * 1000.0 / 8_000
            val idx = (ms / 200.0).toInt()
            val within = ms - idx * 200.0
            if (idx < 60 && within in 20.0..60.0) {
                // Make the first 10 pulses weaker so they are the ones dropped.
                val amp = if (idx < 10) 0.05 else 0.5
                amp * sin(2.0 * PI * 800.0 * t)
            } else 0.0
        }
        val r = Audio.analyze(pcm)
        val events = r["events"]!!.jsonArray
        assertEquals(Audio.MAX_EVENTS, events.size)
        assertEquals(10, r["events_truncated"]!!.jsonPrimitive.int)
        val times = events.map { it.jsonArray[0].jsonPrimitive.long }
        assertEquals(times.sorted(), times, "capped events stay in time order")
        assertTrue(times.first() >= 1900L, "weaker early pulses were dropped first: ${times.first()}")
    }

    @Test fun windowOffsetShiftsEventTimesAndMatchesMarks() {
        val pcm = synth(1_000) { i, t ->
            val ms = i * 1000.0 / sr
            if (ms in 300.0..450.0) 0.5 * sin(2.0 * PI * 1000.0 * t) else 0.0
        }
        val r = Audio.analyze(pcm, fromMs = 2_000L, marks = listOf(Mark(2_200L, "cmd")))
        assertEquals(2_000L, r["from_ms"]!!.jsonPrimitive.long)
        assertEquals(3_000L, r["to_ms"]!!.jsonPrimitive.long)
        val ev = r["events"]!!.jsonArray.single().jsonArray
        assertTrue(ev[0].jsonPrimitive.long in 2_280..2_320)
        val delay = r["marks"]!!.jsonArray.single().jsonArray[4].jsonPrimitive.long
        assertTrue(abs(delay - 100L) <= 20L, "delay=$delay")
    }
}
