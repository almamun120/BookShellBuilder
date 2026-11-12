package com.bookshell.shell;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The book screen: page turner, scroll bar (vertical in portrait, horizontal in landscape) and a small top bar. */
final class ReaderView extends FrameLayout {
    interface Host {
        void onReaderBack();

        void onReaderAbout();

        boolean hasLibrary();
    }

    private final AppConfig cfg;
    private final Host host;
    private final SharedPreferences prefs;
    private final float d;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final FlipView flip;
    private final PageScrollBar bar;
    private final LinearLayout topBar;
    private final TextView tvTitle, tvPage, tvBubble, tvMsg;

    private AppConfig.Book book;
    private PageSource source;
    private float textScale = 1f;
    private int loadGen;
    private boolean barsShown = true;
    private int lastScrub = -1;

    private final Runnable hideTop = () -> setTopBar(false);

    ReaderView(Context c, AppConfig cfg, SharedPreferences prefs, Host host) {
        super(c);
        this.cfg = cfg;
        this.host = host;
        this.prefs = prefs;
        d = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(cfg.readerBg);

        flip = new FlipView(c);
        flip.setBackdrop(cfg.readerBg);
        flip.setFlipDuration(cfg.flipMs);
        addView(flip, new LayoutParams(-1, -1));

        bar = new PageScrollBar(c);
        bar.setAccent(cfg.accent);
        bar.setVisibility(cfg.scrollBar ? VISIBLE : GONE);
        addView(bar, new LayoutParams(dp(34), -1, Gravity.END));

        topBar = new LinearLayout(c);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xCC000000);
        topBar.setPadding(dp(4), dp(2), dp(4), dp(2));
        TextView back = icon("←");
        back.setOnClickListener(v -> host.onReaderBack());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        tvTitle = new TextView(c);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(15);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        tvPage = new TextView(c);
        tvPage.setTextColor(0xFFDDDDDD);
        tvPage.setTextSize(13);
        tvPage.setPadding(dp(8), 0, dp(8), 0);
        topBar.addView(tvPage);
        TextView menu = icon("⋮");
        menu.setOnClickListener(v -> showMenu());
        topBar.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        addView(topBar, new LayoutParams(-1, -2, Gravity.TOP));

        tvBubble = new TextView(c);
        tvBubble.setTextColor(Color.WHITE);
        tvBubble.setTextSize(20);
        tvBubble.setTypeface(Typeface.DEFAULT_BOLD);
        tvBubble.setGravity(Gravity.CENTER);
        tvBubble.setPadding(dp(22), dp(12), dp(22), dp(12));
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(0xDD000000);
        bb.setCornerRadius(dp(14));
        tvBubble.setBackground(bb);
        tvBubble.setVisibility(GONE);
        addView(tvBubble, new LayoutParams(-2, -2, Gravity.CENTER));

        tvMsg = new TextView(c);
        tvMsg.setTextColor(0xFFEEEEEE);
        tvMsg.setTextSize(16);
        tvMsg.setGravity(Gravity.CENTER);
        tvMsg.setPadding(dp(30), 0, dp(30), 0);
        addView(tvMsg, new LayoutParams(-1, -2, Gravity.CENTER));

        flip.setListener(new FlipView.Listener() {
            @Override
            public void onPageChanged(int page) {
                updatePage(page);
            }

            @Override
            public void onTap() {
                setTopBar(!barsShown);
            }
        });
        bar.setListener((f, done) -> {
            int n = flip.pageCount();
            if (n <= 0) return;
            int p = Math.round(f * (n - 1));
            if (p != lastScrub) {
                lastScrub = p;
                flip.jumpTo(p);
            }
            if (done) {
                lastScrub = -1;
                ui.postDelayed(() -> tvBubble.setVisibility(GONE), 500);
            }
            tvBubble.setText("পৃষ্ঠা " + (p + 1) + " / " + n);
            tvBubble.setVisibility(VISIBLE);
        });
    }

    private int dp(int v) {
        return (int) (v * d + 0.5f);
    }

    private TextView icon(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(22);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        boolean land = w > h;
        bar.setVertical(!land);
        LayoutParams lp = (LayoutParams) bar.getLayoutParams();
        if (land) {
            lp.width = -1;
            lp.height = dp(40);
            lp.gravity = Gravity.BOTTOM;
            lp.leftMargin = dp(24);
            lp.rightMargin = dp(24);
            lp.topMargin = 0;
            lp.bottomMargin = 0;
            flip.setInsets(0, 0, 0, cfg.scrollBar ? dp(40) : 0);
        } else {
            lp.width = dp(34);
            lp.height = -1;
            lp.gravity = Gravity.END;
            lp.leftMargin = 0;
            lp.rightMargin = 0;
            lp.topMargin = dp(46);
            lp.bottomMargin = dp(10);
            flip.setInsets(0, 0, cfg.scrollBar ? dp(34) : 0, 0);
        }
        bar.setLayoutParams(lp);
    }

    // ------------------------------------------------------------------ open / close

    void open(AppConfig.Book b) {
        close();
        book = b;
        tvTitle.setText(b.title);
        tvPage.setText("");
        textScale = prefs.getFloat("scale", cfg.textScale);
        load(prefs.getInt("pos_" + b.id, 0), -1f);
        setTopBar(true);
    }

    private void load(final int startPage, final float fraction) {
        final int my = ++loadGen;
        tvMsg.setText("লোড হচ্ছে…");
        tvMsg.setVisibility(VISIBLE);
        final AppConfig.Book b = book;
        final float scale = textScale;
        new Thread(() -> {
            try {
                final PageSource s = BookLoader.load(getContext(), cfg, b, scale);
                post(() -> {
                    if (my != loadGen) {
                        s.close();
                        return;
                    }
                    PageSource old = source;
                    source = s;
                    int page = fraction >= 0 ? Math.round(fraction * Math.max(0, s.pageCount() - 1)) : startPage;
                    flip.setSource(s, page);
                    if (old != null) old.close();
                    tvMsg.setVisibility(GONE);
                });
            } catch (final Throwable t) {
                post(() -> {
                    if (my != loadGen) return;
                    tvMsg.setText("বইটি খোলা যায়নি\n" + t.getMessage());
                });
            }
        }).start();
    }

    void close() {
        loadGen++;
        flip.setSource(null, 0);
        if (source != null) {
            source.close();
            source = null;
        }
        book = null;
    }

    void destroy() {
        close();
        flip.release();
    }

    // ------------------------------------------------------------------ ui

    private void updatePage(int page) {
        int n = flip.pageCount();
        if (n <= 0 || book == null) return;
        String label;
        if (flip.isDouble() && page > 0 && page + 1 < n) label = (page + 1) + "–" + (page + 2) + " / " + n;
        else label = (page + 1) + " / " + n;
        tvPage.setText(label);
        bar.setValue(n > 1 ? page / (float) (n - 1) : 0f);
        prefs.edit().putInt("pos_" + book.id, page).putInt("n_" + book.id, n).apply();
    }

    private void setTopBar(boolean show) {
        barsShown = show;
        ui.removeCallbacks(hideTop);
        topBar.animate().alpha(show ? 1f : 0f).setDuration(180).start();
        topBar.setClickable(show);
        topBar.setEnabled(show);
        topBar.setVisibility(VISIBLE);
        if (!show) {
            topBar.postDelayed(() -> {
                if (!barsShown) topBar.setVisibility(INVISIBLE);
            }, 200);
        } else {
            ui.postDelayed(hideTop, 3500);
        }
    }

    boolean onKey(int keyCode) {
        if (!cfg.volumeKeys) return false;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            flip.next();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            flip.prev();
            return true;
        }
        return false;
    }

    private void showMenu() {
        ui.removeCallbacks(hideTop);
        final List<String> items = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        items.add("পৃষ্ঠায় যান");
        acts.add(this::askPage);
        if (source != null && source.isReflowable()) {
            items.add("লেখা বড় করুন (A+)");
            acts.add(() -> changeScale(textScale + 0.12f));
            items.add("লেখা ছোট করুন (A−)");
            acts.add(() -> changeScale(textScale - 0.12f));
        }
        if (host.hasLibrary()) {
            items.add("বইয়ের তালিকা");
            acts.add(host::onReaderBack);
        }
        items.add("এই অ্যাপ সম্পর্কে");
        acts.add(host::onReaderAbout);
        new AlertDialog.Builder(getContext())
                .setItems(items.toArray(new String[0]), (dlg, which) -> acts.get(which).run())
                .setOnDismissListener(dlg -> setTopBar(true))
                .show();
    }

    private void askPage() {
        final int n = flip.pageCount();
        if (n <= 0) return;
        final EditText e = new EditText(getContext());
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("১ – " + n);
        new AlertDialog.Builder(getContext())
                .setTitle("কোন পৃষ্ঠায় যাবেন?")
                .setView(e)
                .setPositiveButton("যান", (dlg, w) -> {
                    try {
                        int p = Integer.parseInt(e.getText().toString().trim());
                        flip.jumpTo(Math.max(1, Math.min(n, p)) - 1);
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton("বাতিল", null)
                .show();
    }

    private void changeScale(float s) {
        s = Math.max(0.7f, Math.min(2.2f, s));
        if (Math.abs(s - textScale) < 0.001f || book == null) return;
        int n = Math.max(1, flip.pageCount() - 1);
        float fraction = flip.currentPage() / (float) n;
        textScale = s;
        prefs.edit().putFloat("scale", s).apply();
        load(0, fraction);
    }
}
