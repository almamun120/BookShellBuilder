package com.bookshell.builder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQ_ICON = 1, REQ_SPLASH = 2, REQ_FILES = 3, REQ_COVER = 4;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final Pattern PKG = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");
    private static final int BLUE = 0xFF2F5BEA;

    private SharedPreferences sp;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private final Map<String, Spinner> spinners = new LinkedHashMap<>();
    private final List<BookSpec> books = new ArrayList<>();
    private LinearLayout booksBox;
    private BookSpec pendingBook;
    private ImageView ivIcon, ivSplash;
    private TextView tvStatus, tvResult;
    private Button btnBuild, btnShare;
    private LinearLayout resultBox;
    private File lastApk;
    private Uri lastSaved;

    private static final String[] ORIENT_L = {"অটো (ঘোরালে বদলাবে)", "সবসময় পোর্ট্রেট", "সবসময় ল্যান্ডস্কেপ"};
    private static final String[] ORIENT_V = {"auto", "portrait", "landscape"};
    private static final String[] SPLASH_SRC_L = {"প্রথম বইয়ের প্রথম পেজ / কভার", "আমার কাস্টম ছবি", "ছবি ছাড়া (শুধু রং ও নাম)"};
    private static final String[] SPLASH_SRC_V = {"page", "custom", "none"};
    private static final String[] SPLASH_MODE_L = {"ফুল স্ক্রিন — ফিট (পুরো ছবি দেখাবে)", "ফুল স্ক্রিন — ক্রপ (স্ক্রিন ভরাট)",
            "ফুল স্ক্রিন — স্ট্রেচ", "লোগো (কাস্টম সাইজ)"};
    private static final String[] SPLASH_MODE_V = {"fullFit", "fullCrop", "fullStretch", "logo"};
    private static final String[] ICON_SRC_L = {"প্রথম বইয়ের কভার", "আমার কাস্টম ছবি", "অ্যাপ নামের প্রথম অক্ষর"};
    private static final String[] ICON_SRC_V = {"cover", "custom", "letter"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("bookbuilder", MODE_PRIVATE);
        loadBooks();
        buildUi();
        renderBooks();
    }

    @Override
    protected void onPause() {
        savePrefs();
        super.onPause();
    }

    // ------------------------------------------------------------------ UI helpers

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(Color.WHITE, 0xFFE4E7EC, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(14);
        parent.addView(c, lp);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF101828);
        c.addView(t);
        return c;
    }

    private TextView label(LinearLayout c, String text) {
        TextView l = new TextView(this);
        l.setText(text);
        l.setTextSize(13);
        l.setTextColor(0xFF475467);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(12);
        c.addView(l, lp);
        return l;
    }

    private void hint(LinearLayout c, String text) {
        TextView l = new TextView(this);
        l.setText(text);
        l.setTextSize(12);
        l.setTextColor(0xFF667085);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(6);
        c.addView(l, lp);
    }

    private EditText edit(String hintText, String text, int type) {
        EditText e = new EditText(this);
        e.setHint(hintText);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setBackground(rounded(0xFFF9FAFB, 0xFFD0D5DD, 10));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setText(text);
        return e;
    }

    private EditText field(LinearLayout c, String key, String label, String hintText, String def, int type) {
        label(c, label);
        EditText e = edit(hintText, sp.getString("f_" + key, def), type);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(MATCH, WRAP);
        ep.topMargin = dp(4);
        c.addView(e, ep);
        fields.put(key, e);
        return e;
    }

    private EditText area(LinearLayout c, String key, String label, String hintText, String def, int lines) {
        EditText e = field(c, key, label, hintText, def, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setSingleLine(false);
        e.setMinLines(lines);
        e.setGravity(Gravity.TOP | Gravity.START);
        return e;
    }

    private Spinner choice(LinearLayout c, String key, String label, String[] labels) {
        label(c, label);
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        s.setSelection(Math.min(sp.getInt("sel_" + key, 0), labels.length - 1));
        c.addView(s);
        spinners.put(key, s);
        return s;
    }

    private String pick(String key, String[] values) {
        return values[spinners.get(key).getSelectedItemPosition()];
    }

    private Switch sw(LinearLayout c, String key, String text, boolean def) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setTextSize(14);
        s.setTextColor(0xFF1D2939);
        s.setChecked(sp.getBoolean("s_" + key, def));
        s.setPadding(0, dp(6), 0, dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(6);
        c.addView(s, lp);
        switches.put(key, s);
        return s;
    }

    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setBackground(rounded(color, 0, 12));
        return b;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFFF2F4F7);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(32));
        sv.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));

        TextView h = new TextView(this);
        h.setText("BookShell Builder");
        h.setTextSize(24);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(0xFF101828);
        root.addView(h);
        TextView sub = new TextView(this);
        sub.setText("PDF / EPUB থেকে বাস্তব বইয়ের মতো পেজ-ওল্টানো APK তৈরি করুন");
        sub.setTextSize(13);
        sub.setTextColor(0xFF667085);
        root.addView(sub);

        // ---- app
        LinearLayout c = card(root, "অ্যাপ");
        field(c, "name", "অ্যাপের নাম", "আমার বই", "", InputType.TYPE_CLASS_TEXT);
        field(c, "pkg", "প্যাকেজ নেম", "com.mycompany.mybook", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field(c, "verName", "ভার্সন নাম", "1.0", "1.0", InputType.TYPE_CLASS_TEXT);
        field(c, "verCode", "ভার্সন কোড (আপডেটের সময় বাড়ান)", "1", "1", InputType.TYPE_CLASS_NUMBER);

        // ---- books
        c = card(root, "বই ও লাইব্রেরি");
        hint(c, "একটি বইয়ে একাধিক PDF/EPUB যোগ করলে সেগুলো ক্রমানুসারে জুড়ে একটি বই হবে। একাধিক বই যোগ করলে অ্যাপে বইয়ের তালিকা (লাইব্রেরি) আসবে। URL দিলে APK তৈরির সময় লিংক থেকে ফাইল ডাউনলোড হয়ে অ্যাপের ভেতরে ঢুকে যাবে।");
        booksBox = new LinearLayout(this);
        booksBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(booksBox, new LinearLayout.LayoutParams(MATCH, WRAP));
        Button addBook = button("+ নতুন বই", BLUE);
        LinearLayout.LayoutParams ab = new LinearLayout.LayoutParams(MATCH, dp(46));
        ab.topMargin = dp(12);
        c.addView(addBook, ab);
        addBook.setOnClickListener(v -> {
            BookSpec bs = new BookSpec();
            bs.title = "বই " + (books.size() + 1);
            books.add(bs);
            saveBooks();
            renderBooks();
        });
        switchesLater(c);

        // ---- splash
        c = card(root, "স্প্ল্যাশ স্ক্রিন");
        choice(c, "splashSrc", "স্প্ল্যাশ ছবির উৎস", SPLASH_SRC_L);
        ivSplash = imageRow(c, "কাস্টম স্প্ল্যাশ ছবি বাছুন", REQ_SPLASH, "splash_src.png");
        choice(c, "splashMode", "ছবি দেখানোর ধরন", SPLASH_MODE_L);
        field(c, "splashSize", "লোগো সাইজ (dp, শুধু 'লোগো' ধরনে)", "180", "180", InputType.TYPE_CLASS_NUMBER);
        field(c, "splashBg", "ব্যাকগ্রাউন্ড রং (hex)", "#FFFFFF", "#FFFFFF", InputType.TYPE_CLASS_TEXT);
        field(c, "splashSec", "কত সেকেন্ড দেখাবে", "1.8", "1.8", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        sw(c, "splashName", "অ্যাপের নাম দেখাও", true);
        sw(c, "splashSpinner", "লোডিং স্পিনার দেখাও", true);

        // ---- icon
        c = card(root, "অ্যাপ আইকন");
        choice(c, "iconSrc", "আইকনের উৎস", ICON_SRC_L);
        ivIcon = imageRow(c, "কাস্টম আইকন ছবি বাছুন", REQ_ICON, "icon_src.png");

        // ---- reader
        c = card(root, "রিডার");
        choice(c, "orient", "স্ক্রিন ঘোরানো", ORIENT_L);
        hint(c, "পোর্ট্রেটে একটি পেজ + ডান পাশে ভার্টিক্যাল স্ক্রল বার; ল্যান্ডস্কেপে পাশাপাশি দুই পেজ + নিচে হরিজন্টাল স্ক্রল বার।");
        field(c, "accent", "থিম রং (hex)", "#2F5BEA", "#2F5BEA", InputType.TYPE_CLASS_TEXT);
        field(c, "readerBg", "রিডারের ব্যাকগ্রাউন্ড (hex)", "#1F2023", "#1F2023", InputType.TYPE_CLASS_TEXT);
        field(c, "paper", "EPUB পাতার রং (hex)", "#FBF8F1", "#FBF8F1", InputType.TYPE_CLASS_TEXT);
        field(c, "ink", "EPUB লেখার রং (hex)", "#1B1B1B", "#1B1B1B", InputType.TYPE_CLASS_TEXT);
        field(c, "textPct", "EPUB লেখার সাইজ (%)", "100", "100", InputType.TYPE_CLASS_NUMBER);
        field(c, "flipMs", "পেজ ওল্টানোর সময় (মিলিসেকেন্ড)", "450", "450", InputType.TYPE_CLASS_NUMBER);
        sw(c, "scrollBar", "স্ক্রল বার দেখাও", true);
        sw(c, "volKeys", "ভলিউম বাটনে পেজ ওল্টানো", true);
        sw(c, "keepOn", "পড়ার সময় স্ক্রিন জাগিয়ে রাখো", true);
        sw(c, "fullscreen", "ফুল স্ক্রিন (স্ট্যাটাস বার লুকাও)", false);
        sw(c, "alwaysLib", "একটি বই হলেও লাইব্রেরি দেখাও", false);

        // ---- about
        c = card(root, "About (আমার তথ্য)");
        field(c, "aboutTitle", "শিরোনাম (ফাঁকা = অ্যাপের নাম)", "", "", InputType.TYPE_CLASS_TEXT);
        area(c, "aboutText", "বিবরণ", "এই বই সম্পর্কে, প্রকাশক, কপিরাইট…", "", 4);
        field(c, "aboutDev", "লেখক / ডেভেলপারের নাম", "", "", InputType.TYPE_CLASS_TEXT);
        area(c, "aboutLinks", "লিংক (প্রতি লাইনে একটি: নাম|লিংক)", "ওয়েবসাইট|https://example.com\nইমেইল|mailto:me@example.com", "", 3);

        // ---- build
        btnBuild = button("APK তৈরি করুন", 0xFF12B76A);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(MATCH, dp(54));
        bp.topMargin = dp(20);
        root.addView(btnBuild, bp);
        btnBuild.setOnClickListener(v -> onBuild());
        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(0xFF475467);
        tvStatus.setPadding(0, dp(10), 0, 0);
        root.addView(tvStatus);

        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        resultBox.setVisibility(View.GONE);
        resultBox.setBackground(rounded(0xFFECFDF3, 0xFFABEFC6, 14));
        resultBox.setPadding(dp(14), dp(12), dp(14), dp(14));
        tvResult = new TextView(this);
        tvResult.setTextSize(13);
        tvResult.setTextColor(0xFF054F31);
        resultBox.addView(tvResult);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button btnInstall = button("ইনস্টল করুন", BLUE);
        btnShare = button("শেয়ার", 0xFF475467);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        l1.topMargin = dp(10);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        l2.topMargin = dp(10);
        l2.leftMargin = dp(8);
        row.addView(btnInstall, l1);
        row.addView(btnShare, l2);
        resultBox.addView(row);
        LinearLayout.LayoutParams rb = new LinearLayout.LayoutParams(MATCH, WRAP);
        rb.topMargin = dp(12);
        root.addView(resultBox, rb);
        btnInstall.setOnClickListener(v -> onInstall());
        btnShare.setOnClickListener(v -> onShare());

        setContentView(sv);
    }

    private void switchesLater(LinearLayout c) {
        // placeholder hook kept for readability: library-related options live in the "রিডার" card
    }

    private ImageView imageRow(LinearLayout parent, String label, final int req, String savedName) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(MATCH, WRAP);
        rl.topMargin = dp(12);
        parent.addView(row, rl);

        ImageView iv = new ImageView(this);
        iv.setBackground(rounded(0xFFF2F4F7, 0xFFD0D5DD, 12));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        row.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
        File saved = new File(getFilesDir(), savedName);
        if (saved.exists()) iv.setImageBitmap(BitmapFactory.decodeFile(saved.getAbsolutePath()));

        Button b = button(label, 0xFF475467);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(46), 1f);
        bl.leftMargin = dp(12);
        row.addView(b, bl);
        b.setOnClickListener(v -> pickImage(req));
        return iv;
    }

    private void pickImage(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, req);
    }

    // ------------------------------------------------------------------ books list

    private void renderBooks() {
        booksBox.removeAllViews();
        for (int i = 0; i < books.size(); i++) booksBox.addView(bookCard(i));
        if (books.isEmpty()) hint(booksBox, "এখনো কোনো বই নেই। নিচের বাটনে 'নতুন বই' চাপুন।");
    }

    private View bookCard(final int index) {
        final BookSpec b = books.get(index);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(0xFFF9FAFB, 0xFFD0D5DD, 12));
        c.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);

        TextView head = new TextView(this);
        head.setText("বই " + (index + 1));
        head.setTextSize(13);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(BLUE);
        c.addView(head);

        EditText t = edit("বইয়ের নাম", b.title, InputType.TYPE_CLASS_TEXT);
        t.addTextChangedListener(watch(s -> b.title = s));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(MATCH, WRAP);
        tl.topMargin = dp(6);
        c.addView(t, tl);
        EditText a = edit("লেখকের নাম (ঐচ্ছিক)", b.author, InputType.TYPE_CLASS_TEXT);
        a.addTextChangedListener(watch(s -> b.author = s));
        c.addView(a, tl);

        for (int k = 0; k < b.sources.size(); k++) c.addView(sourceRow(b, k));

        TextView cov = new TextView(this);
        cov.setTextSize(12);
        cov.setTextColor(0xFF667085);
        cov.setText(b.coverFile != null ? "কভার: কাস্টম ছবি" : "কভার: প্রথম পেজ/কভার থেকে নিজে তৈরি হবে");
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(MATCH, WRAP);
        cl.topMargin = dp(8);
        c.addView(cov, cl);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button f = button("+ ফাইল", BLUE);
        Button u = button("+ URL", BLUE);
        Button cv = button("কভার", 0xFF475467);
        Button del = button("মুছুন", 0xFFD92D20);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(42), 1f);
        bl.setMargins(dp(2), dp(8), dp(2), 0);
        row.addView(f, bl);
        row.addView(u, bl);
        row.addView(cv, bl);
        row.addView(del, bl);
        c.addView(row);

        f.setOnClickListener(v -> {
            pendingBook = b;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "application/epub+zip", "application/octet-stream"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_FILES);
        });
        u.setOnClickListener(v -> askUrl(b));
        cv.setOnClickListener(v -> {
            if (b.coverFile != null) {
                new AlertDialog.Builder(this).setItems(new String[]{"নতুন কভার ছবি বাছুন", "কাস্টম কভার সরান"}, (d, w) -> {
                    if (w == 0) {
                        pendingBook = b;
                        pickImage(REQ_COVER);
                    } else {
                        b.coverFile = null;
                        saveBooks();
                        renderBooks();
                    }
                }).show();
            } else {
                pendingBook = b;
                pickImage(REQ_COVER);
            }
        });
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("\"" + b.title + "\" বইটি তালিকা থেকে মুছবেন?")
                .setPositiveButton("মুছুন", (d, w) -> {
                    books.remove(b);
                    saveBooks();
                    renderBooks();
                })
                .setNegativeButton("না", null).show());
        return c;
    }

    private View sourceRow(final BookSpec b, final int k) {
        final BookSpec.Src s = b.sources.get(k);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(MATCH, WRAP);
        rl.topMargin = dp(6);
        row.setLayoutParams(rl);

        TextView t = new TextView(this);
        String kind = s.type.toUpperCase();
        if (s.url) t.setText((k + 1) + ". 🔗 " + s.link);
        else {
            long kb = s.file != null ? s.file.length() / 1024 : 0;
            t.setText((k + 1) + ". " + kind + " — " + s.name + " (" + (kb > 1024 ? (kb / 1024) + " MB" : kb + " KB") + ")");
        }
        t.setTextSize(12);
        t.setTextColor(0xFF1D2939);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));

        row.addView(smallBtn("↑", () -> move(b, k, -1)));
        row.addView(smallBtn("↓", () -> move(b, k, 1)));
        row.addView(smallBtn("✕", () -> {
            b.sources.remove(s);
            saveBooks();
            renderBooks();
        }));
        return row;
    }

    private TextView smallBtn(String text, Runnable r) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(16);
        t.setTextColor(0xFF475467);
        t.setOnClickListener(v -> r.run());
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        return t;
    }

    private void move(BookSpec b, int k, int dir) {
        int to = k + dir;
        if (to < 0 || to >= b.sources.size()) return;
        BookSpec.Src s = b.sources.remove(k);
        b.sources.add(to, s);
        saveBooks();
        renderBooks();
    }

    private void askUrl(final BookSpec b) {
        final EditText e = edit("https://…/book.pdf", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle("PDF/EPUB লিংক")
                .setMessage("APK তৈরির সময় এই লিংক থেকে ফাইল নামিয়ে অ্যাপে ঢোকানো হবে। Google Drive / Dropbox শেয়ার লিংকও চলবে (ফাইল 'যে কেউ দেখতে পারে' হতে হবে)।")
                .setView(e)
                .setPositiveButton("যোগ করুন", (d, w) -> {
                    String u = e.getText().toString().trim();
                    if (u.isEmpty()) return;
                    if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
                    BookSpec.Src s = new BookSpec.Src();
                    s.url = true;
                    s.link = u;
                    s.name = u;
                    b.sources.add(s);
                    saveBooks();
                    renderBooks();
                })
                .setNegativeButton("বাতিল", null)
                .show();
    }

    private interface Str {
        void set(String s);
    }

    private TextWatcher watch(final Str st) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                st.set(s.toString());
            }
        };
    }

    private void saveBooks() {
        try {
            JSONArray a = new JSONArray();
            for (BookSpec b : books) a.put(b.toJson());
            sp.edit().putString("books", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void loadBooks() {
        try {
            JSONArray a = new JSONArray(sp.getString("books", "[]"));
            for (int i = 0; i < a.length(); i++) {
                BookSpec b = BookSpec.fromJson(a.getJSONObject(i));
                if (b.coverFile != null && !b.coverFile.exists()) b.coverFile = null;
                books.add(b);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ picker results

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_FILES) {
            final List<Uri> uris = new ArrayList<>();
            ClipData cd = data.getClipData();
            if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            final BookSpec target = pendingBook;
            if (target == null || uris.isEmpty()) return;
            status("ফাইল কপি হচ্ছে…");
            new Thread(() -> {
                int ok = 0;
                String err = null;
                for (Uri u : uris) {
                    try {
                        addLocalFile(target, u);
                        ok++;
                    } catch (Exception e) {
                        err = e.getMessage();
                    }
                }
                final int fok = ok;
                final String ferr = err;
                runOnUiThread(() -> {
                    status("");
                    saveBooks();
                    renderBooks();
                    if (ferr != null) toast(ferr);
                    else if (fok > 0) toast(fok + "টি ফাইল যোগ হয়েছে");
                });
            }).start();
            return;
        }
        if (data.getData() == null) return;
        try {
            Uri u = data.getData();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 1600) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                bmp = BitmapFactory.decodeStream(in, null, o);
            }
            if (bmp == null) throw new IllegalStateException("ছবি পড়া যায়নি");
            if (req == REQ_COVER && pendingBook != null) {
                File dest = new File(getFilesDir(), "cover_" + pendingBook.id + ".png");
                try (OutputStream out = new FileOutputStream(dest)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                pendingBook.coverFile = dest;
                saveBooks();
                renderBooks();
                return;
            }
            File dest = new File(getFilesDir(), req == REQ_ICON ? "icon_src.png" : "splash_src.png");
            try (OutputStream out = new FileOutputStream(dest)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            (req == REQ_ICON ? ivIcon : ivSplash).setImageBitmap(bmp);
        } catch (Exception e) {
            toast("ছবি লোড ব্যর্থ: " + e.getMessage());
        }
    }

    private void addLocalFile(BookSpec b, Uri u) throws Exception {
        String name = "book";
        try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ix >= 0) name = c.getString(ix);
            }
        }
        File dir = new File(getFilesDir(), "sources");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dest = new File(dir, java.util.UUID.randomUUID().toString().substring(0, 8) + ".bin");
        try (InputStream in = getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        String type = Downloader.sniff(dest);
        if (type == null) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            throw new Exception(name + " — PDF বা EPUB নয়");
        }
        BookSpec.Src s = new BookSpec.Src();
        s.file = dest;
        s.name = name;
        s.type = type;
        b.sources.add(s);
        if (b.sources.size() == 1 && (b.title.isEmpty() || b.title.matches("বই \\d+"))) {
            b.title = name.replaceAll("(?i)\\.(pdf|epub)$", "");
        }
    }

    // ------------------------------------------------------------------ prefs

    private void savePrefs() {
        SharedPreferences.Editor ed = sp.edit();
        for (Map.Entry<String, EditText> e : fields.entrySet()) ed.putString("f_" + e.getKey(), e.getValue().getText().toString());
        for (Map.Entry<String, Switch> e : switches.entrySet()) ed.putBoolean("s_" + e.getKey(), e.getValue().isChecked());
        for (Map.Entry<String, Spinner> e : spinners.entrySet()) ed.putInt("sel_" + e.getKey(), e.getValue().getSelectedItemPosition());
        ed.apply();
        saveBooks();
    }

    private String val(String key) {
        return fields.get(key).getText().toString().trim();
    }

    private boolean on(String key) {
        return switches.get(key).isChecked();
    }

    private static int clamp(String v, int min, int max, int def) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(v.trim())));
        } catch (Exception e) {
            return def;
        }
    }

    private static int parseColor(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Color.parseColor(s.startsWith("#") ? s : "#" + s);
        } catch (Exception e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ actions

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    private void status(String m) {
        tvStatus.setText(m);
    }

    private void onBuild() {
        String name = val("name");
        String pkg = val("pkg");
        if (name.isEmpty()) { toast("অ্যাপের নাম দিন"); return; }
        if (!PKG.matcher(pkg).matches()) { toast("প্যাকেজ নেম সঠিক নয় (যেমন: com.mycompany.mybook, ছোট হাতের অক্ষর)"); return; }
        if (pkg.equals(AxmlEditor.OLD_PACKAGE) || pkg.equals(getPackageName())) { toast("অন্য একটি প্যাকেজ নেম দিন"); return; }
        int code;
        try {
            code = Integer.parseInt(val("verCode"));
            if (code < 1) throw new NumberFormatException();
        } catch (NumberFormatException e) { toast("ভার্সন কোড ১ বা তার বেশি হতে হবে"); return; }
        double sec;
        try { sec = Double.parseDouble(val("splashSec")); } catch (Exception e) { sec = 1.8; }

        final BuildSpec s = new BuildSpec();
        int n = 0;
        for (BookSpec b : books) {
            if (b.sources.isEmpty()) continue;
            n++;
            if (b.title.trim().isEmpty()) b.title = "বই " + n;
            s.books.add(b);
        }
        if (s.books.isEmpty()) { toast("অন্তত একটি বইয়ে একটি PDF/EPUB/URL যোগ করুন"); return; }

        s.appName = name;
        s.pkg = pkg;
        s.versionName = val("verName").isEmpty() ? "1.0" : val("verName");
        s.versionCode = code;
        File ic = new File(getFilesDir(), "icon_src.png");
        File sp2 = new File(getFilesDir(), "splash_src.png");
        s.iconFile = ic.exists() ? ic : null;
        s.splashFile = sp2.exists() ? sp2 : null;
        s.iconSource = pick("iconSrc", ICON_SRC_V);
        s.splashSource = pick("splashSrc", SPLASH_SRC_V);
        if ("custom".equals(s.splashSource) && s.splashFile == null) { toast("কাস্টম স্প্ল্যাশ ছবি বাছুন"); return; }
        if ("custom".equals(s.iconSource) && s.iconFile == null) { toast("কাস্টম আইকন ছবি বাছুন"); return; }
        s.splashMode = pick("splashMode", SPLASH_MODE_V);
        s.splashSize = clamp(val("splashSize"), 40, 600, 180);
        s.splashBg = parseColor(val("splashBg"), Color.WHITE);
        s.splashMs = (int) Math.max(0, Math.min(10, sec) * 1000);
        s.splashName = on("splashName");
        s.splashSpinner = on("splashSpinner");

        s.accent = parseColor(val("accent"), BLUE);
        s.readerBg = parseColor(val("readerBg"), 0xFF1F2023);
        s.paper = parseColor(val("paper"), 0xFFFBF8F1);
        s.ink = parseColor(val("ink"), 0xFF1B1B1B);
        s.orientation = pick("orient", ORIENT_V);
        s.textPercent = clamp(val("textPct"), 60, 250, 100);
        s.flipMs = clamp(val("flipMs"), 150, 1500, 450);
        s.scrollBar = on("scrollBar");
        s.volumeKeys = on("volKeys");
        s.keepScreenOn = on("keepOn");
        s.fullscreen = on("fullscreen");
        s.alwaysLibrary = on("alwaysLib");

        s.aboutTitle = val("aboutTitle");
        s.aboutText = fields.get("aboutText").getText().toString().trim();
        s.aboutDeveloper = val("aboutDev");
        for (String line : fields.get("aboutLinks").getText().toString().split("\\r?\\n")) {
            int bar = line.indexOf('|');
            if (bar <= 0) continue;
            String t = line.substring(0, bar).trim(), u = line.substring(bar + 1).trim();
            if (!t.isEmpty() && !u.isEmpty()) s.aboutLinks.add(new String[]{t, u});
        }

        savePrefs();
        btnBuild.setEnabled(false);
        resultBox.setVisibility(View.GONE);
        status("শুরু হচ্ছে...");

        new Thread(() -> {
            try {
                File apk = ApkGenerator.generate(this, s, m -> runOnUiThread(() -> status(m)));
                runOnUiThread(() -> status("ডাউনলোড ফোল্ডারে সেভ হচ্ছে..."));
                String where;
                Uri saved = null;
                try {
                    saved = saveToDownloads(apk);
                    where = Build.VERSION.SDK_INT >= 29 ? "Download/BookShellBuilder/" + apk.getName() : saved.getPath();
                } catch (Exception e) {
                    where = apk.getAbsolutePath();
                }
                final Uri fs = saved;
                final String fw = where;
                runOnUiThread(() -> {
                    lastApk = apk;
                    lastSaved = fs;
                    btnBuild.setEnabled(true);
                    status("");
                    tvResult.setText("✔ APK তৈরি ও সাইন হয়েছে\n" + fw + "\nসাইজ: " + String.format("%.1f", apk.length() / 1048576.0) + " MB");
                    btnShare.setVisibility(fs != null && Build.VERSION.SDK_INT >= 29 ? View.VISIBLE : View.GONE);
                    resultBox.setVisibility(View.VISIBLE);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    btnBuild.setEnabled(true);
                    status("ব্যর্থ: " + t.getMessage());
                });
            }
        }).start();
    }

    private Uri saveToDownloads(File apk) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, apk.getName());
            v.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BookShellBuilder");
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IllegalStateException("MediaStore insert failed");
            try (InputStream in = new FileInputStream(apk); OutputStream out = getContentResolver().openOutputStream(u)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return u;
        }
        File f = new File(getExternalFilesDir(null), apk.getName());
        try (InputStream in = new FileInputStream(apk); OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return Uri.fromFile(f);
    }

    private void onInstall() {
        if (lastApk == null || !lastApk.exists()) { toast("আগে APK তৈরি করুন"); return; }
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast("এই অ্যাপকে 'অজানা অ্যাপ ইনস্টল' অনুমতি দিন, তারপর আবার চাপুন");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        try {
            Installer.install(this, lastApk);
        } catch (Exception e) {
            toast("ইনস্টল শুরু করা যায়নি: " + e.getMessage());
        }
    }

    private void onShare() {
        if (lastSaved == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/vnd.android.package-archive");
        i.putExtra(Intent.EXTRA_STREAM, lastSaved);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "APK শেয়ার করুন"));
    }
}
