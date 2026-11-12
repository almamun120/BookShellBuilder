package com.bookshell.shell;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** The "about" page with the owner's own text and links. */
final class AboutView extends LinearLayout {
    interface Host {
        void onAboutBack();
    }

    AboutView(Context c, AppConfig cfg, Host host) {
        super(c);
        final float d = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFFF8F7F4);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(cfg.accent);
        head.setElevation(4 * d);
        TextView back = new TextView(c);
        back.setText("←");
        back.setTextColor(Color.WHITE);
        back.setTextSize(24);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> host.onAboutBack());
        head.addView(back, new LayoutParams((int) (52 * d), (int) (52 * d)));
        TextView ht = new TextView(c);
        ht.setText("সম্পর্কে");
        ht.setTextColor(Color.WHITE);
        ht.setTextSize(18);
        ht.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(ht);
        addView(head, new LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(c);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (22 * d);
        body.setPadding(pad, pad, pad, pad * 2);
        sv.addView(body, new FrameLayout.LayoutParams(-1, -2));
        addView(sv, new LayoutParams(-1, 0, 1f));

        try {
            ImageView icon = new ImageView(c);
            icon.setImageDrawable(c.getPackageManager().getApplicationIcon(c.getPackageName()));
            body.addView(icon, new LayoutParams((int) (88 * d), (int) (88 * d)));
        } catch (Exception ignored) {
        }

        String title = cfg.aboutTitle.isEmpty() ? cfg.name : cfg.aboutTitle;
        TextView t = text(c, title, 22, 0xFF101828, true);
        t.setGravity(Gravity.CENTER);
        LayoutParams tp = new LayoutParams(-1, -2);
        tp.topMargin = (int) (14 * d);
        body.addView(t, tp);

        String ver = "";
        try {
            ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        if (ver != null && !ver.isEmpty()) {
            TextView v = text(c, "সংস্করণ " + ver, 13, 0xFF667085, false);
            v.setGravity(Gravity.CENTER);
            body.addView(v, new LayoutParams(-1, -2));
        }

        if (!cfg.aboutText.isEmpty()) {
            TextView x = text(c, cfg.aboutText, 15, 0xFF344054, false);
            x.setLineSpacing(0, 1.3f);
            x.setTextIsSelectable(true);
            LayoutParams xp = new LayoutParams(-1, -2);
            xp.topMargin = (int) (18 * d);
            body.addView(x, xp);
        }

        if (!cfg.aboutDeveloper.isEmpty()) {
            TextView x = text(c, cfg.aboutDeveloper, 14, 0xFF475467, true);
            x.setGravity(Gravity.CENTER);
            LayoutParams xp = new LayoutParams(-1, -2);
            xp.topMargin = (int) (20 * d);
            body.addView(x, xp);
        }

        for (final AppConfig.Link l : cfg.aboutLinks) {
            TextView b = text(c, l.title, 15, Color.WHITE, true);
            b.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setColor(cfg.accent);
            g.setCornerRadius(12 * d);
            b.setBackground(g);
            b.setPadding(0, (int) (12 * d), 0, (int) (12 * d));
            b.setOnClickListener(v -> {
                try {
                    String u = l.url.contains(":") ? l.url : "https://" + l.url;
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(c, "লিংক খোলা যায়নি", Toast.LENGTH_SHORT).show();
                }
            });
            LayoutParams bp = new LayoutParams(-1, -2);
            bp.topMargin = (int) (12 * d);
            body.addView(b, bp);
        }
    }

    private static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
}
