package com.bookshell.builder;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads a PDF / EPUB from a link while the APK is being built. */
final class Downloader {
    private Downloader() {}

    interface Log {
        void log(String m);
    }

    /** Turns common "share" links into direct download links. */
    static String direct(String u) {
        u = u.trim();
        Matcher m = Pattern.compile("drive\\.google\\.com/file/d/([\\w-]+)").matcher(u);
        if (m.find()) return "https://drive.usercontent.google.com/download?id=" + m.group(1) + "&export=download&confirm=t";
        m = Pattern.compile("drive\\.google\\.com/(?:open|uc)\\?.*?id=([\\w-]+)").matcher(u);
        if (m.find()) return "https://drive.usercontent.google.com/download?id=" + m.group(1) + "&export=download&confirm=t";
        if (u.contains("dropbox.com")) {
            u = u.replaceAll("[?&]dl=0", "");
            u += (u.contains("?") ? "&" : "?") + "dl=1";
        }
        return u;
    }

    /** @return "pdf" or "epub" */
    static String fetch(String link, File dest, Log log) throws Exception {
        String url = direct(link);
        HttpURLConnection c = null;
        for (int hop = 0; hop < 8; hop++) {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) BookShellBuilder");
            c.setRequestProperty("Accept", "application/pdf,application/epub+zip,*/*");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("রিডাইরেক্ট ঠিকানা নেই: " + link);
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new Exception("ডাউনলোড ব্যর্থ (HTTP " + code + "): " + link);
            }
            break;
        }
        if (c == null) throw new Exception("ডাউনলোড ব্যর্থ: " + link);
        long total = c.getContentLengthLong();
        long done = 0, lastLog = 0;
        try (InputStream in = new BufferedInputStream(c.getInputStream(), 65536);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (done - lastLog > 1_000_000) {
                    lastLog = done;
                    log.log("ডাউনলোড হচ্ছে… " + (done / 1024 / 1024) + " MB" + (total > 0 ? " / " + (total / 1024 / 1024) + " MB" : ""));
                }
            }
        } finally {
            c.disconnect();
        }
        String t = sniff(dest);
        if (t == null) {
            dest.delete();
            throw new Exception("লিংকটি সরাসরি PDF/EPUB ফাইল নয় (ওয়েব পেজ এসেছে): " + link);
        }
        return t;
    }

    /** Looks at the first bytes: %PDF is a PDF, PK is a zip (EPUB). */
    static String sniff(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] h = new byte[1024];
            int n = in.read(h);
            String s = new String(h, 0, Math.max(0, n), "ISO-8859-1");
            if (s.contains("%PDF-")) return "pdf";
            if (s.startsWith("PK")) return "epub";
        } catch (Exception ignored) {
        }
        return null;
    }
}
