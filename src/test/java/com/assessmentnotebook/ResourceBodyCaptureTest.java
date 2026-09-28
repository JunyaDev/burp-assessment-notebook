package com.assessmentnotebook;

import com.assessmentnotebook.analyze.HtmlAnalyzer;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.core.PageRegistration;
import com.assessmentnotebook.model.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The "Capture resource bodies" toggle: a body carried on a discovered resource
 * is saved as that resource's source copy at page registration, deduplicated on
 * re-registration, and updated when the body changes.
 */
class ResourceBodyCaptureTest {

    private static PageRegistration pageWithScript(String url, String scriptSrc) {
        String html = "<html><head><script src=\"" + scriptSrc + "\"></script></head>"
                + "<body>x</body></html>";
        PageRegistration reg = new PageRegistration();
        reg.url = url;
        reg.method = "GET";
        reg.statusCode = 200;
        reg.contentType = "text/html";
        reg.discovered = new HtmlAnalyzer().analyze(html, url);
        return reg;
    }

    private static long resourceSourceCount(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir.resolve("source"))) {
            return s.filter(p -> p.getFileName().toString().contains("app.js")).count();
        }
    }

    @Test void capturedBodyIsSavedAsResourceSource(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("bodies");

        PageRegistration reg = pageWithScript("https://s.test/index", "/static/app.js");
        reg.discovered.resources.get(0).body = "console.log('v1');";
        reg.discovered.resources.get(0).bodySource = "proxy history";
        c.registerPage(reg);

        Resource app = c.project().resources.get(0);
        assertNotNull(app.sourceFile, "resource has a saved source copy");
        assertFalse(app.sourceFile.isBlank());
        assertEquals("console.log('v1');",
                Files.readString(c.layout().root.resolve(app.sourceFile)));
        assertTrue(app.notes.contains("proxy history"), "provenance recorded in notes");
    }

    @Test void identicalBodyOnReRegistrationIsNotDuplicated(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("bodies");

        PageRegistration first = pageWithScript("https://s.test/index", "/static/app.js");
        first.discovered.resources.get(0).body = "console.log('same');";
        c.registerPage(first);
        String savedFile = c.project().resources.get(0).sourceFile;

        PageRegistration again = pageWithScript("https://s.test/index", "/static/app.js");
        again.discovered.resources.get(0).body = "console.log('same');";
        c.registerPage(again);

        assertEquals(savedFile, c.project().resources.get(0).sourceFile, "same file reused");
        assertEquals(1, resourceSourceCount(dir), "no duplicate source file written");
    }

    @Test void changedBodyReplacesTheSavedSource(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("bodies");

        PageRegistration first = pageWithScript("https://s.test/index", "/static/app.js");
        first.discovered.resources.get(0).body = "console.log('v1');";
        c.registerPage(first);

        PageRegistration second = pageWithScript("https://s.test/index", "/static/app.js");
        second.discovered.resources.get(0).body = "console.log('v2');";
        c.registerPage(second);

        Resource app = c.project().resources.get(0);
        assertEquals("console.log('v2');",
                Files.readString(c.layout().root.resolve(app.sourceFile)));
    }

    @Test void noBodyLeavesResourceIdentityOnly(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("bodies");

        c.registerPage(pageWithScript("https://s.test/index", "/static/app.js"));

        Resource app = c.project().resources.get(0);
        assertTrue(app.sourceFile == null || app.sourceFile.isBlank(),
                "identity-only registration saves no body");
    }
}
