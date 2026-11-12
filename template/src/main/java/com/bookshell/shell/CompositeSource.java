package com.bookshell.shell;

import android.graphics.Bitmap;

import java.util.List;

/** Several sources read one after another as a single book (this is how several PDFs become one book). */
final class CompositeSource implements PageSource {
    private final List<PageSource> parts;
    private final int[] offset;
    private final int total;

    CompositeSource(List<PageSource> parts) {
        this.parts = parts;
        offset = new int[parts.size() + 1];
        for (int i = 0; i < parts.size(); i++) offset[i + 1] = offset[i] + parts.get(i).pageCount();
        total = offset[parts.size()];
    }

    @Override
    public int pageCount() {
        return total;
    }

    @Override
    public float aspect() {
        return parts.isEmpty() ? 0.707f : parts.get(0).aspect();
    }

    @Override
    public boolean isReflowable() {
        for (PageSource p : parts) if (p.isReflowable()) return true;
        return false;
    }

    @Override
    public void render(int page, Bitmap target) {
        for (int i = 0; i < parts.size(); i++) {
            if (page < offset[i + 1]) {
                parts.get(i).render(page - offset[i], target);
                return;
            }
        }
    }

    @Override
    public void close() {
        for (PageSource p : parts) p.close();
    }
}
