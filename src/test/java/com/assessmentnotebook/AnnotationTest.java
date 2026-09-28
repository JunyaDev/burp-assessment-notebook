package com.assessmentnotebook;

import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.core.PageRegistration;
import com.assessmentnotebook.model.Annotation;
import com.assessmentnotebook.model.Page;
import com.assessmentnotebook.model.Screenshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AnnotationTest {

    private static byte[] png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test void annotatedScreenshotRendersSvgOverlay(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("anno");

        PageRegistration reg = new PageRegistration();
        reg.url = "https://s.test/dash";
        reg.method = "GET";
        reg.statusCode = 200;
        reg.contentType = "text/html";
        Page page = c.registerPage(reg);

        Screenshot shot = c.addScreenshot(page.id, png(800, 600), "Default", 0, null);
        assertEquals(800, shot.imageWidth);
        assertEquals(600, shot.imageHeight);

        Annotation a = c.addAnnotation(shot.id, 100, 120, 200, 60,
                "Admin panel", "visible without auth", null);
        assertNotNull(a);

        String html = Files.readString(c.layout().pages().resolve(page.id + ".html"));
        assertTrue(html.contains("<svg class=\"annotated\""), "SVG overlay rendered");
        assertTrue(html.contains("viewBox=\"0 0 800 600\""), "viewBox uses image size");
        assertTrue(html.contains("stroke=\"#e8564b\""), "prominent red outline");
        assertTrue(html.contains("Admin panel"), "label rendered");
    }

    @Test void unannotatedScreenshotStaysPlainImage(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("anno2");
        PageRegistration reg = new PageRegistration();
        reg.url = "https://s.test/x";
        reg.method = "GET";
        Page page = c.registerPage(reg);
        c.addScreenshot(page.id, png(400, 300), "Default", 0, null);
        String html = Files.readString(c.layout().pages().resolve(page.id + ".html"));
        assertFalse(html.contains("<svg class=\"annotated\""));
        assertTrue(html.contains("<img src="));
    }
}
