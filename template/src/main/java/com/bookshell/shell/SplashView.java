package com.bookshell.shell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.io.InputStream;

/** Splash screen: custom image (logo size or full screen fit/crop/stretch), app name and a spinner. */
final class SplashView extends View {
    private final AppConfig cfg;
    private Bitmap img;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final float d;
    private final long start = System.currentTimeMillis();

    SplashView(Context c, AppConfig cfg) {
        super(c);
        this.cfg = cfg;
        d = c.getResources().getDisplayMetrics().density;
        if (cfg.splashImage) {
            try (InputStream in = c.getAssets().open("splash.img")) {
                img = BitmapFactory.decodeStream(in);
            } catch (Exception ignored) {
            }
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(22 * d);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(3 * d);
        arc.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true); // swallow touches while visible
    }

    private static boolean isDark(int c) {
        double l = (0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255)) / 255.0;
        return l < 0.5;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawColor(cfg.splashBg);
        int fg = isDark(cfg.splashBg) ? 0xFFFFFFFF : 0xFF111111;
        boolean full = cfg.splashMode.startsWith("full");
        float nameY = h * 0.5f;

        if (img != null) {
            src.set(0, 0, img.getWidth(), img.getHeight());
            if (full) {
                switch (cfg.splashMode) {
                    case "fullStretch":
                        dst.set(0, 0, w, h);
                        break;
                    case "fullCrop": {
                        float s = Math.max(w / img.getWidth(), h / img.getHeight());
                        float dw = img.getWidth() * s, dh = img.getHeight() * s;
                        dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
                        break;
                    }
                    default: {
                        float s = Math.min(w / img.getWidth(), h / img.getHeight());
                        float dw = img.getWidth() * s, dh = img.getHeight() * s;
                        dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
                    }
                }
                c.drawBitmap(img, src, dst, p);
                nameY = h - 120 * d;
            } else {
                float box = cfg.splashSize * d;
                float s = Math.min(box / img.getWidth(), box / img.getHeight());
                float dw = img.getWidth() * s, dh = img.getHeight() * s;
                float top = h * 0.42f - dh / 2f;
                dst.set((w - dw) / 2, top, (w + dw) / 2, top + dh);
                c.drawBitmap(img, src, dst, p);
                nameY = dst.bottom + 40 * d;
            }
        }
        if (cfg.splashName) {
            text.setColor(full && img != null ? 0xFFFFFFFF : fg);
            if (full && img != null) text.setShadowLayer(6 * d, 0, 2 * d, 0xCC000000);
            else text.clearShadowLayer();
            c.drawText(cfg.name, w / 2f, nameY, text);
        }
        if (cfg.splashSpinner) {
            float r = 14 * d;
            float cy = Math.min(h - 50 * d, nameY + 54 * d);
            oval.set(w / 2f - r, cy - r, w / 2f + r, cy + r);
            arc.setColor(full && img != null ? 0xFFFFFFFF : cfg.accent);
            float a = ((System.currentTimeMillis() - start) % 1000) / 1000f * 360f;
            c.drawArc(oval, a, 100, false, arc);
            postInvalidateOnAnimation();
        }
    }
}
