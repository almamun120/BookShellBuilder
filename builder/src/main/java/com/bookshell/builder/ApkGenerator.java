package com.bookshell.builder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.android.apksig.ApkSigner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Turns a BuildSpec into a signed, installable APK that contains the books. */
final class ApkGenerator {
    interface Progress {
        void log(String msg);
    }

    private ApkGenerator() {}

    /** A source after it has been resolved to a real file on disk. */
    private static final class Resolved {
        File file;
        String type;
        String entry; // assets/books/b0_s0.pdf
    }

    static File generate(Context ctx, BuildSpec s, Progress p) throws Exception {
        File work = new File(ctx.getCacheDir(), "work");
        deleteRec(work);
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();

        // ---- 1. books: download links, detect types
        List<List<Resolved>> all = new ArrayList<>();
        for (int bi = 0; bi < s.books.size(); bi++) {
            BookSpec b = s.books.get(bi);
            List<Resolved> list = new ArrayList<>();
            for (int si = 0; si < b.sources.size(); si++) {
                BookSpec.Src src = b.sources.get(si);
                Resolved r = new Resolved();
                if (src.url) {
                    p.log("ডাউনলোড: " + src.link);
                    File dl = new File(work, "dl_" + bi + "_" + si);
                    r.type = Downloader.fetch(src.link, dl, p::log);
                    r.file = dl;
                } else {
                    r.file = src.file;
                    String t = Downloader.sniff(src.file);
                    r.type = t != null ? t : src.type;
                }
                r.entry = "assets/books/b" + bi + "_s" + si + "." + r.type;
                list.add(r);
            }
            all.add(list);
        }

        // ---- 2. covers
        p.log("কভার তৈরি হচ্ছে...");
        Map<String, byte[]> extra = new LinkedHashMap<>();
        Map<String, File> big = new LinkedHashMap<>();
        Bitmap firstRaw = null;     // big first page of the first book (for splash / icon)
        List<Boolean> hasCover = new ArrayList<>();
        for (int bi = 0; bi < s.books.size(); bi++) {
            BookSpec b = s.books.get(bi);
            Resolved first = all.get(bi).get(0);
            Bitmap raw = null;
            if (b.coverFile != null && b.coverFile.exists()) raw = BitmapFactory.decodeFile(b.coverFile.getAbsolutePath());
            if (raw == null) {
                raw = "pdf".equals(first.type) ? Covers.pdfFirstPage(first.file, 1200) : Covers.epubCover(first.file, null);
            }
            Bitmap cover = raw != null ? raw : Covers.generated(b.title, b.author, s.accent, 600, 840);
            if (bi == 0) firstRaw = raw != null ? raw : cover;
            extra.put("assets/covers/" + bi + ".jpg", Covers.jpeg(Covers.scaleDown(cover, 640), 88));
            hasCover.add(true);
            for (Resolved r : all.get(bi)) big.put(r.entry, r.file);
        }

        // ---- 3. splash and icon
        boolean hasSplash = false;
        if ("custom".equals(s.splashSource) && s.splashFile != null && s.splashFile.exists()) {
            Bitmap sb = BitmapFactory.decodeFile(s.splashFile.getAbsolutePath());
            if (sb != null) {
                extra.put("assets/splash.img", png(Covers.scaleDown(sb, s.splashMode.startsWith("full") ? 1600 : 720)));
                hasSplash = true;
            }
        } else if ("page".equals(s.splashSource) && firstRaw != null) {
            extra.put("assets/splash.img", Covers.jpeg(Covers.scaleDown(firstRaw, 1600), 90));
            hasSplash = true;
        }

        Bitmap iconSrc = null;
        if ("custom".equals(s.iconSource) && s.iconFile != null && s.iconFile.exists()) {
            iconSrc = BitmapFactory.decodeFile(s.iconFile.getAbsolutePath());
        } else if ("cover".equals(s.iconSource)) {
            iconSrc = firstRaw;
        }
        final Bitmap iconBmp = iconSrc;
        final Map<Integer, byte[]> iconCache = new HashMap<>();

        extra.put("assets/config.json", config(s, hasSplash, all, hasCover).getBytes(StandardCharsets.UTF_8));

        // ---- 4. template + manifest
        p.log("টেমপ্লেট লোড হচ্ছে...");
        File tpl = new File(work, "template.apk");
        try (InputStream in = ctx.getAssets().open("template.apk"); OutputStream out = new FileOutputStream(tpl)) {
            copy(in, out);
        }
        byte[] manifest;
        try (ZipFile zf = new ZipFile(tpl)) {
            ZipEntry me = zf.getEntry("AndroidManifest.xml");
            if (me == null) throw new IllegalStateException("টেমপ্লেটে AndroidManifest.xml নেই");
            manifest = ApkPacker.readAll(zf.getInputStream(me));
        }
        Set<String> allowed = new HashSet<>();
        allowed.add("android.permission.INTERNET");
        manifest = AxmlEditor.patch(manifest, s.pkg, s.appName, s.versionName, s.versionCode, allowed);

        p.log("APK প্যাক হচ্ছে (বই ঢোকানো হচ্ছে)...");
        File unsigned = new File(work, "unsigned.apk");
        ApkPacker.pack(tpl, unsigned, manifest, extra, big, size -> {
            byte[] cached = iconCache.get(size);
            if (cached == null) {
                cached = png(renderIcon(iconBmp, s.appName, s.accent, size));
                iconCache.put(size, cached);
            }
            return cached;
        });

        p.log("সাইন করা হচ্ছে...");
        SigningKey sk = SigningKey.loadOrCreate(ctx, s.pkg, s.appName);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder(
                "bookshell", sk.key, Collections.singletonList(sk.cert)).build();
        File signed = new File(work, fileName(s));
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(unsigned)
                .setOutputApk(signed)
                .setMinSdkVersion(24)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
        //noinspection ResultOfMethodCallIgnored
        unsigned.delete();
        //noinspection ResultOfMethodCallIgnored
        tpl.delete();
        for (List<Resolved> l : all) for (Resolved r : l) if (r.file.getParentFile().equals(work)) r.file.delete();
        return signed;
    }

    static String fileName(BuildSpec s) {
        String base = s.appName.replaceAll("[^A-Za-z0-9_\\-]", "");
        if (base.isEmpty()) base = s.pkg.replace('.', '_');
        return base + "_" + s.versionName + ".apk";
    }

    private static String color(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    private static String config(BuildSpec s, boolean hasSplash, List<List<Resolved>> all, List<Boolean> covers) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", s.appName);
        o.put("accent", color(s.accent));
        o.put("splashBg", color(s.splashBg));
        o.put("readerBg", color(s.readerBg));
        o.put("paper", color(s.paper));
        o.put("ink", color(s.ink));
        o.put("splashImage", hasSplash);
        o.put("splashMode", s.splashMode);
        o.put("splashSize", s.splashSize);
        o.put("splashName", s.splashName);
        o.put("splashSpinner", s.splashSpinner);
        o.put("splashMs", s.splashMs);
        o.put("orientation", s.orientation);
        o.put("fullscreen", s.fullscreen);
        o.put("keepScreenOn", s.keepScreenOn);
        o.put("scrollBar", s.scrollBar);
        o.put("alwaysLibrary", s.alwaysLibrary);
        o.put("volumeKeys", s.volumeKeys);
        o.put("textScale", s.textPercent / 100.0);
        o.put("flipMs", s.flipMs);
        o.put("aboutTitle", s.aboutTitle);
        o.put("aboutText", s.aboutText);
        o.put("aboutDeveloper", s.aboutDeveloper);
        JSONArray links = new JSONArray();
        for (String[] l : s.aboutLinks) links.put(new JSONObject().put("title", l[0]).put("url", l[1]));
        o.put("aboutLinks", links);

        JSONArray books = new JSONArray();
        for (int i = 0; i < s.books.size(); i++) {
            BookSpec b = s.books.get(i);
            JSONObject bo = new JSONObject();
            bo.put("id", b.id);
            bo.put("title", b.title);
            bo.put("author", b.author);
            bo.put("cover", covers.get(i));
            JSONArray ss = new JSONArray();
            for (Resolved r : all.get(i)) {
                ss.put(new JSONObject()
                        .put("file", r.entry.substring("assets/".length()))
                        .put("type", r.type));
            }
            bo.put("sources", ss);
            books.put(bo);
        }
        o.put("books", books);
        return o.toString();
    }

    // ---------------------------------------------------------------- images

    private static byte[] png(Bitmap b) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return bo.toByteArray();
    }

    /** Square icon: the picked image (center-cropped) or a generated letter icon. */
    private static Bitmap renderIcon(Bitmap src, String name, int accent, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (src != null) {
            int side = Math.min(src.getWidth(), src.getHeight());
            Rect from = new Rect((src.getWidth() - side) / 2, 0, (src.getWidth() + side) / 2, side); // top square = the title area
            c.drawBitmap(src, from, new Rect(0, 0, size, size), p);
        } else {
            p.setColor(accent);
            c.drawRoundRect(new RectF(0, 0, size, size), size * 0.22f, size * 0.22f, p);
            p.setColor(Color.WHITE);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(size * 0.55f);
            String letter = name.isEmpty() ? "B" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase();
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(letter, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p);
        }
        return out;
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void deleteRec(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
