package br.com.wanotifkeeper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionStateTrackerTest {

    @Test
    fun significantMotionStartsImmediately() {
        val tracker = MotionStateTracker()
        assertFalse(tracker.isInMotion(1_000L))
        tracker.markActivity(1_000L)
        assertTrue(tracker.isInMotion(1_000L))
    }

    @Test
    fun isolatedSpikeDoesNotStartMotion() {
        val tracker = MotionStateTracker()
        tracker.observeSample(active = true, quiet = false, now = 1_000L)
        assertFalse(tracker.isInMotion(1_100L))
    }

    @Test
    fun shortBurstStartsMotionWithoutSignificantSensor() {
        val tracker = MotionStateTracker()
        tracker.observeSample(active = true, quiet = false, now = 1_000L)
        tracker.observeSample(active = true, quiet = false, now = 1_500L)
        tracker.observeSample(active = true, quiet = false, now = 1_900L)
        assertTrue(tracker.isInMotion(1_900L))
    }

    @Test
    fun quietPeriodEndsMotionEarly() {
        val tracker = MotionStateTracker(quietExitMs = 12_000L)
        tracker.markActivity(1_000L)

        tracker.observeSample(active = false, quiet = true, now = 5_000L)
        assertTrue(tracker.isInMotion(16_999L))

        tracker.observeSample(active = false, quiet = true, now = 17_000L)
        assertFalse(tracker.isInMotion(17_000L))
    }

    @Test
    fun neutralSampleBreaksQuietConfirmation() {
        val tracker = MotionStateTracker(quietExitMs = 12_000L)
        tracker.markActivity(1_000L)

        tracker.observeSample(active = false, quiet = true, now = 5_000L)
        tracker.observeSample(active = false, quiet = false, now = 10_000L)
        tracker.observeSample(active = false, quiet = true, now = 11_000L)

        assertTrue(tracker.isInMotion(20_000L))
    }

    @Test
    fun repeatedActivityBurstsExtendMotion() {
        val tracker = MotionStateTracker(hardTimeoutMs = 35_000L)
        tracker.markActivity(1_000L)

        tracker.observeSample(active = true, quiet = false, now = 20_000L)
        tracker.observeSample(active = true, quiet = false, now = 20_500L)
        tracker.observeSample(active = true, quiet = false, now = 21_000L)

        assertTrue(tracker.isInMotion(55_999L))
        assertFalse(tracker.isInMotion(56_000L))
    }

    @Test
    fun hardTimeoutEndsMotionEvenWithoutQuietSamples() {
        val tracker = MotionStateTracker(hardTimeoutMs = 35_000L)
        tracker.markActivity(1_000L)

        assertTrue(tracker.isInMotion(35_999L))
        assertFalse(tracker.isInMotion(36_000L))
    }

    @Test
    fun resetReturnsToStoppedImmediately() {
        val tracker = MotionStateTracker()
        tracker.markActivity(1_000L)
        tracker.reset()
        assertFalse(tracker.isInMotion(1_001L))
    }
}
