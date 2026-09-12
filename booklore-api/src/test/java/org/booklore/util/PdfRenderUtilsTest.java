package org.booklore.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class PdfRenderUtilsTest {

    @Test
    void ordinaryPageKeepsThePreferredDpi() {
        // A4 at 150 DPI is 1240 x 1754 px: under the 3000 px reader budget, so untouched.
        assertThat(PdfRenderUtils.cappedDpi(PDRectangle.A4, 150, 3000)).isEqualTo(150f);
    }

    @Test
    void oversizedPageGetsItsDpiLowered() {
        PDRectangle poster = new PDRectangle(50 * 72, 70 * 72);

        float dpi = PdfRenderUtils.cappedDpi(poster, 300, 1500);

        assertThat(dpi * 70).isLessThanOrEqualTo(1500f);
    }

    @Test
    void landscapePagesAreBudgetedOnTheirLongerSide() {
        PDRectangle wide = new PDRectangle(70 * 72, 50 * 72);

        assertThat(PdfRenderUtils.cappedDpi(wide, 300, 1500)).isEqualTo(PdfRenderUtils.cappedDpi(new PDRectangle(50 * 72, 70 * 72), 300, 1500));
    }

    @Test
    void emptyPageBoxFallsBackToThePreferredDpi() {
        assertThat(PdfRenderUtils.cappedDpi(new PDRectangle(0, 0), 200, 1500)).isEqualTo(200f);
        assertThat(PdfRenderUtils.cappedDpi(null, 200, 1500)).isEqualTo(200f);
    }

    @Test
    void posterSizedPageRendersWithinTheBudget_notAt300Dpi() throws Exception {
        // At 300 DPI this page would be 15000 x 21000 px (~945 MB as RGB).
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(50 * 72, 70 * 72)));

            BufferedImage image = PdfRenderUtils.renderPage(document, 0, 300, PdfRenderUtils.COVER_MAX_LONG_SIDE_PX);

            assertThat(Math.max(image.getWidth(), image.getHeight())).isLessThanOrEqualTo(PdfRenderUtils.COVER_MAX_LONG_SIDE_PX);
            assertThat(image.getHeight()).isGreaterThan(image.getWidth());
        }
    }
}
