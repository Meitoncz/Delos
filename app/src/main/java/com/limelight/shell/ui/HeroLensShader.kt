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
 * A uniform circular aperture, not a Gaussian blur. Rendered once per artwork/strength at 420 × 252;
 * motion only transforms the cached result, never runs a many-tap shader across the screen.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object HeroLensShader {
    fun render(source: Bitmap, blurPx: Int): Bitmap {
        val input = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
        val shader = RuntimeShader(SOURCE).apply {
            // setInputShader preserves BitmapShader's explicit linear sampling (API 33).
            setInputShader("image", input)
            setFloatUniform("resolution", source.width.toFloat(), source.height.toFloat())
            // Match the old Gaussian's apparent strength: a disk's per-axis sigma is radius / 2.
            setFloatUniform("radius", blurPx.coerceIn(0, 40) * (source.width / 2100f) * 2f)
        }
        val node = RenderNode("Delos hero lens").apply { // l10n-ignore
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
                "Hero lens GPU render failed: $status" // l10n-ignore
            }
            val image = checkNotNull(reader.acquireNextImage()) { "Hero lens GPU image unavailable" } // l10n-ignore
            image.use {
                val buffer = checkNotNull(image.hardwareBuffer) { "Hero lens hardware buffer unavailable" } // l10n-ignore
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

    // Equal-area golden-angle disk samples make a round bokeh footprint without directional box
    // streaks. Blur is static: 48 taps × 420 × 252 only on a cache miss, not on animation frames.
    internal const val SOURCE = """
uniform shader image;
uniform float2 resolution;
uniform float radius;

half4 main(float2 p) {
    half4 color = half4(0.0);
    for (int i = 0; i < 48; ++i) {
        float fi = float(i);
        float angle = fi * 2.39996323;
        float r = radius * sqrt((fi + 0.5) / 48.0);
        color += image.eval(p + r * float2(cos(angle), sin(angle)));
    }
    color /= 48.0;
    // Same diagonal stops as HeroBitmaps' legacy snapshot, after (not inside) the blur.
    float2 direction = float2(-resolution.x, resolution.y);
    float t = dot(p - float2(resolution.x, 0.0), direction) / dot(direction, direction);
    float alpha = clamp((0.95 - t) / 0.65, 0.0, 1.0);
    return color * half(alpha);
}
"""
}
