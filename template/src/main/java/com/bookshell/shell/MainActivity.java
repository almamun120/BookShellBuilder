package com.bookshell.shell;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements ReaderView.Host, LibraryView.Host, AboutView.Host {
    private static final int S_LIBRARY = 0, S_READER = 1, S_ABOUT = 2;

    private AppConfig cfg;
    private SharedPreferences prefs;
    private FrameLayout content;
    private FrameLayout root;
    private SplashView splash;
    private LibraryView library;
    private ReaderView reader;
    private int state = S_LIBRARY;
    private int aboutReturn = S_LIBRARY;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = AppConfig.load(this);
        prefs = getSharedPreferences("reader", MODE_PRIVATE);

        switch (cfg.orientation) {
            case "portrait":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case "landscape":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        if (cfg.keepScreenOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().setStatusBarColor(cfg.fullscreen ? Color.TRANSPARENT : darker(cfg.accent));
        getWindow().setNavigationBarColor(cfg.readerBg);

        root = new FrameLayout(this);
        root.setBackgroundColor(cfg.readerBg);
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        if (cfg.books.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("এই অ্যাপে কোনো বই নেই");
            t.setTextColor(Color.WHITE);
            t.setGravity(Gravity.CENTER);
            content.addView(t, new FrameLayout.LayoutParams(-1, -1));
        } else if (cfg.books.size() == 1 && !cfg.alwaysLibrary) {
            openBook(0);
        } else {
            showLibrary();
        }

        splash = new SplashView(this, cfg);
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        ui.postDelayed(this::hideSplash, Math.max(300, cfg.splashMs));
    }

    private static int darker(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] *= 0.8f;
        return Color.HSVToColor(hsv);
    }

    private void hideSplash() {
        final SplashView s = splash;
        if (s == null) return;
        splash = null;
        s.animate().alpha(0f).setDuration(350).withEndAction(() -> root.removeView(s)).start();
    }

    // ------------------------------------------------------------------ screens

    private void show(View v) {
        content.removeAllViews();
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showLibrary() {
        if (reader != null) reader.close();
        if (library == null) library = new LibraryView(this, cfg, prefs, this);
        else library.refresh();
        state = S_LIBRARY;
        show(library);
    }

    private void openBook(int index) {
        if (reader == null) reader = new ReaderView(this, cfg, prefs, this);
        state = S_READER;
        show(reader);
        reader.open(cfg.books.get(index));
    }

    private void showAbout() {
        aboutReturn = state;
        state = S_ABOUT;
        show(new AboutView(this, cfg, this));
    }

    private boolean multi() {
        return cfg.books.size() > 1 || cfg.alwaysLibrary;
    }

    // ------------------------------------------------------------------ callbacks

    @Override
    public void onOpenBook(int index) {
        openBook(index);
    }

    @Override
    public void onLibraryAbout() {
        showAbout();
    }

    @Override
    public void onReaderBack() {
        if (multi()) showLibrary();
        else finish();
    }

    @Override
    public void onReaderAbout() {
        showAbout();
    }

    @Override
    public boolean hasLibrary() {
        return multi();
    }

    @Override
    public void onAboutBack() {
        if (aboutReturn == S_READER && reader != null) {
            state = S_READER;
            show(reader);
        } else if (multi()) {
            showLibrary();
        } else if (reader != null) {
            state = S_READER;
            show(reader);
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (state == S_ABOUT) onAboutBack();
        else if (state == S_READER) onReaderBack();
        else finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (state == S_READER && reader != null && reader.onKey(keyCode)) return true;
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        // swallow the matching key-up so the volume bar does not pop up
        if (state == S_READER && cfg.volumeKeys
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true;
        return super.onKeyUp(keyCode, e);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && cfg.fullscreen) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (reader != null) reader.destroy();
        super.onDestroy();
    }
}
