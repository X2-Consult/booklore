package org.booklore.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Renders PDF pages at a DPI that also respects a pixel budget. A fixed DPI is fine for a normal
 * page, but PDF pages can be any size: a scanned comic or art book stored as one poster-sized image
 * per page (50 x 70 in) comes out at 15000 x 21000 px at 300 DPI, about 945 MB for one BufferedImage,
 * which is how single-image PDFs ran the JVM out of heap.
 */
public final class PdfRenderUtils {

    /** Covers are stored at most 1000 x 1500, so rendering past this long side is thrown away. */
    public static final int COVER_MAX_LONG_SIDE_PX = 1500;

    private PdfRenderUtils() {
    }

    public static BufferedImage renderPage(PDDocument document, int pageIndex, float preferredDpi, int maxLongSidePx) throws IOException {
        float dpi = cappedDpi(document.getPage(pageIndex).getCropBox(), preferredDpi, maxLongSidePx);
        return new PDFRenderer(document).renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
    }

    /** The preferred DPI, lowered if needed so the page's longer side stays within {@code maxLongSidePx}. */
    static float cappedDpi(PDRectangle pageBox, float preferredDpi, int maxLongSidePx) {
        float longSidePoints = pageBox == null ? 0 : Math.max(pageBox.getWidth(), pageBox.getHeight());
        if (longSidePoints <= 0) {
            return preferredDpi;
        }
        // PDF user space is 72 points per inch.
        float dpiForBudget = maxLongSidePx * 72f / longSidePoints;
        return Math.min(preferredDpi, dpiForBudget);
    }
}
