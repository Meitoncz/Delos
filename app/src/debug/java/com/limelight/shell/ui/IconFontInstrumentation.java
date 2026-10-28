package com.limelight.shell.ui;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.res.ResourcesCompat;

import com.limelight.R;

import java.io.File;
import java.io.FileOutputStream;

/** Rasterizes the bundled icon fonts on the real device; no Activity or stream is started. */
public final class IconFontInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        StringBuilder report = new StringBuilder();
        Bitmap sheet = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888);
        try {
            int[] fonts = {R.font.phosphor_regular, R.font.phosphor_fill};
            String[] names = {"Regular", "Fill"};
            String[] icons = {Symbols.ADD, Symbols.SETTINGS, Symbols.COMPUTER, Symbols.ACCOUNT_CIRCLE};
            Canvas preview = new Canvas(sheet);
            preview.drawColor(Color.rgb(18, 25, 30));
            Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
            guide.setColor(Color.rgb(70, 100, 110));
            guide.setStyle(Paint.Style.STROKE);
            guide.setStrokeWidth(1);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            for (int weight = 0; weight < fonts.length; weight++) {
                Typeface typeface = ResourcesCompat.getFont(getTargetContext(), fonts[weight]);
                if (typeface == null) throw new AssertionError("Missing bundled font");
                paint.setTypeface(typeface);
                float maxError = 0;
                for (int size : new int[]{24, 48, 96}) {
                    paint.setTextSize(size);
                    Bitmap image = Bitmap.createBitmap(size * 3, size * 3, Bitmap.Config.ARGB_8888);
                    try {
                        Canvas canvas = new Canvas(image);
                        float center = image.getWidth() * .5f;
                        Paint.FontMetrics metrics = paint.getFontMetrics();
                        float baseline = image.getHeight() * .5f - (metrics.ascent + metrics.descent) * .5f;
                        for (int icon = 0; icon < icons.length; icon++) {
                            image.eraseColor(Color.TRANSPARENT);
                            float advance = paint.measureText(icons[icon]);
                            if (Math.abs(advance - size) > .01f) throw new AssertionError("Non-square icon advance");
                            canvas.drawText(icons[icon], center - advance * .5f, baseline, paint);
                            int left = image.getWidth(), right = -1;
                            for (int y = 0; y < image.getHeight(); y++) {
                                for (int x = 0; x < image.getWidth(); x++) {
                                    if (Color.alpha(image.getPixel(x, y)) > 32) {
                                        left = Math.min(left, x);
                                        right = Math.max(right, x);
                                    }
                                }
                            }
                            if (right < left) throw new AssertionError("Icon did not rasterize");
                            float error = Math.abs((left + right + 1) * .5f - center);
                            maxError = Math.max(maxError, error);
                            if (error > .5f) throw new AssertionError(names[weight] + " icon=" + icon +
                                    " size=" + size + " horizontal center error=" + error);
                            if (size == 96) {
                                float cx = icon * 128 + 64, cy = weight * 128 + 64;
                                preview.drawCircle(cx, cy, 56, guide);
                                preview.drawLine(cx, cy - 60, cx, cy + 60, guide);
                                preview.drawText(icons[icon], cx - advance * .5f,
                                        cy - (metrics.ascent + metrics.descent) * .5f, paint);
                            }
                        }
                    } finally {
                        image.recycle();
                    }
                }
                report.append("PASS ").append(names[weight]).append(" plus/gear/desktop/user-circle at 24/48/96px; max center error=")
                        .append(maxError).append("px\n");
            }
            File file = new File(getTargetContext().getExternalFilesDir(null), "icon-centering.png");
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (!sheet.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new AssertionError("Cannot save raster proof");
            }
            report.append("Raster proof: ").append(file.getAbsolutePath()).append('\n');
            result.putString("stream", report.toString());
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", report + "FAIL: " + Log.getStackTraceString(error));
            finish(0, result);
        } finally {
            sheet.recycle();
        }
    }
}
