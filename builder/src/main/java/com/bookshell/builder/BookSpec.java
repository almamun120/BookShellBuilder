package com.bookshell.builder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One book in the library: a title plus one or more PDF / EPUB parts that are read as a single book. */
final class BookSpec {
    /** A part of a book: a file picked on the phone, or a link that is downloaded while building. */
    static final class Src {
        boolean url;
        String link;   // when url
        File file;     // when local
        String name;   // display name
        String type = "pdf"; // pdf | epub (links are re-detected after download)

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("url", url);
            o.put("link", link == null ? "" : link);
            o.put("file", file == null ? "" : file.getAbsolutePath());
            o.put("name", name == null ? "" : name);
            o.put("type", type);
            return o;
        }

        static Src fromJson(JSONObject o) {
            Src s = new Src();
            s.url = o.optBoolean("url");
            s.link = o.optString("link");
            String f = o.optString("file");
            s.file = f.isEmpty() ? null : new File(f);
            s.name = o.optString("name");
            s.type = o.optString("type", "pdf");
            return s;
        }
    }

    String id = UUID.randomUUID().toString().substring(0, 8);
    String title = "";
    String author = "";
    File coverFile;
    final List<Src> sources = new ArrayList<>();

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("author", author);
        o.put("cover", coverFile == null ? "" : coverFile.getAbsolutePath());
        JSONArray a = new JSONArray();
        for (Src s : sources) a.put(s.toJson());
        o.put("sources", a);
        return o;
    }

    static BookSpec fromJson(JSONObject o) {
        BookSpec b = new BookSpec();
        b.id = o.optString("id", b.id);
        b.title = o.optString("title");
        b.author = o.optString("author");
        String c = o.optString("cover");
        b.coverFile = c.isEmpty() ? null : new File(c);
        JSONArray a = o.optJSONArray("sources");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                Src s = Src.fromJson(a.optJSONObject(i));
                if (s.url || (s.file != null && s.file.exists())) b.sources.add(s);
            }
        }
        return b;
    }
}
