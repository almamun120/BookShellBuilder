package com.bookshell.shell;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A real-book page turner.
 *
 * Landscape: two pages side by side (the cover sits alone on the right, like a closed book).
 * Portrait: one page. Dragging right-to-left turns forward, left-to-right turns back; the leaf
 * folds along a crease like paper, shows its back side, and casts a shadow.
 *
 * Model: a "leaf" rests on the right of the spine. s leaves have been turned. Double mode: leaf j has
 * page 2j on its front and 2j+1 on its back; the spread shows pages 2s-1 (left) and 2s (right).
 * Single mode: leaf j has page j on its front and blank paper on its back; the view shows page s.
 */
final class FlipView extends View {
    interface Listener {
        void onPageChanged(int page);

        void onTap();
    }

    private final float density;
    private final int slop;
    private final float flingMin;

    private Listener listener;
    private PageSource src;
    private int n;
    private boolean dbl;
    private boolean laidOut;
    private int s;          // leaves already turned
    private int maxS;
    private int curPage;
    private volatile int wantPage;
    private int pendingPage;

    private int insL, insT, insR, insB;
    private int bg = 0xFF1F2023;
    private int flipMs = 450;

    private float pw, ph, spineX;
    private int slotW, slotH;
    private final RectF leftR = new RectF();
    private final RectF rightR = new RectF();
    private final RectF spread = new RectF();

    // bitmaps
    private LruCache<Integer, Bitmap> cache;
    private final HashSet<Integer> pending = new HashSet<>();
    private ExecutorService exec = Executors.newSingleThreadExecutor();
    private int gen;

    // flip state
    private int leaf = -1;
    private float t;
    private boolean bottom = true;
    private float fingerDy;
    private ValueAnimator anim;

    // touch
    private static final int M_NONE = 0, M_DRAG = 1, M_IGNORE = 2;
    private int mode = M_NONE;
    private float downX, downY, t0;
    private VelocityTracker vt;

    // drawing
    private final CurlGeometry geo = new CurlGeometry();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path uPath = new Path();
    private final Path fPath = new Path();
    private final Path flapPath = new Path();
    private final Matrix mtx = new Matrix();
    private final float[] srcPts = new float[6];
    private final float[] dstPts = new float[6];
    private Paint spineLeft, spineRight, spineSingle;

    FlipView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        flingMin = 700f * density;
        stroke.setStyle(Paint.Style.STROKE);
        line.setStyle(Paint.Style.STROKE);
    }

    // ------------------------------------------------------------------ public API

    void setListener(Listener l) {
        listener = l;
    }

    void setBackdrop(int color) {
        bg = color;
        invalidate();
    }

    void setFlipDuration(int ms) {
        flipMs = Math.max(150, Math.min(1500, ms));
    }

    void setInsets(int l, int t, int r, int b) {
        insL = l;
        insT = t;
        insR = r;
        insB = b;
        relayout();
        invalidate();
    }

    void setSource(PageSource source, int startPage) {
        cancelAnim();
        leaf = -1;
        src = source;
        n = source == null ? 0 : source.pageCount();
        pendingPage = Math.max(0, Math.min(startPage, Math.max(0, n - 1)));
        laidOut = false;
        clearCache();
        slotW = slotH = 0;
        relayout();
        invalidate();
    }

    boolean isDouble() {
        return dbl;
    }

    int currentPage() {
        return curPage;
    }

    int pageCount() {
        return n;
    }

    boolean isBusy() {
        return anim != null || mode == M_DRAG;
    }

    /** Jumps without animation (used by the scroll bar and "go to page"). */
    void jumpTo(int page) {
        if (src == null) return;
        cancelAnim();
        leaf = -1;
        page = Math.max(0, Math.min(page, n - 1));
        s = Math.min(maxS, spreadOfPage(page));
        commit(false);
    }

    void next() {
        if (src == null || anim != null || s >= maxS) return;
        bottom = true;
        fingerDy = 0;
        animate(s, 0f, 1f, true);
    }

    void prev() {
        if (src == null || anim != null || s <= 0) return;
        bottom = true;
        fingerDy = 0;
        animate(s - 1, 1f, 0f, true);
    }

    void release() {
        cancelAnim();
        exec.shutdownNow();
        clearCache();
    }

    // ------------------------------------------------------------------ layout

    private int spreadOfPage(int p) {
        return dbl ? (p + 1) / 2 : p;
    }

    private int pageOfSpread(int sp) {
        return dbl ? (sp == 0 ? 0 : 2 * sp - 1) : sp;
    }

    private int leavesMax() {
        if (n <= 0) return 0;
        return dbl ? n / 2 : n - 1;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        relayout();
    }

    private void relayout() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || src == null || n <= 0) return;
        boolean newDbl = w > h;
        if (!laidOut || newDbl != dbl) {
            int p = laidOut ? pageOfSpread(s) : pendingPage;
            dbl = newDbl;
            maxS = leavesMax();
            s = Math.min(maxS, spreadOfPage(p));
            curPage = pageOfSpread(s);
            wantPage = curPage;
            laidOut = true;
        }
        float margin = 10 * density;
        float aw = w - insL - insR - 2 * margin;
        float ah = h - insT - insB - 2 * margin;
        float a = Math.max(0.2f, src.aspect());
        if (dbl) {
            ph = Math.min(ah, aw / (2 * a));
        } else {
            ph = Math.min(ah, aw / a);
        }
        pw = ph * a;
        float cx = insL + (w - insL - insR) / 2f;
        float cy = insT + (h - insT - insB) / 2f;
        float top = cy - ph / 2f;
        if (dbl) {
            spineX = cx;
            leftR.set(cx - pw, top, cx, top + ph);
            rightR.set(cx, top, cx + pw, top + ph);
            spread.set(cx - pw, top, cx + pw, top + ph);
        } else {
            spineX = cx - pw / 2f;
            rightR.set(spineX, top, spineX + pw, top + ph);
            leftR.set(spineX - pw, top, spineX, top + ph);
            spread.set(rightR);
        }
        int nw = Math.max(2, Math.round(pw)), nh = Math.max(2, Math.round(ph));
        if (nw != slotW || nh != slotH) {
            slotW = nw;
            slotH = nh;
            clearCache();
            long per = (long) nw * nh * 4;
            long budget = Math.min(Runtime.getRuntime().maxMemory() / 4, per * 14);
            cache = new LruCache<Integer, Bitmap>((int) Math.max(budget, per * 6)) {
                @Override
                protected int sizeOf(Integer k, Bitmap v) {
                    return v.getByteCount();
                }
            };
        }
        float sh = 26 * density;
        spineLeft = gradPaint(spineX - sh, 0, spineX, 0, 0x00000000, 0x40000000);
        spineRight = gradPaint(spineX, 0, spineX + sh, 0, 0x55000000, 0x00000000);
        spineSingle = gradPaint(spineX, 0, spineX + 16 * density, 0, 0x38000000, 0x00000000);
        if (listener != null) listener.onPageChanged(curPage);
        prefetch();
    }

    private Paint gradPaint(float x0, float y0, float x1, float y1, int c0, int c1) {
        Paint p = new Paint();
        p.setShader(new LinearGradient(x0, y0, x1, y1, c0, c1, Shader.TileMode.CLAMP));
        return p;
    }

    // ------------------------------------------------------------------ bitmaps

    private void clearCache() {
        gen++;
        if (cache != null) cache.evictAll();
        pending.clear();
    }

    private Bitmap bitmap(int page) {
        if (page < 0 || page >= n || cache == null) return null;
        Bitmap b = cache.get(page);
        if (b == null) request(page);
        return b;
    }

    private void request(final int page) {
        if (page < 0 || page >= n || pending.contains(page) || slotW <= 0) return;
        pending.add(page);
        final int g = gen, w = slotW, h = slotH;
        final PageSource source = src;
        try {
            exec.execute(() -> {
                Bitmap b = null;
                if (g == gen && Math.abs(page - wantPage) <= 12) {
                    try {
                        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        source.render(page, b);
                    } catch (Throwable e) {
                        b = null;
                    }
                }
                final Bitmap fb = b;
                post(() -> {
                    pending.remove(page);
                    if (g == gen && fb != null && cache != null) {
                        cache.put(page, fb);
                        invalidate();
                    }
                });
            });
        } catch (Exception rejected) {
            pending.remove(page);
        }
    }

    private void prefetch() {
        if (src == null || cache == null) return;
        int c = dbl ? Math.max(0, 2 * s - 1) : s;
        wantPage = c;
        request(c);
        if (dbl) request(c + 1);
        for (int d = 1; d <= 4; d++) {
            request(c + d + (dbl ? 1 : 0));
            request(c - d);
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(bg);
        if (src == null || n == 0 || slotW <= 0 || !laidOut) return;
        if (leaf >= 0 && t > 0.002f && t < 0.998f) {
            drawFlip(c, leaf, t);
        } else {
            drawStatic(c, leaf >= 0 ? (t >= 0.998f ? leaf + 1 : leaf) : s);
        }
    }

    private void drawStatic(Canvas c, int sp) {
        if (dbl) drawBase(c, 2 * sp - 1, 2 * sp, sp);
        else drawBase(c, -1, sp, sp);
    }

    private void drawPage(Canvas c, int page, RectF dst) {
        if (page < 0 || page >= n) return;
        Bitmap b = bitmap(page);
        if (b != null) {
            c.drawBitmap(b, null, dst, bmpPaint);
        } else {
            fill.setColor(0xFFF4F1EA);
            c.drawRect(dst, fill);
        }
    }

    private void drawStack(Canvas c, RectF page, boolean rightSide, int leaves) {
        float th = Math.min(7 * density, leaves * 0.6f * density);
        if (th < 1.5f) return;
        int lines = (int) (th / (1.5f * density)) + 1;
        float step = th / lines;
        for (int i = 0; i < lines; i++) {
            fill.setColor((i & 1) == 0 ? 0xFFE9E5DB : 0xFFCFC9BC);
            float inset = (lines - i) * 0.7f * density;
            float x0 = rightSide ? page.right + i * step : page.left - (i + 1) * step;
            c.drawRect(x0, page.top + inset * 0.3f + (i * 0.4f), x0 + step + 0.5f, page.bottom - inset * 0.3f - (i * 0.4f), fill);
        }
    }

    /** Pages at rest plus page stacks and spine shading. turned = number of leaves on the left. */
    private void drawBase(Canvas c, int leftPage, int rightPage, int turned) {
        int remaining = maxS - turned;
        if (dbl) {
            boolean hasL = leftPage >= 0 && leftPage < n;
            boolean hasR = rightPage >= 0 && rightPage < n;
            if (hasL) drawStack(c, leftR, false, turned);
            if (hasR) drawStack(c, rightR, true, remaining);
            if (hasL) {
                drawPage(c, leftPage, leftR);
                c.drawRect(leftR, spineLeft);
            }
            if (hasR) {
                drawPage(c, rightPage, rightR);
                c.drawRect(rightR, spineRight);
            }
        } else {
            if (rightPage >= 0 && rightPage < n) {
                drawStack(c, rightR, true, remaining);
                drawPage(c, rightPage, rightR);
                c.drawRect(rightR, spineSingle);
            }
        }
    }

    private void toPath(Path p, float[] pts, int count) {
        p.reset();
        if (count < 3) return;
        p.moveTo(pts[0], pts[1]);
        for (int i = 1; i < count; i++) p.lineTo(pts[2 * i], pts[2 * i + 1]);
        p.close();
    }

    private void drawFlip(Canvas c, int j, float tt) {
        RectF r = rightR;
        int frontP = dbl ? 2 * j : j;
        int backP = dbl ? 2 * j + 1 : -1;
        int underP = dbl ? 2 * j + 2 : j + 1;
        int leftP = dbl ? 2 * j - 1 : -1;

        drawBase(c, leftP, underP, j);

        // pull point: the far corner travels along the page, lifting a little in the middle
        float w = r.width();
        float cy = bottom ? r.bottom : r.top;
        float sin = (float) Math.sin(Math.PI * tt);
        float lift = Math.min(r.height() * 0.14f, w * 0.18f) * sin;
        float px = r.right - 2f * w * tt;
        float py = cy + (bottom ? -lift : lift) + fingerDy * 0.35f * sin;
        geo.compute(r.left, r.top, r.right, r.bottom, bottom, px, py);
        if (!geo.curled) {
            drawPage(c, frontP, r);
            return;
        }

        toPath(uPath, geo.unfolded, geo.unfoldedN);
        toPath(fPath, geo.folded, geo.foldedN);
        toPath(flapPath, geo.flap, geo.flapN);

        float mx = geo.mx, my = geo.my, nx = geo.nx, ny = geo.ny;

        // shadow on the page that is being uncovered, next to the crease
        float sh1 = Math.min(60 * density, pw * 0.4f);
        shade.setShader(new LinearGradient(mx, my, mx + nx * sh1, my + ny * sh1,
                new int[]{0x66000000, 0x22000000, 0x00000000}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(fPath, shade);

        // the part of the leaf that stays flat
        if (geo.unfoldedN >= 3) {
            c.save();
            c.clipPath(uPath);
            drawPage(c, frontP, r);
            float sh2 = 22 * density;
            shade.setShader(new LinearGradient(mx, my, mx - nx * sh2, my - ny * sh2,
                    0x30000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawPath(uPath, shade);
            c.restore();
        }

        // soft shadow cast by the lifted flap
        c.save();
        c.clipRect(spread);
        stroke.setColor(Color.BLACK);
        int[] widths = {20, 12, 6};
        int[] alphas = {12, 20, 34};
        for (int i = 0; i < 3; i++) {
            stroke.setStrokeWidth(widths[i] * density);
            stroke.setAlpha(alphas[i]);
            c.drawPath(flapPath, stroke);
        }
        c.restore();

        // the flap itself
        c.save();
        c.clipPath(flapPath);
        Bitmap frontBmp = bitmap(frontP);
        if (dbl) {
            Bitmap back = bitmap(backP);
            if (back != null) {
                geo.reflect(r.right, r.top);
                dstPts[0] = geo.rx;
                dstPts[1] = geo.ry;
                geo.reflect(r.left, r.top);
                dstPts[2] = geo.rx;
                dstPts[3] = geo.ry;
                geo.reflect(r.right, r.bottom);
                dstPts[4] = geo.rx;
                dstPts[5] = geo.ry;
                srcPts[0] = 0;
                srcPts[1] = 0;
                srcPts[2] = back.getWidth();
                srcPts[3] = 0;
                srcPts[4] = 0;
                srcPts[5] = back.getHeight();
                mtx.setPolyToPoly(srcPts, 0, dstPts, 0, 3);
                c.drawBitmap(back, mtx, bmpPaint);
            } else {
                c.drawPaint(paperFill());
            }
        } else {
            c.drawPaint(paperFill());
            if (frontBmp != null) {
                geo.reflect(r.left, r.top);
                dstPts[0] = geo.rx;
                dstPts[1] = geo.ry;
                geo.reflect(r.right, r.top);
                dstPts[2] = geo.rx;
                dstPts[3] = geo.ry;
                geo.reflect(r.left, r.bottom);
                dstPts[4] = geo.rx;
                dstPts[5] = geo.ry;
                srcPts[0] = 0;
                srcPts[1] = 0;
                srcPts[2] = frontBmp.getWidth();
                srcPts[3] = 0;
                srcPts[4] = 0;
                srcPts[5] = frontBmp.getHeight();
                mtx.setPolyToPoly(srcPts, 0, dstPts, 0, 3);
                bmpPaint.setAlpha(46);
                c.drawBitmap(frontBmp, mtx, bmpPaint);
                bmpPaint.setAlpha(255);
            }
        }
        // curvature shading: dark at the crease, fading out across the flap
        float depth = Math.min(pw * 0.55f, 120 * density);
        shade.setShader(new LinearGradient(mx, my, mx - nx * depth, my - ny * depth,
                new int[]{0x66000000, 0x1A000000, 0x08FFFFFF, 0x00000000}, new float[]{0f, 0.18f, 0.6f, 1f},
                Shader.TileMode.CLAMP));
        c.drawPath(flapPath, shade);
        c.restore();

        // thin anti-aliased edge hides the stair-stepping of the clip
        stroke.setStrokeWidth(Math.max(1f, density * 0.8f));
        stroke.setColor(0x55000000);
        c.drawPath(flapPath, stroke);
    }

    private final Paint paper = new Paint();

    private Paint paperFill() {
        paper.setColor(0xFFF3EFE6);
        return paper;
    }

    // ------------------------------------------------------------------ animation / state

    private void cancelAnim() {
        if (anim != null) {
            ValueAnimator a = anim;
            anim = null;
            a.cancel();
        }
    }

    private void animate(final int leafIdx, float from, final float to, boolean tap) {
        cancelAnim();
        leaf = leafIdx;
        t = from;
        long d = (long) (flipMs * Math.abs(to - from));
        d = tap ? flipMs : Math.max(160, d);
        final ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(d);
        a.setInterpolator(tap ? new AccelerateDecelerateInterpolator() : new DecelerateInterpolator(1.4f));
        a.addUpdateListener(v -> {
            t = (float) v.getAnimatedValue();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled || anim != a) return;
                anim = null;
                s = to >= 0.5f ? leafIdx + 1 : leafIdx;
                leaf = -1;
                t = 0;
                commit(true);
            }
        });
        anim = a;
        a.start();
    }

    private void commit(boolean notify) {
        curPage = pageOfSpread(s);
        wantPage = curPage;
        prefetch();
        invalidate();
        if (listener != null) listener.onPageChanged(curPage);
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (src == null || !laidOut) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                mode = anim != null ? M_IGNORE : M_NONE;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(e);
                if (mode == M_NONE) {
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 0.7f) {
                        if (dx < 0 && s < maxS) {
                            leaf = s;
                            t0 = 0f;
                            mode = M_DRAG;
                        } else if (dx > 0 && s > 0) {
                            leaf = s - 1;
                            t0 = 1f;
                            mode = M_DRAG;
                        } else {
                            mode = M_IGNORE;
                        }
                        if (mode == M_DRAG) {
                            bottom = downY > rightR.centerY();
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }
                if (mode == M_DRAG) {
                    float dt = (downX - e.getX()) * 1.6f / (2f * pw);
                    t = Math.max(0f, Math.min(1f, t0 + dt));
                    fingerDy = e.getY() - downY;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (vt != null) vt.addMovement(e);
                if (mode == M_DRAG) {
                    vt.computeCurrentVelocity(1000);
                    float vx = vt.getXVelocity();
                    float target = Math.abs(vx) > flingMin ? (vx < 0 ? 1f : 0f) : (t > 0.5f ? 1f : 0f);
                    animate(leaf, t, target, false);
                } else if (mode == M_NONE) {
                    float x = e.getX();
                    float w = getWidth();
                    if (x < w * 0.3f) prev();
                    else if (x > w * 0.7f) next();
                    else if (listener != null) listener.onTap();
                }
                mode = M_NONE;
                recycleVt();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (mode == M_DRAG) animate(leaf, t, t0, false);
                mode = M_NONE;
                recycleVt();
                return true;
            default:
                return true;
        }
    }

    private void recycleVt() {
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAnim();
    }
}
