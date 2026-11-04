package com.limelight.shell.ui

import androidx.compose.animation.core.LinearEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure arithmetic of the shared motion language (`Motion`, `EntranceStagger`). */
class MotionTest {
    @Test
    fun anAnimationIsAtItsStartBeforeItsDelayAndAtItsEndAfterItsDuration() {
        val m = Motion
        assertEquals(0f, m.progress(0f, 100, 620, LinearEasing), 0f)
        assertEquals(0f, m.progress(100f, 100, 620, LinearEasing), 0f)
        assertEquals(.5f, m.progress(410f, 100, 620, LinearEasing), 1e-6f)
        assertEquals(1f, m.progress(720f, 100, 620, LinearEasing), 0f)
        assertEquals(1f, m.progress(5000f, 100, 620, LinearEasing), 0f)
        assertEquals(.25f, m.linear(60f, 0, 240), 0f)
        assertEquals(15f, m.lerp(10f, 20f, .5f), 1e-6f)
    }

    @Test
    fun theSpringAndTheGlideOvershootAndEaseInDoesNot() {
        val spring = EaseSpring
        assertTrue((1..99).any { spring.transform(it / 100f) > 1.02f })
        assertTrue((1..99).all { Motion.In.transform(it / 100f) <= it / 100f + 1e-4f })
        assertTrue((1..99).any { Motion.Glide.transform(it / 100f) > 1.0f })
        assertEquals(1f, spring.transform(1f), 1e-4f)
        assertEquals(0f, Motion.Glide.transform(0f), 1e-4f)
    }

    @Test
    fun aStaggerStartsWithTheBaseDelayAndStepsFromThere() {
        assertEquals(120, Motion.staggerDelay(0, Motion.ROW_DELAY, Motion.STAGGER_ROW))
        assertEquals(120 + 3 * 46, Motion.staggerDelay(3, Motion.ROW_DELAY, Motion.STAGGER_ROW))
        // A place before the first one counts as the first
        assertEquals(120, Motion.staggerDelay(-2, Motion.ROW_DELAY, Motion.STAGGER_ROW))
        assertEquals(0, Motion.staggerTotal(0, 120, 46, 420))
        assertEquals(120 + 46 + 420, Motion.staggerTotal(2, 120, 46, 420))
    }

    @Test
    fun aReverseStaggerStartsWithTheLastElement() {
        assertEquals(0, Motion.reverseStaggerDelay(8, 9, 8))
        assertEquals(64, Motion.reverseStaggerDelay(0, 9, 8))
        assertEquals(0, Motion.reverseStaggerDelay(12, 9, 8))
    }

    @Test
    fun reducedMotionKeepsTheFadeAndDropsEveryTransform() {
        // Entering: the rise, the grow and the slide are gone, the progress itself (the opacity) is not touched
        assertEquals(18f * .25f, Motion.rise(18f, .75f, reduced = false), 1e-6f)
        assertEquals(0f, Motion.rise(18f, .25f, reduced = true), 0f)
        assertEquals(.8f + .2f * .5f, Motion.scaleIn(.8f, .5f, reduced = false), 1e-6f)
        assertEquals(1f, Motion.scaleIn(.8f, .5f, reduced = true), 0f)
        assertEquals(-22f * .5f, Motion.slide(-1, 22f, .5f, reduced = false), 1e-6f)
        assertEquals(22f * .5f, Motion.slide(1, 22f, .5f, reduced = false), 1e-6f)
        assertEquals(0f, Motion.slide(1, 22f, .5f, reduced = true), 0f)
        assertEquals(0f, Motion.slide(0, 22f, .5f, reduced = false), 0f)
        // At the end of an animation nothing is left to move
        assertEquals(0f, Motion.rise(18f, 1f, reduced = false), 0f)
        assertEquals(0f, Motion.slide(1, 22f, 1f, reduced = false), 0f)
    }

    @Test
    fun onlyTheChevronOfTheKeyThatWasPressedNudges() {
        assertEquals(-Motion.CHEVRON_BUMP, Motion.chevronBump(-1, -1, 1f, reduced = false), 0f)
        assertEquals(0f, Motion.chevronBump(1, -1, 1f, reduced = false), 0f)
        assertEquals(Motion.CHEVRON_BUMP / 2f, Motion.chevronBump(1, 1, .5f, reduced = false), 1e-6f)
        assertEquals(0f, Motion.chevronBump(-1, 1, 1f, reduced = false), 0f)
        assertEquals(0f, Motion.chevronBump(1, 1, 1f, reduced = true), 0f)
        assertEquals(0f, Motion.chevronBump(1, 0, 1f, reduced = false), 0f)
    }

    @Test
    fun aPressSqueezesBelowTheFocusScaleUnlessItIsTurnedOff() {
        assertEquals(1.12f, Motion.scaleTarget(focused = true, pressed = false, focusScale = 1.12f, pressScale = .97f), 0f)
        assertEquals(.97f, Motion.scaleTarget(focused = true, pressed = true, focusScale = 1.12f, pressScale = .97f), 0f)
        assertEquals(.97f, Motion.scaleTarget(focused = false, pressed = true, focusScale = 1.12f, pressScale = .97f), 0f)
        assertEquals(1f, Motion.scaleTarget(focused = false, pressed = false, focusScale = 1.12f, pressScale = .97f), 0f)
        // pressScale 1 leaves the focus scale alone (the element has a press feedback of its own)
        assertEquals(1.06f, Motion.scaleTarget(focused = true, pressed = true, focusScale = 1.06f, pressScale = 1f), 0f)
        assertTrue(Motion.PRESS_SCALE < 1f)
    }

    @Test
    fun whatLeavesIsFasterThanWhatComes() {
        assertTrue(Motion.EXIT < Motion.ENTER)
        assertTrue(Motion.POP_OUT < Motion.POP_IN)
        assertTrue(Motion.PRESS < Motion.FOCUS)
        assertTrue(Motion.CARD_FADE_IN < Motion.POP_IN)
        // The entrance of a screen's elements ends in about a second: the controls are never held back by it
        assertTrue(Motion.staggerTotal(Motion.STAGGER_CAP + 1, Motion.ROW_DELAY, Motion.STAGGER_ROW, Motion.ROW_IN) < 1100)
    }

    @Test
    fun aDisabledStaggerHandsOutNoPlaces() {
        val stagger = EntranceStagger(enabled = false)
        assertEquals(-1, stagger.take())
        assertEquals(-1, stagger.take())
    }

    @Test
    fun theStaggerHandsOutPlacesInOrderUpToItsCap() {
        val stagger = EntranceStagger(enabled = true, cap = 2)
        assertEquals(listOf(0, 1, 2, 2, 2), List(5) { stagger.take() })
    }

    @Test
    fun anElementThatShowsUpLateIsNotPartOfTheEntrance() {
        var now = 1000L
        val stagger = EntranceStagger(enabled = true, cap = Motion.STAGGER_CAP, lateAfterMs = 700L) { now }
        assertEquals(0, stagger.take())
        now += 700
        assertEquals(1, stagger.take())
        now += 1
        assertEquals(-1, stagger.take())
        assertEquals(-1, stagger.take())
    }
}
