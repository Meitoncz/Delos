package com.limelight.shell.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Bokeh lens, not a Gaussian blur: a round aperture of golden-angle taps whose weights boost the artwork's
 * brightest pixels and brighten the aperture rim (see [HeroBokeh]). Rendered once per artwork/strength at
 * 420 × 252; motion only transforms the cached result, never runs a many-tap shader across the screen.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object HeroLensShader {
    fun render(source: Bitmap, blurPx: Int): Bitmap {
        val input = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
        val radius = HeroBokeh.radiusPx(blurPx, source.width)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val (t0, t1) = HeroBokeh.thresholds(pixels)
        val shader = RuntimeShader(HeroBokeh.shaderSource(HeroBokeh.sampleCount(radius))).apply {
            // setInputShader preserves BitmapShader's explicit linear sampling (API 33).
            setInputShader("image", input)
            setFloatUniform("resolution", source.width.toFloat(), source.height.toFloat())
            // Match the old Gaussian's apparent strength: a disk's per-axis sigma is radius / 2.
            setFloatUniform("radius", radius)
            setFloatUniform("thresholds", t0, t1)
        }
        val node = RenderNode("Delos hero lens").apply {
            setPosition(0, 0, source.width, source.height)
        }
        val canvas = node.beginRecording()
        try {
            canvas.drawRect(0f, 0f, source.width.toFloat(), source.height.toFloat(), Paint().apply { this.shader = shader })
        } finally {
            node.endRecording()
        }
        // These are public GPU offscreen-rendering APIs. Keep every resource local to this one cache
        // fill; neither a Surface nor a renderer survives in the composition or in the bitmap cache.
        val reader = ImageReader.newInstance(
            source.width, source.height, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        // Created inside the try, so the finally releases the reader even if the renderer cannot be created.
        var renderer: HardwareRenderer? = null
        try {
            val gpu = HardwareRenderer().also { renderer = it }
            gpu.setSurface(reader.surface)
            gpu.setContentRoot(node)
            gpu.setOpaque(false)
            val status = gpu.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            check(status and (HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or HardwareRenderer.SYNC_CONTEXT_IS_STOPPED) == 0) {
                "Hero lens GPU render failed: $status"
            }
            val image = checkNotNull(reader.acquireNextImage()) { "Hero lens GPU image unavailable" }
            image.use {
                val buffer = checkNotNull(image.hardwareBuffer) { "Hero lens hardware buffer unavailable" }
                buffer.use {
                    val bitmap = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                    try {
                        // A small software copy owns its pixels after ImageReader and HardwareBuffer close.
                        return checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            renderer?.destroy()
            reader.close()
            node.discardDisplayList()
        }
    }
}
