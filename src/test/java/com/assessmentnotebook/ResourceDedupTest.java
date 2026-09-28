package com.assessmentnotebook;

import com.assessmentnotebook.analyze.HtmlAnalyzer;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.core.PageRegistration;
import com.assessmentnotebook.model.Page;
import com.assessmentnotebook.model.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Proves spec §10/§11: a resource has one canonical identity. */
class ResourceDedupTest {

    private static Page registerWithScript(NotebookController c, String url, String scriptSrc)
            throws IOException {
        String html = "<html><head><script src=" + scriptSrc + "></script></head>"
                + "<body>x</body></html>";
        PageRegistration reg = new PageRegistration();
        reg.url = url;
        reg.method = "GET";
        reg.statusCode = 200;
        reg.contentType = "text/html";
        reg.discovered = new HtmlAnalyzer().analyze(html, url);
        return c.registerPage(reg);
    }

    @Test void sameScriptFromManyPagesIsOneResourceWithManyLoaders(@TempDir Path dir)
            throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("dedup");

        Page a = registerWithScript(c, "https://s.test/index", "/static/app.js");
        Page b = registerWithScript(c, "https://s.test/dashboard", "/static/app.js?v=9f3a");
        Page cc = registerWithScript(c, "https://s.test/profile", "/static/app.js#load");

        assertEquals(1, c.project().resources.size(), "one canonical script record");
        Resource app = c.project().resources.get(0);
        assertEquals(Resource.Type.SCRIPT, app.type);
        assertEquals(3, app.pageIds.size(), "loaded by all three pages");
        assertTrue(app.pageIds.containsAll(java.util.List.of(a.id, b.id, cc.id)));
    }

    @Test void standaloneRegistrationMergesWithDiscoveredAndUpgradesType(@TempDir Path dir)
            throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("merge");

        // Discovered from a page as a SCRIPT.
        Page page = registerWithScript(c, "https://s.test/index", "/static/app.js");
        assertEquals(1, c.project().resources.size());

        // Independently "Register Resource" on the same URL, misclassified OTHER.
        Resource merged = c.addResource("https://s.test/static/app.js?cachebust=1",
                Resource.Type.OTHER, "seen in traffic", page.id);

        assertEquals(1, c.project().resources.size(), "no duplicate created");
        assertEquals(Resource.Type.SCRIPT, merged.type, "specific type retained over OTHER");
        assertEquals(1, merged.pageIds.size());
        assertTrue(merged.observedUrls.size() >= 2, "raw URL forms preserved");
    }

    @Test void otherUpgradesToSpecificWhenSeenLater(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("upgrade");
        c.addResource("https://s.test/thing", Resource.Type.OTHER);
        Resource r = c.addResource("https://s.test/thing", Resource.Type.API);
        assertEquals(1, c.project().resources.size());
        assertEquals(Resource.Type.API, r.type);
    }
}
