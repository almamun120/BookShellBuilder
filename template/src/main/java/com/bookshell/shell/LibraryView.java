package com.bookshell.shell;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Shelf of all books in the app. */
final class LibraryView extends LinearLayout {
    interface Host {
        void onOpenBook(int index);

        void onLibraryAbout();
    }

    private final AppConfig cfg;
    private final SharedPreferences prefs;
    private final Host host;
    private final float d;
    private final LinearLayout grid;
    private final Map<Integer, Bitmap> covers = new HashMap<>();
    private int builtCols = -1;

    LibraryView(Context c, AppConfig cfg, SharedPreferences prefs, Host host) {
        super(c);
        this.cfg = cfg;
        this.prefs = prefs;
        this.host = host;
        d = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFFF3F1EC);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(cfg.accent);
        head.setPadding(dp(18), dp(10), dp(6), dp(10));
        head.setElevation(dp(4));
        TextView title = new TextView(c);
        title.setText(cfg.name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        head.addView(title, new LayoutParams(0, -2, 1f));
        TextView about = new TextView(c);
        about.setText("ⓘ");
        about.setTextColor(Color.WHITE);
        about.setTextSize(24);
        about.setGravity(Gravity.CENTER);
        about.setOnClickListener(v -> host.onLibraryAbout());
        head.addView(about, new LayoutParams(dp(48), dp(48)));
        addView(head, new LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(c);
        grid = new LinearLayout(c);
        grid.setOrientation(VERTICAL);
        grid.setPadding(dp(12), dp(12), dp(12), dp(24));
        sv.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        addView(sv, new LayoutParams(-1, 0, 1f));
    }

    private int dp(int v) {
        return (int) (v * d + 0.5f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int cols = Math.max(2, Math.min(6, (int) (w / (150 * d))));
        if (cols != builtCols) build(cols, w);
    }

    /** Called when coming back from the reader so progress labels are fresh. */
    void refresh() {
        if (builtCols > 0) build(builtCols, getWidth());
    }

    private Bitmap cover(int i) {
        Bitmap b = covers.get(i);
        if (b != null) return b;
        try (InputStream in = getContext().getAssets().open("covers/" + i + ".jpg")) {
            b = BitmapFactory.decodeStream(in);
            if (b != null) covers.put(i, b);
        } catch (Exception ignored) {
        }
        return b;
    }

    private void build(int cols, int totalW) {
        builtCols = cols;
        grid.removeAllViews();
        int cardW = (totalW - dp(24) - cols * dp(16)) / cols;
        if (cardW < dp(80)) cardW = dp(80);
        int count = cfg.books.size();
        for (int r = 0; r * cols < count; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            for (int k = 0; k < cols; k++) {
                int idx = r * cols + k;
                LayoutParams lp = new LayoutParams(cardW, -2);
                lp.setMargins(dp(8), dp(8), dp(8), dp(8));
                if (idx < count) row.addView(card(idx, cardW), lp);
                else row.addView(new View(getContext()), lp);
            }
            grid.addView(row, new LayoutParams(-1, -2));
        }
    }

    private View card(final int idx, int w) {
        AppConfig.Book b = cfg.books.get(idx);
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.setOnClickListener(v -> host.onOpenBook(idx));

        FrameLayout box = new FrameLayout(getContext());
        int h = (int) (w * 1.4f);
        Bitmap bmp = b.cover ? cover(idx) : null;
        if (bmp != null) {
            ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(bmp);
            box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView t = new TextView(getContext());
            t.setText(b.title);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(Color.WHITE);
            t.setTextSize(16);
            t.setPadding(dp(10), dp(10), dp(10), dp(10));
            t.setBackgroundColor(cfg.accent);
            box.addView(t, new FrameLayout.LayoutParams(-1, -1));
        }
        box.setElevation(dp(5));
        final float radius = dp(8);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        box.setClipToOutline(true);
        c.addView(box, new LayoutParams(w, h));

        TextView title = new TextView(getContext());
        title.setText(b.title);
        title.setTextColor(0xFF1B1B1B);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams tp = new LayoutParams(-1, -2);
        tp.topMargin = dp(8);
        c.addView(title, tp);

        if (b.author != null && !b.author.isEmpty()) {
            TextView a = new TextView(getContext());
            a.setText(b.author);
            a.setTextColor(0xFF667085);
            a.setTextSize(12);
            a.setSingleLine(true);
            c.addView(a);
        }

        int pos = prefs.getInt("pos_" + b.id, -1);
        int n = prefs.getInt("n_" + b.id, 0);
        TextView pr = new TextView(getContext());
        pr.setTextSize(12);
        pr.setTextColor(cfg.accent);
        pr.setText(pos >= 0 && n > 0 ? "পৃষ্ঠা " + (pos + 1) + " / " + n : "পড়া শুরু করুন");
        c.addView(pr);
        return c;
    }
}
