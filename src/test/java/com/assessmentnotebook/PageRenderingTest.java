package com.assessmentnotebook;

import com.assessmentnotebook.analyze.HtmlAnalyzer;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.core.PageRegistration;
import com.assessmentnotebook.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page document surfaces data that used to be captured but never shown:
 * inbound vulnerability links, page-scoped parameter tests, and variant bodies.
 */
class PageRenderingTest {

    private static Page registerLoginPage(NotebookController c) throws IOException {
        PageRegistration reg = new PageRegistration();
        reg.url = "https://site.test/rest/user/login";
        reg.method = "POST";
        reg.statusCode = 401;
        reg.contentType = "application/json";
        reg.discovered = new HtmlAnalyzer().analyze("<html><body>x</body></html>", reg.url);
        return c.registerPage(reg);
    }

    private static String pageHtml(NotebookController c, String pageId) throws IOException {
        return Files.readString(c.layout().pages().resolve(pageId + ".html"));
    }

    @Test void pageShowsVulnerabilityThatAffectsIt(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("render");
        Page page = registerLoginPage(c);
        c.createVulnerability("SQLi login bypass", Vulnerability.Severity.CRITICAL, page.url);

        String html = pageHtml(c, page.id);
        assertTrue(html.contains("FINDINGS AFFECTING THIS PAGE"), "page lists its findings");
        assertTrue(html.contains("SQLi login bypass"), "finding title shown on the page");
    }

    @Test void pageShowsOrphanParameterTests(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("render");
        Page page = registerLoginPage(c);

        ParameterTest t = new ParameterTest();
        t.pageId = page.id;
        t.parameterName = "email";
        t.source = ParameterTest.Source.JSON;
        t.probeLabel = "SQL '";
        t.sentValue = "'";
        t.responseStatus = 500;
        t.responseLength = 1183;
        // parameterId deliberately null: no HTML form parameter to attach to.
        c.recordParameterTests(List.of(t));

        String html = pageHtml(c, page.id);
        assertTrue(html.contains("PARAMETER TESTS"), "page renders orphan parameter tests");
        assertTrue(html.contains("email"), "the tested field name appears");
    }

    @Test void pageEmbedsVariantResponseBody(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("render");
        Page page = registerLoginPage(c);

        PageVariant v = new PageVariant();
        v.url = page.url;          // registerVariant groups onto a page by URL/path
        v.method = page.method;
        v.label = "SQLi error";
        v.statusCode = 500;
        c.registerVariant(v, "{\"code\":\"SQLITE_ERROR\",\"message\":\"unrecognized token\"}");

        String html = pageHtml(c, page.id);
        assertTrue(html.contains("VARIANT RESPONSES"), "page has a variant-bodies panel");
        assertTrue(html.contains("SQLITE_ERROR"), "the variant response body is embedded");
    }

    @Test void fullFindingPersistsAndRendersEveryField(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("render");
        Page page = registerLoginPage(c);

        Vulnerability v = new Vulnerability();
        v.title = "SQLi auth bypass";
        v.severity = Vulnerability.Severity.CRITICAL;
        v.status = Vulnerability.Status.CONFIRMED;
        v.affectedUrl = page.url;
        v.affectedComponent = "POST /rest/user/login, JSON field email";
        v.description = "email is concatenated into the SQL query";
        v.technicalObservation = "' OR 1=1-- returns a JWT";
        v.stepsToReproduce = "1. POST the payload\n2. Observe 200 + token";
        v.impact = "Full authentication bypass";
        v.remediation = "Use parameterized queries";
        Vulnerability saved = c.createVulnerability(v);

        assertEquals(Vulnerability.Status.CONFIRMED, saved.status);
        String html = Files.readString(
                c.layout().root.resolve("vulnerabilities/" + saved.id + ".html"));
        for (String needle : new String[]{"POST /rest/user/login, JSON field email",
                "concatenated into the SQL query", "OR 1=1", "Full authentication bypass",
                "parameterized queries", "Confirmed"}) {
            assertTrue(html.contains(needle), "vuln page shows: " + needle);
        }
    }
}
