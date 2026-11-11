package com.limelight.shell.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroMotionTest {
    @Test
    fun orbitIsCircularAndOppositePhasesAreSymmetric() {
        for (step in 0..360) {
            val turn = step / 360f
            val pitch = HeroMotion.pitch(turn, false)
            val yaw = HeroMotion.yaw(turn, false)
            assertEquals(HeroMotion.DEGREES.toDouble(), sqrt((pitch * pitch + yaw * yaw).toDouble()), 1e-5)
            assertEquals(-pitch, HeroMotion.pitch(turn + .5f, false), 1e-5f)
            assertEquals(-yaw, HeroMotion.yaw(turn + .5f, false), 1e-5f)
        }
    }

    @Test
    fun positionAndVelocityAreContinuousAcrossTheLoopBoundary() {
        val delta = .0001f
        assertEquals(HeroMotion.pitch(0f, false), HeroMotion.pitch(1f, false), 1e-5f)
        assertEquals(HeroMotion.yaw(0f, false), HeroMotion.yaw(1f, false), 1e-5f)
        val pitchBefore = (HeroMotion.pitch(1f, false) - HeroMotion.pitch(1f - delta, false)) / delta
        val pitchAfter = (HeroMotion.pitch(delta, false) - HeroMotion.pitch(0f, false)) / delta
        val yawBefore = (HeroMotion.yaw(1f, false) - HeroMotion.yaw(1f - delta, false)) / delta
        val yawAfter = (HeroMotion.yaw(delta, false) - HeroMotion.yaw(0f, false)) / delta
        // The slopes are about 94°/turn here, so the one-sided differences differ by the curvature times delta.
        assertEquals(pitchBefore, pitchAfter, .15f)
        assertEquals(yawBefore, yawAfter, .15f)
    }

    @Test
    fun reducedMotionIsAlwaysFlat() {
        for (step in 0..360) {
            assertEquals(0f, HeroMotion.pitch(step / 360f, true), 0f)
            assertEquals(0f, HeroMotion.yaw(step / 360f, true), 0f)
        }
    }

    @Test
    fun farEdgeMovesClearlyButStaysElegant() {
        // Un-overscanned right edge midpoint at the yaw extreme: about 92 px at 2100 px wide (15°, camera 2.2 × 2100 px).
        val shift = 1050.0 - projectRaw(Point(1050.0, 0.0), 0.0, HeroMotion.DEGREES * PI / 180.0).x
        assertTrue("far edge shift $shift", shift in 88.0..96.0)
        assertEquals(25.0, HeroMotion.PERIOD_NANOS / 1e9, 0.0)
    }

    @Test
    fun stationaryCenterAndOverscanKeepAllViewportCornersInsideTheTiltedImage() {
        assertCovers(1920.0, 1080.0)
    }

    @Test
    fun theSameOverscanCoversEveryStageShape() {
        // 16:10, 3:2, 4:3, 20:9, 21:9 and a floored compact stage (docs/GUI.md, Screen shapes)
        for ((w, h) in listOf(1920.0 to 1200.0, 1920.0 to 1280.0, 1920.0 to 1440.0, 2400.0 to 1080.0, 2520.0 to 1080.0, 1600.0 to 1000.0)) assertCovers(w, h)
    }

    /** Every corner of the [stageW] x [stageH] viewport stays inside the tilted [HeroArea] of that stage at every orbit step. */
    private fun assertCovers(stageW: Double, stageH: Double) {
        val area = HeroArea.of(stageW.toFloat(), stageH.toFloat())
        val hw = area.width / 2.0
        val hh = area.height / 2.0
        val image = listOf(Point(-hw, -hh), Point(hw, -hh), Point(hw, hh), Point(-hw, hh))
        val viewport = listOf(Point(-stageW / 2, -stageH / 2), Point(stageW / 2, -stageH / 2), Point(stageW / 2, stageH / 2), Point(-stageW / 2, stageH / 2))
        val camera = maxOf(area.width, area.height) * HeroMotion.CAMERA_DISTANCE_FACTOR.toDouble()
        for (step in 0..360) {
            val pitch = HeroMotion.pitch(step / 360f, false) * PI / 180.0
            val yaw = HeroMotion.yaw(step / 360f, false) * PI / 180.0
            val center = project(Point(0.0, 0.0), pitch, yaw, camera)
            assertEquals(0.0, center.x, 0.0)
            assertEquals(0.0, center.y, 0.0)
            val projected = image.map { project(it, pitch, yaw, camera) }
            for (corner in viewport) {
                for (edge in projected.indices) {
                    val a = projected[edge]
                    val b = projected[(edge + 1) % projected.size]
                    val cross = (b.x - a.x) * (corner.y - a.y) - (b.y - a.y) * (corner.x - a.x)
                    assertTrue("Exposed viewport corner at orbit step $step on a $stageW x $stageH stage", cross > 0.0)
                }
            }
        }
    }

    @Test
    fun phaseFollowsTheFrameClockAndWrapsEveryPeriod() {
        assertEquals(0f, HeroMotion.turnAt(0L), 0f)
        assertEquals(0f, HeroMotion.turnAt(HeroMotion.PERIOD_NANOS * 3), 0f)
        assertEquals(.5f, HeroMotion.turnAt(HeroMotion.PERIOD_NANOS * 3 + HeroMotion.PERIOD_NANOS / 2), 1e-6f)
        assertEquals(HeroMotion.turnAt(12_345_678_901L), HeroMotion.turnAt(12_345_678_901L + HeroMotion.PERIOD_NANOS), 1e-6f)
    }

    private data class Point(val x: Double, val y: Double)

    private fun project(point: Point, pitch: Double, yaw: Double, camera: Double): Point =
        projectRaw(Point(point.x * HeroMotion.OVERSCAN, point.y * HeroMotion.OVERSCAN), pitch, yaw, camera)

    private fun projectRaw(point: Point, pitch: Double, yaw: Double, camera: Double = 2100.0 * HeroMotion.CAMERA_DISTANCE_FACTOR): Point {
        val rotatedX = point.x * cos(yaw)
        val rotatedZ = -point.x * sin(yaw)
        val rotatedY = point.y * cos(pitch) - rotatedZ * sin(pitch)
        val depth = point.y * sin(pitch) + rotatedZ * cos(pitch)
        val perspective = camera / (camera - depth)
        return Point(rotatedX * perspective, rotatedY * perspective)
    }
}
