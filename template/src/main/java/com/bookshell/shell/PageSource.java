package com.bookshell.shell;

import android.graphics.Bitmap;

/** Something that can draw numbered pages into a bitmap. render() blocks and is called off the UI thread. */
interface PageSource {
    int pageCount();

    /** width / height of a typical page. */
    float aspect();

    /** Draws page (0-based) fitted and centred into the bitmap on a paper-coloured background. */
    void render(int page, Bitmap target);

    void close();

    /** EPUB parts can reflow; PDF parts cannot. */
    boolean isReflowable();
}
