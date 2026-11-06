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
    fun thirtySecondOrbitIsCircularAndOppositePhasesAreSymmetric() {
        assertEquals(30_000_000_000L, HeroMotion.PERIOD_NANOS)
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
        assertEquals(pitchBefore, pitchAfter, .1f)
        assertEquals(yawBefore, yawAfter, .1f)
    }

    @Test
    fun reducedMotionIsAlwaysFlat() {
        for (step in 0..360) {
            assertEquals(0f, HeroMotion.pitch(step / 360f, true), 0f)
            assertEquals(0f, HeroMotion.yaw(step / 360f, true), 0f)
        }
    }

    @Test
    fun stationaryCenterAndOverscanKeepAllViewportCornersInsideTheTiltedImage() {
        val image = listOf(Point(-1050.0, -630.0), Point(1050.0, -630.0), Point(1050.0, 630.0), Point(-1050.0, 630.0))
        val viewport = listOf(Point(-960.0, -540.0), Point(960.0, -540.0), Point(960.0, 540.0), Point(-960.0, 540.0))
        for (step in 0..360) {
            val pitch = HeroMotion.pitch(step / 360f, false) * PI / 180.0
            val yaw = HeroMotion.yaw(step / 360f, false) * PI / 180.0
            val center = project(Point(0.0, 0.0), pitch, yaw)
            assertEquals(0.0, center.x, 0.0)
            assertEquals(0.0, center.y, 0.0)
            val projected = image.map { project(it, pitch, yaw) }
            for (corner in viewport) {
                for (edge in projected.indices) {
                    val a = projected[edge]
                    val b = projected[(edge + 1) % projected.size]
                    val cross = (b.x - a.x) * (corner.y - a.y) - (b.y - a.y) * (corner.x - a.x)
                    assertTrue("Exposed viewport corner at orbit step $step", cross > 0.0)
                }
            }
        }
    }

    private data class Point(val x: Double, val y: Double)

    private fun project(point: Point, pitch: Double, yaw: Double): Point {
        val x = point.x * HeroMotion.OVERSCAN
        val y = point.y * HeroMotion.OVERSCAN
        val rotatedX = x * cos(yaw)
        val rotatedZ = -x * sin(yaw)
        val rotatedY = y * cos(pitch) - rotatedZ * sin(pitch)
        val depth = y * sin(pitch) + rotatedZ * cos(pitch)
        val camera = 2100.0 * HeroMotion.CAMERA_DISTANCE_FACTOR
        val perspective = camera / (camera - depth)
        return Point(rotatedX * perspective, rotatedY * perspective)
    }
}
