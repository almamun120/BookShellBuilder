package com.bookshell.shell;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Settings written by the builder into assets/config.json. */
final class AppConfig {
    static final class Source {
        String file;   // asset path, e.g. books/b0_s0.pdf
        String type;   // pdf | epub
    }

    static final class Book {
        String id;
        String title;
        String author;
        boolean cover;
        final List<Source> sources = new ArrayList<>();

        boolean hasEpub() {
            for (Source s : sources) if ("epub".equals(s.type)) return true;
            return false;
        }
    }

    static final class Link {
        String title;
        String url;
    }

    String name = "বই";
    int accent = 0xFF3B82F6;
    int splashBg = 0xFFFFFFFF;
    int readerBg = 0xFF1F2023;
    int paper = 0xFFFBF8F1;
    int ink = 0xFF1B1B1B;

    boolean splashImage;
    String splashMode = "fullFit";   // logo | fullFit | fullCrop | fullStretch
    int splashSize = 180;
    boolean splashName = true;
    boolean splashSpinner = true;
    int splashMs = 1800;

    String orientation = "auto";
    boolean fullscreen;
    boolean keepScreenOn = true;
    boolean scrollBar = true;
    boolean alwaysLibrary;
    boolean volumeKeys = true;
    float textScale = 1f;
    int flipMs = 450;

    String aboutTitle = "";
    String aboutText = "";
    String aboutDeveloper = "";
    final List<Link> aboutLinks = new ArrayList<>();

    final List<Book> books = new ArrayList<>();

    static AppConfig load(Context c) {
        AppConfig a = new AppConfig();
        try {
            JSONObject o = new JSONObject(readAsset(c, "config.json"));
            a.name = o.optString("name", a.name);
            a.accent = color(o.optString("accent"), a.accent);
            a.splashBg = color(o.optString("splashBg"), a.splashBg);
            a.readerBg = color(o.optString("readerBg"), a.readerBg);
            a.paper = color(o.optString("paper"), a.paper);
            a.ink = color(o.optString("ink"), a.ink);
            a.splashImage = o.optBoolean("splashImage");
            a.splashMode = o.optString("splashMode", a.splashMode);
            a.splashSize = o.optInt("splashSize", a.splashSize);
            a.splashName = o.optBoolean("splashName", true);
            a.splashSpinner = o.optBoolean("splashSpinner", true);
            a.splashMs = o.optInt("splashMs", a.splashMs);
            a.orientation = o.optString("orientation", "auto");
            a.fullscreen = o.optBoolean("fullscreen");
            a.keepScreenOn = o.optBoolean("keepScreenOn", true);
            a.scrollBar = o.optBoolean("scrollBar", true);
            a.alwaysLibrary = o.optBoolean("alwaysLibrary");
            a.volumeKeys = o.optBoolean("volumeKeys", true);
            a.textScale = (float) o.optDouble("textScale", 1.0);
            a.flipMs = o.optInt("flipMs", 450);
            a.aboutTitle = o.optString("aboutTitle");
            a.aboutText = o.optString("aboutText");
            a.aboutDeveloper = o.optString("aboutDeveloper");
            JSONArray links = o.optJSONArray("aboutLinks");
            if (links != null) {
                for (int i = 0; i < links.length(); i++) {
                    JSONObject l = links.getJSONObject(i);
                    Link k = new Link();
                    k.title = l.optString("title");
                    k.url = l.optString("url");
                    a.aboutLinks.add(k);
                }
            }
            JSONArray books = o.optJSONArray("books");
            if (books != null) {
                for (int i = 0; i < books.length(); i++) {
                    JSONObject bo = books.getJSONObject(i);
                    Book b = new Book();
                    b.id = bo.optString("id", "b" + i);
                    b.title = bo.optString("title", "বই " + (i + 1));
                    b.author = bo.optString("author");
                    b.cover = bo.optBoolean("cover");
                    JSONArray ss = bo.optJSONArray("sources");
                    if (ss != null) {
                        for (int k = 0; k < ss.length(); k++) {
                            JSONObject so = ss.getJSONObject(k);
                            Source s = new Source();
                            s.file = so.getString("file");
                            s.type = so.optString("type", "pdf");
                            b.sources.add(s);
                        }
                    }
                    if (!b.sources.isEmpty()) a.books.add(b);
                }
            }
        } catch (Exception ignored) {
            // an unreadable config simply gives an empty library; MainActivity shows a message
        }
        return a;
    }

    private static int color(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return def;
        }
    }

    static String readAsset(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
