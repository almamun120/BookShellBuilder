package com.bookshell.shell;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Copies a book's files out of the APK (PdfRenderer needs a real file) and opens them as one PageSource. */
final class BookLoader {
    private BookLoader() {}

    static PageSource load(Context ctx, AppConfig cfg, AppConfig.Book book, float textScale) throws Exception {
        File dir = booksDir(ctx);
        List<PageSource> parts = new ArrayList<>();
        try {
            for (AppConfig.Source s : book.sources) {
                File f = ensure(ctx, dir, s.file);
                if ("epub".equals(s.type)) parts.add(new EpubSource(f, textScale, cfg.paper, cfg.ink));
                else parts.add(new PdfSource(f));
            }
        } catch (Exception | OutOfMemoryError e) {
            for (PageSource p : parts) p.close();
            if (e instanceof OutOfMemoryError) throw new Exception("মেমরি কম পড়েছে");
            throw (Exception) e;
        }
        if (parts.size() == 1) return parts.get(0);
        return new CompositeSource(parts);
    }

    /** books_v<versionCode>; older folders are removed so an update never reads stale files. */
    private static File booksDir(Context ctx) {
        int ver = 1;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            ver = pi.versionCode;
        } catch (Exception ignored) {
        }
        String mine = "books_v" + ver;
        File[] kids = ctx.getFilesDir().listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.getName().startsWith("books_v") && !k.getName().equals(mine)) deleteRec(k);
            }
        }
        File d = new File(ctx.getFilesDir(), mine);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File ensure(Context ctx, File dir, String asset) throws Exception {
        File out = new File(dir, asset.replace('/', '_'));
        if (out.exists() && out.length() > 0) return out;
        File tmp = new File(dir, out.getName() + ".part");
        try (InputStream in = ctx.getAssets().open(asset); OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        if (!tmp.renameTo(out)) throw new java.io.IOException("ফাইল সেভ করা যায়নি");
        return out;
    }

    private static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
