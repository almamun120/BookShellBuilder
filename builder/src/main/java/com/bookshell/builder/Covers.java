package com.bookshell.builder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Makes cover pictures: the first PDF page, the EPUB's cover image, or a generated title card. */
final class Covers {
    private Covers() {}

    static Bitmap pdfFirstPage(File pdf, int width) {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)) {
            PdfRenderer r = new PdfRenderer(fd);
            try {
                if (r.getPageCount() == 0) return null;
                PdfRenderer.Page p = r.openPage(0);
                try {
                    float sc = width / (float) p.getWidth();
                    int h = Math.max(1, Math.round(p.getHeight() * sc));
                    Bitmap b = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888);
                    b.eraseColor(Color.WHITE);
                    Matrix m = new Matrix();
                    m.setScale(sc, sc);
                    p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    return b;
                } finally {
                    p.close();
                }
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String local(String n) {
        int i = n.indexOf(':');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    private static byte[] read(ZipFile z, String name) throws Exception {
        ZipEntry e = z.getEntry(name);
        if (e == null) {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry x = en.nextElement();
                if (x.getName().equalsIgnoreCase(name)) {
                    e = x;
                    break;
                }
            }
        }
        if (e == null) return null;
        try (InputStream in = z.getInputStream(e)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static String resolve(String dir, String href) {
        href = Uri.decode(href);
        int h = href.indexOf('#');
        if (h >= 0) href = href.substring(0, h);
        String full = dir + href;
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String p : full.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else out.add(p);
        }
        return android.text.TextUtils.join("/", out);
    }

    /** EPUB title and cover image (null if there is none). */
    static Bitmap epubCover(File epub, String[] titleOut) {
        try (ZipFile z = new ZipFile(epub)) {
            byte[] cont = read(z, "META-INF/container.xml");
            String opf = null;
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
            if (opf == null) return null;
            byte[] data = read(z, opf);
            if (data == null) return null;
            String dir = opf.contains("/") ? opf.substring(0, opf.lastIndexOf('/') + 1) : "";
            Map<String, String> href = new HashMap<>();
            Map<String, String> type = new HashMap<>();
            Map<String, String> props = new HashMap<>();
            String coverId = null;
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new ByteArrayInputStream(data), null);
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev != XmlPullParser.START_TAG) continue;
                String n = local(p.getName());
                if (n.equals("item")) {
                    String id = p.getAttributeValue(null, "id");
                    if (id == null) continue;
                    href.put(id, p.getAttributeValue(null, "href"));
                    type.put(id, p.getAttributeValue(null, "media-type"));
                    String pr = p.getAttributeValue(null, "properties");
                    if (pr != null) props.put(id, pr);
                } else if (n.equals("meta") && "cover".equals(p.getAttributeValue(null, "name"))) {
                    coverId = p.getAttributeValue(null, "content");
                } else if (n.equals("title") && titleOut != null && titleOut[0] == null) {
                    try {
                        titleOut[0] = p.nextText();
                    } catch (Exception ignored) {
                    }
                }
            }
            String path = null;
            for (Map.Entry<String, String> e : props.entrySet()) {
                if (e.getValue().contains("cover-image")) path = href.get(e.getKey());
            }
            if (path == null && coverId != null && href.get(coverId) != null) path = href.get(coverId);
            if (path == null) {
                for (Map.Entry<String, String> e : href.entrySet()) {
                    String t = type.get(e.getKey());
                    if (t != null && t.startsWith("image/") && (e.getKey() + e.getValue()).toLowerCase().contains("cover")) {
                        path = e.getValue();
                        break;
                    }
                }
            }
            if (path == null) return null;
            byte[] img = read(z, resolve(dir, path));
            if (img == null) return null;
            return BitmapFactory.decodeByteArray(img, 0, img.length);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A plain title card, used when the book has no usable first page or cover. */
    static Bitmap generated(String title, String author, int accent, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float[] hsv = new float[3];
        Color.colorToHSV(accent, hsv);
        int dark = Color.HSVToColor(new float[]{hsv[0], Math.min(1f, hsv[1] + 0.1f), hsv[2] * 0.55f});
        p.setShader(new LinearGradient(0, 0, w, h, accent, dark, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(0x33FFFFFF);
        c.drawRect(w * 0.08f, h * 0.06f, w * 0.085f, h * 0.94f, p);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextSize(w * 0.09f);
        int tw = (int) (w * 0.74f);
        StaticLayout sl = StaticLayout.Builder.obtain(title, 0, title.length(), tp, tw)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(6)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).build();
        c.save();
        c.translate(w * 0.13f, Math.max(h * 0.12f, (h - sl.getHeight()) / 2f - h * 0.05f));
        sl.draw(c);
        c.restore();
        if (author != null && !author.isEmpty()) {
            tp.setTypeface(Typeface.DEFAULT);
            tp.setTextSize(w * 0.055f);
            tp.setAlpha(210);
            StaticLayout al = StaticLayout.Builder.obtain(author, 0, author.length(), tp, tw)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).build();
            c.save();
            c.translate(w * 0.13f, h * 0.84f - al.getHeight());
            al.draw(c);
            c.restore();
        }
        return b;
    }

    static Bitmap scaleDown(Bitmap b, int maxSide) {
        int m = Math.max(b.getWidth(), b.getHeight());
        if (m <= maxSide) return b;
        float f = maxSide / (float) m;
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * f), Math.round(b.getHeight() * f), true);
    }

    static byte[] jpeg(Bitmap b, int q) {
        Bitmap flat = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(flat);
        c.drawColor(Color.WHITE);
        c.drawBitmap(b, 0, 0, null);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        flat.compress(Bitmap.CompressFormat.JPEG, q, bo);
        return bo.toByteArray();
    }
}
