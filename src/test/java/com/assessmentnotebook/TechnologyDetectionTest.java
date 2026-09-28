package com.assessmentnotebook;

import com.assessmentnotebook.analyze.TechnologyDetector;
import com.assessmentnotebook.analyze.TechnologyDetector.Detection;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.model.Technology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TechnologyDetectionTest {

    private final TechnologyDetector det = new TechnologyDetector();

    private Detection byName(List<Detection> ds, String name) {
        return ds.stream().filter(d -> d.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    @Test void detectsServerAndVersionFromHeader() {
        List<Detection> ds = det.detect("https://s.test/",
                List.of("Server: nginx/1.25.3", "X-Powered-By: PHP/8.2.1"), "", "text/html");
        Detection nginx = byName(ds, "nginx");
        assertNotNull(nginx);
        assertEquals("1.25.3", nginx.version);
        assertEquals(Technology.Confidence.HIGH, nginx.confidence);
        Detection php = byName(ds, "PHP");
        assertNotNull(php);
        assertEquals("8.2.1", php.version);
    }

    @Test void detectsFrameworkFromCookie() {
        List<Detection> ds = det.detect("https://s.test/",
                List.of("Set-Cookie: laravel_session=abc; path=/"), "", "text/html");
        assertNotNull(byName(ds, "Laravel"));
    }

    @Test void detectsAngularVersionFromMarkup() {
        List<Detection> ds = det.detect("https://s.test/",
                List.of(), "<app-root ng-version=\"17.1.0\"></app-root>", "text/html");
        Detection ng = byName(ds, "Angular");
        assertNotNull(ng);
        assertEquals("17.1.0", ng.version);
    }

    @Test void detectsLibraryAndVersionFromScriptUrl() {
        List<Detection> ds = det.detect("https://s.test/",
                List.of(), "<script src=\"/static/jquery-3.6.0.min.js\"></script>", "text/html");
        Detection jq = byName(ds, "jQuery");
        assertNotNull(jq);
        assertEquals("3.6.0", jq.version);
    }

    @Test void detectsWordpressFromContentPath() {
        List<Detection> ds = det.detect("https://s.test/",
                List.of(), "<link href=\"/wp-content/themes/x/style.css\">", "text/html");
        assertNotNull(byName(ds, "WordPress"));
    }

    @Test void mergeUpgradesVersionAndKeepsHistoryWithoutDuplicating(@TempDir Path dir)
            throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("tech");

        // First a bare React marker (no version).
        c.recordDetections(det.detect("https://s.test/", List.of(),
                "<div data-reactroot></div>", "text/html"));
        assertEquals(1, c.project().technologies.size());
        Technology react = c.project().technologies.get(0);
        assertEquals("React", react.name);
        assertEquals("", react.version);

        // Later a precise version from a script URL.
        c.recordDetections(det.detect("https://s.test/", List.of(),
                "<script src=\"/static/react@18.3.1/react.production.min.js\"></script>",
                "text/html"));
        assertEquals(1, c.project().technologies.size(), "no duplicate React record");
        react = c.project().technologies.get(0);
        assertEquals("18.3.1", react.version, "version upgraded in place");
        assertTrue(react.history.stream().anyMatch(h -> h.contains("18.3.1")), "history kept");
        assertTrue(react.evidences.size() >= 2, "evidence accumulated");
    }

    @Test void manualEditIsNotOverwrittenByDetection(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("tech");
        Technology t = c.addTechnology(Technology.Category.JS_LIBRARY, "jQuery", "3.6.0", "manual", "");
        c.updateTechnology(t.id, null, null, "3.6.0", Technology.Confidence.CONFIRMED, "verified");

        // A detection claiming a different version must not clobber the edit.
        c.recordDetections(det.detect("https://s.test/", List.of(),
                "<script src=\"/jquery-2.1.0.min.js\"></script>", "text/html"));
        Technology after = c.project().technologies.stream()
                .filter(x -> x.name.equalsIgnoreCase("jQuery")).findFirst().orElseThrow();
        assertEquals("3.6.0", after.version, "tester's version preserved");
        assertEquals(Technology.Confidence.CONFIRMED, after.confidence);
        assertEquals(1, c.project().technologies.size());
    }

    @Test void versionSpecificityRule() {
        assertTrue(NotebookController.isMoreSpecificVersion("18.3.1", "18"));
        assertTrue(NotebookController.isMoreSpecificVersion("1.0", ""));
        assertFalse(NotebookController.isMoreSpecificVersion("18", "18.3.1"));
        assertFalse(NotebookController.isMoreSpecificVersion("", "1.0"));
        assertFalse(NotebookController.isMoreSpecificVersion("1.0", "1.0"));
    }
}
