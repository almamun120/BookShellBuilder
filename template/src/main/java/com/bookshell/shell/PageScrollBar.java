package com.bookshell.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** A thin scroll bar for jumping through the book. Vertical in portrait, horizontal in landscape. */
final class PageScrollBar extends View {
    interface Listener {
        /** fraction 0..1; done = finger lifted. */
        void onScrub(float fraction, boolean done);
    }

    private boolean vertical = true;
    private float value;       // 0..1
    private boolean dragging;
    private int accent = 0xFF3B82F6;
    private Listener listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;

    PageScrollBar(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setAccent(int c) {
        accent = c;
        invalidate();
    }

    void setVertical(boolean v) {
        if (vertical != v) {
            vertical = v;
            invalidate();
        }
    }

    void setValue(float v) {
        if (dragging) return;
        value = Math.max(0f, Math.min(1f, v));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float pad = 18 * d;
        float thumbR = 11 * d;
        float track = 4 * d;
        if (vertical) {
            float cx = w / 2f;
            float y0 = pad, y1 = h - pad;
            p.setColor(0x44FFFFFF);
            r.set(cx - track / 2, y0, cx + track / 2, y1);
            c.drawRoundRect(r, track, track, p);
            float ty = y0 + (y1 - y0) * value;
            p.setColor(accent);
            r.set(cx - track / 2, y0, cx + track / 2, ty);
            c.drawRoundRect(r, track, track, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(cx, ty, thumbR + (dragging ? 3 * d : 0), p);
            p.setColor(accent);
            c.drawCircle(cx, ty, thumbR * 0.55f, p);
        } else {
            float cy = h / 2f;
            float x0 = pad, x1 = w - pad;
            p.setColor(0x44FFFFFF);
            r.set(x0, cy - track / 2, x1, cy + track / 2);
            c.drawRoundRect(r, track, track, p);
            float tx = x0 + (x1 - x0) * value;
            p.setColor(accent);
            r.set(x0, cy - track / 2, tx, cy + track / 2);
            c.drawRoundRect(r, track, track, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(tx, cy, thumbR + (dragging ? 3 * d : 0), p);
            p.setColor(accent);
            c.drawCircle(tx, cy, thumbR * 0.55f, p);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float pad = 18 * d;
        float f;
        if (vertical) f = (e.getY() - pad) / Math.max(1f, getHeight() - 2 * pad);
        else f = (e.getX() - pad) / Math.max(1f, getWidth() - 2 * pad);
        f = Math.max(0f, Math.min(1f, f));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                value = f;
                invalidate();
                if (listener != null) listener.onScrub(f, false);
                return true;
            case MotionEvent.ACTION_MOVE:
                value = f;
                invalidate();
                if (listener != null) listener.onScrub(f, false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                value = f;
                dragging = false;
                invalidate();
                if (listener != null) listener.onScrub(f, true);
                return true;
            default:
                return true;
        }
    }
}
