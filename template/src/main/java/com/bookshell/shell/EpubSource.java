package com.bookshell.shell;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.text.Html;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.LruCache;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads an EPUB and cuts its text into fixed-size book pages. Layout is done with StaticLayout on a
 * virtual page of PW x PH units, so the page count does not depend on the device or on the slot
 * the page is drawn into.
 */
final class EpubSource implements PageSource {
    static final int PW = 600, PH = 860, MX = 46, MY = 54;
    private static final int CW = PW - 2 * MX;
    private static final int CH = PH - 2 * MY;
    private static final float BASE_TEXT = 22f;

    private static final class Chapter {
        Spanned text;
        StaticLayout layout;
    }

    private static final class Page {
        int chapter, startLine, endLine;
    }

    private static final class State {
        final List<Chapter> chapters = new ArrayList<>();
        final List<Page> pages = new ArrayList<>();
    }

    private final ZipFile zip;
    private final Map<String, String> names = new HashMap<>();
    private final List<String> spine = new ArrayList<>();
    private final List<String> spineDirs = new ArrayList<>();
    private final int paper;
    private final int ink;
    private volatile State state;
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Paint imgPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    String title = "";

    EpubSource(File f, float textScale, int paper, int ink) throws Exception {
        this.paper = paper;
        this.ink = ink;
        zip = new ZipFile(f);
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            names.put(e.getName().toLowerCase(), e.getName());
        }
        parseSpine();
        if (spine.isEmpty()) throw new IOException("EPUB-এ কোনো অধ্যায় পাওয়া যায়নি");
        state = paginate(textScale);
        if (state.pages.isEmpty()) throw new IOException("EPUB-এ পড়ার মতো লেখা নেই");
    }

    // ------------------------------------------------------------------ container / opf

    private byte[] read(String path) throws IOException {
        String real = names.get(path.toLowerCase());
        if (real == null) return null;
        ZipEntry e = zip.getEntry(real);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static String local(String n) {
        int i = n.indexOf(':');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    private static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i + 1);
    }

    /** Resolves href against a directory inside the zip ("a/b/", "../c.png" -> "a/c.png"). */
    static String resolve(String dir, String href) {
        int hash = href.indexOf('#');
        if (hash >= 0) href = href.substring(0, hash);
        href = Uri.decode(href);
        String full = href.startsWith("/") ? href.substring(1) : dir + href;
        String[] parts = full.split("/");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else {
                out.add(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(out.get(i));
        }
        return sb.toString();
    }

    private void parseSpine() throws Exception {
        String opf = null;
        byte[] cont = read("META-INF/container.xml");
        if (cont != null) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new ByteArrayInputStream(cont), null);
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG && local(p.getName()).equals("rootfile")) {
                    opf = p.getAttributeValue(null, "full-path");
                    break;
                }
            }
        }
        if (opf == null) {
            for (String n : names.values()) {
                if (n.toLowerCase().endsWith(".opf")) {
                    opf = n;
                    break;
                }
            }
        }
        if (opf == null) throw new IOException("EPUB-এর OPF ফাইল নেই");
        byte[] data = read(opf);
        if (data == null) throw new IOException("OPF পড়া যায়নি");
        String base = dirOf(opf);

        Map<String, String> href = new HashMap<>();
        Map<String, String> type = new HashMap<>();
        List<String> ids = new ArrayList<>();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new ByteArrayInputStream(data), null);
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev != XmlPullParser.START_TAG) continue;
            String n = local(p.getName());
            if (n.equals("item")) {
                String id = p.getAttributeValue(null, "id");
                if (id != null) {
                    href.put(id, p.getAttributeValue(null, "href"));
                    type.put(id, p.getAttributeValue(null, "media-type"));
                }
            } else if (n.equals("itemref")) {
                String id = p.getAttributeValue(null, "idref");
                if (id != null) ids.add(id);
            } else if (n.equals("title") && title.isEmpty()) {
                try {
                    title = p.nextText();
                } catch (Exception ignored) {
                }
            }
        }
        for (String id : ids) {
            String h = href.get(id);
            String t = type.get(id);
            if (h == null) continue;
            if (t != null && !(t.contains("html") || t.contains("xml"))) continue;
            String path = resolve(base, h);
            spine.add(path);
            spineDirs.add(dirOf(path));
        }
    }

    // ------------------------------------------------------------------ html -> spanned

    private static final Pattern RE_BODY = Pattern.compile("(?is)<body[^>]*>(.*)</body>");
    private static final Pattern RE_JUNK = Pattern.compile(
            "(?is)<\\?xml.*?\\?>|<!DOCTYPE[^>]*>|<!--.*?-->|<head[^>]*>.*?</head>|<style[^>]*>.*?</style>|<script[^>]*>.*?</script>");
    private static final Pattern RE_SVG_IMG = Pattern.compile(
            "(?is)<image\\b[^>]*?(?:xlink:href|href)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>");

    static String prepare(String html) {
        html = RE_JUNK.matcher(html).replaceAll("");
        Matcher m = RE_BODY.matcher(html);
        if (m.find()) html = m.group(1);
        html = RE_SVG_IMG.matcher(html).replaceAll("<img src=\"$1\"/>");
        return html;
    }

    private static boolean blank(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\uFFFC') return false;
            if (!Character.isWhitespace(c) && c != '\u00A0') return false;
        }
        return true;
    }

    private State paginate(float scale) throws Exception {
        State st = new State();
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(ink);
        tp.linkColor = ink;
        tp.setTextSize(BASE_TEXT * scale);
        tp.setTypeface(Typeface.SERIF);

        for (int i = 0; i < spine.size(); i++) {
            byte[] raw = read(spine.get(i));
            if (raw == null) continue;
            String html = prepare(decode(raw));
            final String dir = spineDirs.get(i);
            Spanned sp = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY, src -> imageFor(dir, src), null);
            if (blank(sp)) continue;

            StaticLayout.Builder b = StaticLayout.Builder.obtain(sp, 0, sp.length(), tp, CW)
                    .setLineSpacing(0f, 1.28f)
                    .setIncludePad(false);
            if (Build.VERSION.SDK_INT >= 26) b.setJustificationMode(android.text.Layout.JUSTIFICATION_MODE_INTER_WORD);
            StaticLayout layout = b.build();

            Chapter ch = new Chapter();
            ch.text = sp;
            ch.layout = layout;
            int ci = st.chapters.size();
            st.chapters.add(ch);

            int lc = layout.getLineCount();
            int line = 0;
            while (line < lc) {
                int top = layout.getLineTop(line);
                int end = line;
                while (end + 1 < lc && layout.getLineBottom(end + 1) - top <= CH) end++;
                CharSequence seg = sp.subSequence(layout.getLineStart(line), layout.getLineEnd(end));
                if (!blank(seg)) {
                    Page pg = new Page();
                    pg.chapter = ci;
                    pg.startLine = line;
                    pg.endLine = end;
                    st.pages.add(pg);
                }
                line = end + 1;
            }
        }
        return st;
    }

    private static String decode(byte[] raw) {
        try {
            if (raw.length > 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) return new String(raw, "UTF-16LE");
            if (raw.length > 2 && (raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF) return new String(raw, "UTF-16BE");
            return new String(raw, "UTF-8");
        } catch (Exception e) {
            return new String(raw);
        }
    }

    // ------------------------------------------------------------------ images

    private Drawable imageFor(String dir, String src) {
        try {
            String path = resolve(dir, src);
            byte[] data = read(path);
            if (data == null) return empty();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return empty();
            float maxW = CW, maxH = CH * 0.92f;
            float sc = Math.min(maxW / o.outWidth, maxH / o.outHeight);
            if (sc > 1f) sc = o.outWidth >= 300 ? Math.min(sc, 2.5f) : 1f;
            int w = Math.max(1, Math.round(o.outWidth * sc));
            int h = Math.max(1, Math.round(o.outHeight * sc));
            return new LazyImage(path, w, h);
        } catch (Exception e) {
            return empty();
        }
    }

    private static Drawable empty() {
        Drawable d = new ColorDrawable(Color.TRANSPARENT);
        d.setBounds(0, 0, 1, 1);
        return d;
    }

    private Bitmap bitmap(String path, int w, int h) {
        String key = path + "@" + w;
        Bitmap b = images.get(key);
        if (b != null) return b;
        try {
            byte[] data = read(path);
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w * 2) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b != null) images.put(key, b);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private final class LazyImage extends Drawable {
        private final String path;
        private final int w, h;

        LazyImage(String path, int w, int h) {
            this.path = path;
            this.w = w;
            this.h = h;
            setBounds(0, 0, w, h);
        }

        @Override
        public void draw(Canvas c) {
            Bitmap b = bitmap(path, w, h);
            if (b != null) c.drawBitmap(b, null, getBounds(), imgPaint);
        }

        @Override
        public int getIntrinsicWidth() {
            return w;
        }

        @Override
        public int getIntrinsicHeight() {
            return h;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------ PageSource

    @Override
    public int pageCount() {
        return state.pages.size();
    }

    @Override
    public float aspect() {
        return PW / (float) PH;
    }

    @Override
    public boolean isReflowable() {
        return true;
    }

    @Override
    public void render(int page, Bitmap bmp) {
        State st = state;
        Canvas c = new Canvas(bmp);
        c.drawColor(paper);
        if (page < 0 || page >= st.pages.size()) return;
        Page pg = st.pages.get(page);
        Chapter ch = st.chapters.get(pg.chapter);
        float s = Math.min(bmp.getWidth() / (float) PW, bmp.getHeight() / (float) PH);
        c.translate((bmp.getWidth() - PW * s) / 2f, (bmp.getHeight() - PH * s) / 2f);
        c.scale(s, s);
        int top = ch.layout.getLineTop(pg.startLine);
        int bottom = ch.layout.getLineBottom(pg.endLine);
        c.save();
        c.translate(MX, MY - top);
        c.clipRect(0, top, CW, bottom);
        ch.layout.draw(c);
        c.restore();
    }

    @Override
    public void close() {
        try {
            zip.close();
        } catch (Exception ignored) {
        }
    }
}
