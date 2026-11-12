package com.bookshell.shell;

/**
 * Pure geometry of a paper page being turned (no Android classes, so it can be unit-tested).
 *
 * A leaf rests on the right of its spine (the left edge of the rectangle). The far corner C is
 * pulled to the point P. The crease is the perpendicular bisector of C-P; the part of the leaf on
 * C's side is folded over onto the other side of the crease (the "flap").
 */
final class CurlGeometry {
    /** Unfolded part of the leaf (stays flat), as x,y pairs. */
    final float[] unfolded = new float[20];
    int unfoldedN;
    /** The part that was lifted (original position), x,y pairs. */
    final float[] folded = new float[20];
    int foldedN;
    /** The lifted part after being laid over the crease. */
    final float[] flap = new float[20];
    int flapN;

    /** A point on the crease and the unit normal that points to the corner's side. */
    float mx, my, nx, ny;
    /** Clamped pull point. */
    float px, py;
    boolean curled;

    float rx, ry; // result of reflect()

    void reflect(float x, float y) {
        float d = (x - mx) * nx + (y - my) * ny;
        rx = x - 2 * d * nx;
        ry = y - 2 * d * ny;
    }

    void compute(float l, float t, float r, float b, boolean bottom, float pullX, float pullY) {
        float w = r - l;
        float h = b - t;
        float cx = r;
        float cy = bottom ? b : t;
        float oy = bottom ? t : b;
        float x = pullX;
        float y = pullY;

        // paper cannot stretch: the corner stays w away from the spine point beside it and
        // hypot(w,h) away from the opposite spine corner
        for (int i = 0; i < 3; i++) {
            float dx = x - l, dy = y - cy;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > w && d > 0) {
                x = l + dx * w / d;
                y = cy + dy * w / d;
            }
            float diag = (float) Math.sqrt(w * w + h * h);
            dx = x - l;
            dy = y - oy;
            d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > diag && d > 0) {
                x = l + dx * diag / d;
                y = oy + dy * diag / d;
            }
        }
        px = x;
        py = y;

        float ddx = cx - x, ddy = cy - y;
        float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy);
        if (dist < 0.75f) {
            curled = false;
            unfoldedN = foldedN = flapN = 0;
            return;
        }
        nx = ddx / dist;
        ny = ddy / dist;
        mx = (cx + x) / 2f;
        my = (cy + y) / 2f;

        float[] quad = {l, t, r, t, r, b, l, b};
        unfoldedN = clip(quad, 4, -1f, unfolded);
        foldedN = clip(quad, 4, 1f, folded);
        flapN = foldedN;
        for (int i = 0; i < foldedN; i++) {
            reflect(folded[2 * i], folded[2 * i + 1]);
            flap[2 * i] = rx;
            flap[2 * i + 1] = ry;
        }
        curled = foldedN >= 3;
    }

    /** Keeps the part of the polygon where sign * ((X - M) . n) >= 0. Returns the point count. */
    private int clip(float[] src, int n, float sign, float[] dst) {
        int out = 0;
        for (int i = 0; i < n; i++) {
            float ax = src[2 * i], ay = src[2 * i + 1];
            int j = (i + 1) % n;
            float bx = src[2 * j], by = src[2 * j + 1];
            float da = sign * ((ax - mx) * nx + (ay - my) * ny);
            float db = sign * ((bx - mx) * nx + (by - my) * ny);
            boolean ina = da >= 0, inb = db >= 0;
            if (ina) {
                dst[2 * out] = ax;
                dst[2 * out + 1] = ay;
                out++;
            }
            if (ina != inb) {
                float k = da / (da - db);
                dst[2 * out] = ax + (bx - ax) * k;
                dst[2 * out + 1] = ay + (by - ay) * k;
                out++;
            }
        }
        return out;
    }
}
