package com.bookshell.shell;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;

final class PdfSource implements PageSource {
    private final ParcelFileDescriptor fd;
    private final PdfRenderer renderer;
    private final int count;
    private final float aspect;
    private final Object lock = new Object();

    PdfSource(File f) throws IOException {
        fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            renderer = new PdfRenderer(fd);
        } catch (IOException | SecurityException e) {
            fd.close();
            throw e instanceof IOException ? (IOException) e : new IOException("PDF পাসওয়ার্ড দেওয়া বা নষ্ট", e);
        }
        count = renderer.getPageCount();
        float a = 0.707f;
        if (count > 0) {
            PdfRenderer.Page p = renderer.openPage(0);
            try {
                if (p.getHeight() > 0) a = p.getWidth() / (float) p.getHeight();
            } finally {
                p.close();
            }
        }
        aspect = a;
    }

    @Override
    public int pageCount() {
        return count;
    }

    @Override
    public float aspect() {
        return aspect;
    }

    @Override
    public boolean isReflowable() {
        return false;
    }

    @Override
    public void render(int page, Bitmap target) {
        target.eraseColor(Color.WHITE);
        synchronized (lock) {
            if (page < 0 || page >= count) return;
            PdfRenderer.Page p = renderer.openPage(page);
            try {
                float sc = Math.min(target.getWidth() / (float) p.getWidth(), target.getHeight() / (float) p.getHeight());
                float dw = p.getWidth() * sc;
                float dh = p.getHeight() * sc;
                Matrix m = new Matrix();
                m.setScale(sc, sc);
                m.postTranslate((target.getWidth() - dw) / 2f, (target.getHeight() - dh) / 2f);
                p.render(target, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            } finally {
                p.close();
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                renderer.close();
            } catch (Exception ignored) {
            }
            try {
                fd.close();
            } catch (Exception ignored) {
            }
        }
    }
}
