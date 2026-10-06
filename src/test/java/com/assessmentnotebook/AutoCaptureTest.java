package com.assessmentnotebook;

import com.assessmentnotebook.analyze.HtmlAnalyzer;
import com.assessmentnotebook.analyze.JsonRequestForm;
import com.assessmentnotebook.analyze.VariantDiff;
import com.assessmentnotebook.core.CaptureResult;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.core.PageRegistration;
import com.assessmentnotebook.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auto-capture registers what is new, drops what is already documented, and
 * records a page that answers differently as a variant.
 */
class AutoCaptureTest {

    private static NotebookController project(Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("auto");
        return c;
    }

    private static PageRegistration html(String url, int status, String body) {
        PageRegistration reg = new PageRegistration();
        reg.url = url;
        reg.method = "GET";
        reg.statusCode = status;
        reg.contentType = "text/html";
        reg.pageSource = body;
        reg.requestHeaders = new ArrayList<>();
        reg.rawRequest = ("GET " + url + " HTTP/1.1\r\n\r\n").getBytes();
        reg.rawResponse = ("HTTP/1.1 " + status + "\r\n\r\n" + body).getBytes();
        reg.discovered = new HtmlAnalyzer().analyze(body, url);
        return reg;
    }

    /** A JSON call as RequestExtractor would hand it over: kind API, body fields as a form. */
    private static PageRegistration api(String method, String url, String requestJson,
            int status, String responseJson, String... headers) {
        PageRegistration reg = new PageRegistration();
        reg.kind = Page.Kind.API;
        reg.url = url;
        reg.method = method;
        reg.statusCode = status;
        reg.contentType = "application/json";
        reg.pageSource = responseJson;
        reg.requestHeaders = new ArrayList<>(List.of(headers));
        if (requestJson != null) {
            reg.requestBody = requestJson;
            reg.requestHeaders.add("Content-Type: application/json");
            var form = JsonRequestForm.build(url, method, requestJson);
            if (form != null) reg.discovered.forms.add(form);
        }
        return reg;
    }

    private static String searchPage(String term, int rows) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < rows; i++) items.append("<li><a href='/item/").append(i).append("'>r</a></li>");
        return "<html><head><title>Search " + term + "</title></head><body>"
                + "<form action='/search'><input name='q' value='" + term + "'></form>"
                + "<ul>" + items + "</ul></body></html>";
    }

    private static long sourceFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir.resolve("source"))) {
            return files.count();
        }
    }

    @Test void firstSightingRegistersAndRepeatsAreDropped(@TempDir Path dir) throws IOException {
        NotebookController c = project(dir);

        CaptureResult first = c.capture(html("https://s.test/search?q=apple", 200, searchPage("apple", 3)));
        assertEquals(CaptureResult.Outcome.NEW_PAGE, first.outcome);
        assertTrue(c.flushCapture());
        Project p = c.project();
        assertEquals(1, p.pages.size());
        assertTrue(Files.exists(dir.resolve("pages").resolve(first.entityId + ".html")),
                "flush writes the new page's document");
        long files = sourceFiles(dir);
        int forms = p.forms.size(), links = p.links.size();

        // Same page, other search term, other number of results: nothing new.
        CaptureResult again = c.capture(html("https://s.test/search?q=banana", 200, searchPage("banana", 9)));
        assertEquals(CaptureResult.Outcome.DUPLICATE, again.outcome);
        assertFalse(again.changed());
        assertFalse(c.flushCapture(), "a duplicate leaves nothing to write");

        assertEquals(1, p.pages.size());
        assertEquals(0, p.variants.size());
        assertEquals(forms, p.forms.size());
        assertEquals(links, p.links.size());
        assertEquals(files, sourceFiles(dir), "no evidence is saved for a duplicate");
    }

    @Test void aPageThatAnswersDifferentlyBecomesAVariantOnce(@TempDir Path dir) throws IOException {
        NotebookController c = project(dir);
        c.capture(html("https://s.test/account", 200, "<html><body><h1>Account</h1>"
                + "<form action='/account'><input name='email'></form></body></html>"));
        Page page = c.project().pages.get(0);
        assertNotNull(page.baseline, "the first capture is held as the baseline");

        PageRegistration denied = html("https://s.test/account", 403,
                "<html><body><p>Forbidden</p></body></html>");
        CaptureResult r = c.capture(denied);
        assertEquals(CaptureResult.Outcome.VARIANT, r.outcome);
        assertTrue(r.detail.contains("status 200 → 403"), r.detail);
        assertTrue(r.detail.contains("response structure changed"), r.detail);

        Project p = c.project();
        assertEquals(1, p.pages.size(), "a variant is not a new page");
        assertEquals(2, p.variants.size(), "the baseline is promoted next to the new variant");
        assertNull(page.baseline);
        assertEquals(200, page.statusCode, "the page keeps describing its first capture");
        PageVariant variant = p.findVariant(r.entityId);
        assertTrue(variant.auto);
        assertEquals(403, variant.statusCode);
        assertNotNull(variant.sourceFile);

        List<VariantDiff.Comparison> diffs = c.compareVariants(page.id);
        assertEquals(1, diffs.size());
        assertTrue(diffs.get(0).outputDifferences.stream().anyMatch(d -> d.startsWith("status:")));

        // The same difference seen again is already documented.
        assertEquals(CaptureResult.Outcome.DUPLICATE, c.capture(html("https://s.test/account", 403,
                "<html><body><p>Forbidden</p></body></html>")).outcome);
        assertEquals(2, p.variants.size());
    }

    @Test void aNewParameterIsAVariantAndJoinsTheSameRequestForm(@TempDir Path dir) throws IOException {
        NotebookController c = project(dir);
        String ok = "{\"items\":[{\"id\":1,\"name\":\"a\"}],\"total\":1}";
        c.capture(api("POST", "https://s.test/api/search", "{\"q\":\"apple\"}", 200, ok));
        Project p = c.project();
        Page page = p.pages.get(0);
        assertEquals(1, page.formIds.size());

        // Another value for the same field: same endpoint, nothing to add.
        assertEquals(CaptureResult.Outcome.DUPLICATE,
                c.capture(api("POST", "https://s.test/api/search", "{\"q\":\"pear\"}", 200, ok)).outcome);

        CaptureResult r = c.capture(api("POST", "https://s.test/api/search",
                "{\"q\":\"apple\",\"debug\":true}", 200, ok));
        assertEquals(CaptureResult.Outcome.VARIANT, r.outcome);
        assertEquals("new parameter debug", r.detail);

        assertEquals(1, page.formIds.size(), "the request form is extended, not duplicated");
        Form form = p.findForm(page.formIds.get(0));
        List<String> names = new ArrayList<>();
        for (String pid : form.parameterIds) names.add(p.findParameter(pid).name);
        assertEquals(List.of("q", "debug"), names);

        Parameter q = p.findParameter(form.parameterIds.get(0));
        assertEquals(List.of("apple", "pear"), q.observedValues,
                "values seen for a parameter are kept as examples");
    }

    @Test void idsInThePathAreOneEndpoint(@TempDir Path dir) throws IOException {
        NotebookController c = project(dir);
        String user = "{\"id\":17,\"name\":\"a\",\"roles\":[\"user\"]}";
        CaptureResult first = c.capture(api("GET", "https://s.test/api/users/17", null, 200, user));
        assertEquals(CaptureResult.Outcome.NEW_ENDPOINT, first.outcome);
        assertEquals(CaptureResult.Outcome.DUPLICATE, c.capture(api("GET",
                "https://s.test/api/users/42", null, 200,
                "{\"id\":42,\"name\":\"b\",\"roles\":[\"user\",\"admin\"]}")).outcome);

        Project p = c.project();
        assertEquals(1, p.pages.size());
        Page endpoint = p.pages.get(0);
        assertEquals(Page.Kind.API, endpoint.kind);
        assertEquals("/api/users/{id}", endpoint.pathTemplate);
        assertEquals(List.of("id", "name", "roles"), endpoint.responseFields);
        assertSame(endpoint, c.findPageFor("https://s.test/api/users/99", "GET"),
                "a request for another id resolves to the documented endpoint");

        // A different method on the same path is its own record.
        assertEquals(CaptureResult.Outcome.NEW_ENDPOINT, c.capture(api("DELETE",
                "https://s.test/api/users/17", null, 204, "")).outcome);

        // With collapsing off, each id is its own record.
        CaptureConfig cfg = c.captureConfig();
        cfg.collapseIds = false;
        c.applyCaptureConfig(cfg);
        assertEquals(CaptureResult.Outcome.NEW_ENDPOINT, c.capture(api("GET",
                "https://s.test/api/users/42", null, 200, user)).outcome);
    }

    @Test void aResponseThatGainsFieldsIsAVariantWithNamedFields(@TempDir Path dir) throws IOException {
        NotebookController c = project(dir);
        c.capture(api("GET", "https://s.test/api/me", null, 200, "{\"id\":1,\"name\":\"a\"}"));
        CaptureResult r = c.capture(api("GET", "https://s.test/api/me", null, 200,
                "{\"id\":1,\"name\":\"a\",\"isAdmin\":true}", "Authorization: Bearer x"));
        assertEquals(CaptureResult.Outcome.VARIANT, r.outcome);
        assertTrue(r.detail.contains("authenticated"), r.detail);

        Page endpoint = c.project().pages.get(0);
        assertTrue(endpoint.responseFields.contains("isAdmin"), "new fields are merged into the endpoint");
        VariantDiff.Comparison d = c.compareVariants(endpoint.id).get(0);
        assertTrue(d.outputDifferences.contains("fields added: isAdmin"), d.outputDifferences.toString());
        assertTrue(c.project().findVariant(r.entityId).sourceFile.endsWith(".json"));
    }

    @Test void endpointIsLinkedToThePageThatCallsIt(@TempDir Path dir) throws IOException {
        NotebookController c = project(dir);
        c.capture(html("https://s.test/", 200, "<html><body><div id='app'></div></body></html>"));
        Page shell = c.project().pages.get(0);

        c.capture(api("GET", "https://s.test/api/orders", null, 200, "[{\"id\":1}]",
                "Referer: https://s.test/"));
        c.flushCapture();
        Page endpoint = c.project().pages.get(1);

        assertTrue(c.project().relationships.stream().anyMatch(r ->
                Relationship.CALLS.equals(r.kind) && r.fromId.equals(shell.id)
                        && r.toId.equals(endpoint.id)));
        String shellDoc = Files.readString(dir.resolve("pages").resolve(shell.id + ".html"));
        assertTrue(shellDoc.contains("API CALLS"), "the calling page lists the endpoint");
        String endpointDoc = Files.readString(dir.resolve("pages").resolve(endpoint.id + ".html"));
        assertTrue(endpointDoc.contains("API ENDPOINT // " + endpoint.id));
        assertTrue(endpointDoc.contains("CALLED BY"));
        assertTrue(endpointDoc.contains("RESPONSE FIELDS"));
        String index = Files.readString(dir.resolve("index.html"));
        assertTrue(index.contains("API endpoints"));
    }

    @Test void resourcesAreRegisteredOnceAndGainTheirLoaders(@TempDir Path dir) throws IOException {
        NotebookController c = project(dir);
        c.capture(html("https://s.test/", 200, "<html><body>home</body></html>"));
        c.capture(html("https://s.test/about", 200, "<html><body><p>about</p></body></html>"));
        Page home = c.project().pages.get(0);
        Page about = c.project().pages.get(1);

        CaptureResult first = c.captureResource("https://s.test/main.dart.js?v=1",
                Resource.Type.SCRIPT, List.of("Referer: https://s.test/"));
        assertEquals(CaptureResult.Outcome.NEW_RESOURCE, first.outcome);
        assertEquals(CaptureResult.Outcome.DUPLICATE, c.captureResource(
                "https://s.test/main.dart.js?v=2", Resource.Type.SCRIPT,
                List.of("Referer: https://s.test/")).outcome, "cache-buster does not make a new resource");
        assertEquals(CaptureResult.Outcome.UPDATED, c.captureResource(
                "https://s.test/main.dart.js", Resource.Type.SCRIPT,
                List.of("Referer: https://s.test/about")).outcome, "a second loading page is recorded");

        assertEquals(1, c.project().resources.size());
        Resource script = c.project().resources.get(0);
        assertEquals(List.of(home.id, about.id), script.pageIds);
        assertTrue(home.resourceIds.contains(script.id));
    }

    @Test void variantsStopAtTheConfiguredLimit(@TempDir Path dir) throws IOException {
        NotebookController c = project(dir);
        CaptureConfig cfg = c.captureConfig();
        cfg.maxVariantsPerPage = 3;
        c.applyCaptureConfig(cfg);

        c.capture(html("https://s.test/p", 200, "<html><body>ok</body></html>"));
        assertEquals(CaptureResult.Outcome.VARIANT,
                c.capture(html("https://s.test/p", 401, "<html><body>ok</body></html>")).outcome);
        assertEquals(CaptureResult.Outcome.VARIANT,
                c.capture(html("https://s.test/p", 403, "<html><body>ok</body></html>")).outcome);
        // Baseline + two variants = 3: the page is full.
        CaptureResult capped = c.capture(html("https://s.test/p", 500, "<html><body>ok</body></html>"));
        assertEquals(CaptureResult.Outcome.CAPPED, capped.outcome);
        assertFalse(capped.changed());
        assertEquals(3, c.project().variants.size());
    }

    @Test void manuallyRegisteredPageIsNotCapturedAgain(@TempDir Path dir) throws IOException {
        NotebookController c = project(dir);
        c.registerPage(html("https://s.test/login", 200, "<html><body><form action='/login'>"
                + "<input name='u'></form></body></html>"));
        assertEquals(CaptureResult.Outcome.DUPLICATE, c.capture(html("https://s.test/login", 200,
                "<html><body><form action='/login'><input name='u'></form></body></html>")).outcome);
        assertEquals(1, c.project().pages.size());
        assertEquals(0, c.project().variants.size());
    }

    @Test void pageFromAnOlderProjectIsAdoptedNotDuplicated(@TempDir Path dir) throws IOException {
        NotebookController c = project(dir);
        Page old = new Page();           // as loaded from a pre-auto-capture project.json
        old.id = c.project().nextId(EntityType.PAGE);
        old.url = "https://s.test/legacy";
        c.project().pages.add(old);

        CaptureResult r = c.capture(html("https://s.test/legacy", 200, "<html><body>x</body></html>"));
        assertEquals(CaptureResult.Outcome.DUPLICATE, r.outcome);
        assertEquals(1, old.fingerprints.size(), "its current state becomes the baseline");
        assertEquals(CaptureResult.Outcome.VARIANT,
                c.capture(html("https://s.test/legacy", 500, "<html><body>x</body></html>")).outcome);
    }

    @Test void captureSettingsSurviveReload(@TempDir Path dir) throws IOException {
        NotebookController c = project(dir);
        CaptureConfig cfg = c.captureConfig();
        cfg.enabled = true;
        CaptureRule rule = new CaptureRule();
        rule.name = "api";
        rule.host = "*.s.test";
        rule.action = CaptureRule.Action.API;
        cfg.rules.add(rule);
        c.applyCaptureConfig(cfg);
        c.capture(api("GET", "https://s.test/api/x", null, 200, "{\"a\":1}"));
        c.flushCapture();

        NotebookController reopened = new NotebookController(dir);
        reopened.open();
        CaptureConfig loaded = reopened.captureConfig();
        assertTrue(loaded.enabled);
        assertEquals(1, loaded.rules.size());
        assertEquals(CaptureRule.Action.API, loaded.rules.get(0).action);
        assertEquals(Page.Kind.API, reopened.project().pages.get(0).kind);
        assertEquals(CaptureResult.Outcome.DUPLICATE, reopened.capture(
                api("GET", "https://s.test/api/x", null, 200, "{\"a\":2}")).outcome,
                "fingerprints persist, so a restart does not re-register everything");
    }

    @Test void credentialsAreNotCopiedIntoObservedValues(@TempDir Path dir) throws IOException {
        NotebookController c = project(dir);
        c.capture(api("POST", "https://s.test/api/login",
                "{\"email\":\"a@b.test\",\"password\":\"hunter2\"}", 200, "{\"ok\":true}"));
        Project p = c.project();
        Form form = p.findForm(p.pages.get(0).formIds.get(0));
        for (String pid : form.parameterIds) {
            Parameter param = p.findParameter(pid);
            if (param.name.equals("password")) assertTrue(param.observedValues.isEmpty());
            if (param.name.equals("email")) assertEquals(List.of("a@b.test"), param.observedValues);
        }
    }
}
