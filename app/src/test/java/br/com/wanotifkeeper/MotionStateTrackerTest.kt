package br.com.wanotifkeeper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionStateTrackerTest {

    private val tracker = MotionStateTracker(stillnessMs = 45_000L)

    @Test
    fun `starts immediately when activity is observed`() {
        assertFalse(tracker.isInMotion(1_000L))
        tracker.markActivity(1_000L)
        assertTrue(tracker.isInMotion(1_000L))
    }

    @Test
    fun `ends after sustained absence of activity`() {
        tracker.markActivity(1_000L)
        assertTrue(tracker.isInMotion(45_999L))
        assertFalse(tracker.isInMotion(46_000L))
    }

    @Test
    fun `new activity extends motion state`() {
        tracker.markActivity(1_000L)
        tracker.markActivity(30_000L)
        assertTrue(tracker.isInMotion(74_999L))
        assertFalse(tracker.isInMotion(75_000L))
    }

    @Test
    fun `reset returns to stopped immediately`() {
        tracker.markActivity(1_000L)
        tracker.reset()
        assertFalse(tracker.isInMotion(1_001L))
    }
}
