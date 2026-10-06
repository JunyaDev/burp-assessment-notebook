package com.assessmentnotebook;

import com.assessmentnotebook.analyze.DiscoveredPage;
import com.assessmentnotebook.analyze.HtmlAnalyzer;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.core.PageRegistration;
import com.assessmentnotebook.example.ExampleProjectGenerator;
import com.assessmentnotebook.html.HtmlGenerator;
import com.assessmentnotebook.model.*;
import com.assessmentnotebook.repair.ProjectRepair;
import com.assessmentnotebook.repair.RepairReport;
import com.assessmentnotebook.repair.RepairTool;
import com.assessmentnotebook.store.ProjectStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The project check must stay silent on a healthy project, change nothing in a
 * dry run, and after a repair leave a project that a second check finds clean.
 */
class ProjectRepairTest {

    private static final String HOME = "<html><head><title>Home</title>"
            + "<script src='/app.js'></script><link rel='stylesheet' href='/site.css'></head><body>"
            + "<a href='/login'>Sign in</a><a href='/about'>About</a></body></html>";
    private static final String LOGIN = "<html><head><title>Login</title></head><body>"
            + "<form id='login' action='/login' method='post'><input name='user'>"
            + "<input name='pass' type='password'></form><a href='/'>Home</a></body></html>";

    // ---- building projects -------------------------------------------------

    private static PageRegistration html(String url, String body) {
        PageRegistration reg = new PageRegistration();
        reg.url = url;
        reg.statusCode = 200;
        reg.contentType = "text/html";
        reg.pageSource = body;
        reg.requestHeaders = new ArrayList<>();
        reg.responseHeaders = new ArrayList<>(List.of("Content-Type: text/html"));
        reg.rawRequest = ("GET " + url + " HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        reg.rawResponse = ("HTTP/1.1 200 OK\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8);
        reg.discovered = new HtmlAnalyzer().analyze(body, url);
        return reg;
    }

    private static PageRegistration json(String url, String body, String... requestHeaders) {
        PageRegistration reg = new PageRegistration();
        reg.url = url;
        reg.statusCode = 200;
        reg.contentType = "application/json";
        reg.pageSource = body;
        reg.requestHeaders = new ArrayList<>(List.of(requestHeaders));
        reg.rawRequest = ("GET " + url + " HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        reg.rawResponse = ("HTTP/1.1 200 OK\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8);
        return reg;
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** A project that uses every kind of record, built the way the extension builds it. */
    private static NotebookController healthy(Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("repair");
        Page home = c.registerPage(html("https://t.test/", HOME));
        Page login = c.registerPage(html("https://t.test/login", LOGIN));

        PageRegistration users = json("https://t.test/api/users/17",
                "{\"id\":17,\"name\":\"ann\"}", "Referer: https://t.test/");
        users.kind = Page.Kind.API;
        c.capture(users);
        c.flushCapture();

        c.addNote(EntityType.PAGE, login.id, Note.Kind.HYPOTHESIS, "user enumeration?");
        c.createVulnerability("Reflected XSS", Vulnerability.Severity.HIGH, "https://t.test/login");
        c.addScreenshot(home.id, png(), "Default", 0, null);

        PageVariant v = new PageVariant();
        v.url = "https://t.test/login?err=1";
        v.label = "error";
        v.statusCode = 200;
        c.registerVariant(v, LOGIN.replace("</form>", "<p>bad</p></form>"));

        ParameterTest t = new ParameterTest();
        t.pageId = login.id;
        t.parameterName = "user";
        t.probeLabel = "empty";
        c.recordParameterTest(t);

        JsFinding f = new JsFinding();
        f.kind = JsFinding.Kind.ENDPOINT;
        f.value = "/api/secret";
        c.recordJsFindings("https://t.test/app.js", home.id, "fetch('/api/secret')", List.of(f));
        return c;
    }

    /** Load the model from disk, damage it, and write it back without regenerating anything. */
    private static void damage(Path dir, Consumer<Project> how) throws IOException {
        ProjectStore store = new ProjectStore(dir);
        Project p = store.load();
        how.accept(p);
        store.save(p);
    }

    private static Project load(Path dir) throws IOException {
        return new ProjectStore(dir).load();
    }

    private static RepairReport check(Path dir) throws IOException {
        return new ProjectRepair(dir, new ProjectRepair.Options()).run();
    }

    private static RepairReport repair(Path dir, boolean prune) throws IOException {
        ProjectRepair.Options o = new ProjectRepair.Options();
        o.apply = true;
        o.prune = prune;
        return new ProjectRepair(dir, o).run();
    }

    private static void assertClean(RepairReport r) {
        assertTrue(r.clean(), () -> "expected a clean project but got:\n" + r.render(true));
    }

    private static Page page(Project p, String url) {
        return p.pages.stream().filter(x -> x.url.equals(url)).findFirst().orElseThrow();
    }

    private static boolean hasEdge(Project p, String fromId, String kind, String toId) {
        return p.relationships.stream().anyMatch(e -> kind.equals(e.kind)
                && fromId.equals(e.fromId) && toId.equals(e.toId));
    }

    /** Every file under the project with its size and modification time. */
    private static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                out.put(dir.relativize(f).toString(),
                        Files.size(f) + "@" + Files.getLastModifiedTime(f).toMillis());
            }
        }
        return out;
    }

    // ---- no false alarms ---------------------------------------------------

    @Test void healthyProjectIsReportedClean(@TempDir Path dir) throws IOException {
        healthy(dir);
        assertClean(check(dir));
    }

    @Test void generatedExampleProjectIsReportedClean(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("example");
        ExampleProjectGenerator.main(new String[]{out.toString()});
        assertClean(check(out));
    }

    @Test void repairingAHealthyProjectLeavesTheModelAlone(@TempDir Path dir) throws IOException {
        healthy(dir);
        byte[] before = Files.readAllBytes(dir.resolve("project.json"));
        RepairReport r = repair(dir, true);
        assertEquals(0, r.fixableCount());
        assertNull(r.backup, "nothing changed, so nothing to back up");
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("project.json")));
    }

    @Test void dryRunWritesNothing(@TempDir Path dir) throws IOException {
        healthy(dir);
        damage(dir, p -> {
            p.relationships.clear();
            p.pages.get(0).formIds.add("form-9999");
            p.resources.get(0).url = "https://T.TEST:443/app.js?v=9";
        });
        Files.writeString(dir.resolve("source").resolve("stray.txt"), "left behind");
        Files.writeString(dir.resolve("pages").resolve("page-9999.html"), "<html></html>");
        Map<String, String> before = snapshot(dir);

        ProjectRepair.Options o = new ProjectRepair.Options();
        o.prune = true; // still a dry run: apply is off
        RepairReport r = new ProjectRepair(dir, o).run();

        assertFalse(r.applied);
        assertTrue(r.fixableCount() > 0);
        assertEquals(before, snapshot(dir), "a dry run must not touch a single file");
    }

    // ---- connections -------------------------------------------------------

    @Test void lostConnectionsAreRebuilt(@TempDir Path dir) throws IOException {
        healthy(dir);
        damage(dir, p -> {
            Page login = page(p, "https://t.test/login");
            Page home = page(p, "https://t.test/");
            login.formIds.clear();                       // page forgot its form
            p.links.forEach(l -> {
                l.destinationPageId = null;              // links never resolved
                if (l.sourcePageId.equals(home.id)) l.sourcePageId = null; // link forgot its page
            });
            p.variants.get(0).pageId = "page-4242";      // variant points at nothing
            login.variantIds.clear();
            p.parameterTests.get(0).parameterId = null;
            p.resources.forEach(res -> res.pageIds.clear());
            p.relationships.clear();                     // every edge gone
        });

        RepairReport r = repair(dir, false);
        assertEquals(0, r.attentionCount(), r.render(true));
        assertTrue(r.brokenLinks.isEmpty(), r.render(true));
        assertNotNull(r.backup);
        assertTrue(Files.exists(r.backup), "the previous project.json is kept");

        Project p = load(dir);
        Page home = page(p, "https://t.test/");
        Page login = page(p, "https://t.test/login");
        Page users = page(p, "https://t.test/api/users/17");
        Form form = p.forms.get(0);

        assertEquals(List.of(form.id), login.formIds);
        assertTrue(hasEdge(p, login.id, Relationship.CONTAINS_FORM, form.id));
        assertTrue(hasEdge(p, form.id, Relationship.HAS_PARAMETER, form.parameterIds.get(0)));
        for (Link l : p.links) {
            assertNotNull(l.sourcePageId, "link " + l.id + " has its page back");
            assertTrue(hasEdge(p, l.sourcePageId, Relationship.CONTAINS_LINK, l.id));
        }
        Link toLogin = p.links.stream().filter(l -> l.destinationUrl.endsWith("/login")
                && home.id.equals(l.sourcePageId)).findFirst().orElseThrow();
        assertEquals(login.id, toLogin.destinationPageId);
        assertTrue(hasEdge(p, home.id, Relationship.LINKS_TO, login.id));

        assertEquals(login.id, p.variants.get(0).pageId, "variant re-filed by its URL");
        assertEquals(List.of(p.variants.get(0).id), login.variantIds);
        assertEquals(form.parameterIds.get(0), p.parameterTests.get(0).parameterId);

        Resource script = p.resources.stream().filter(x -> x.url.endsWith("/app.js")).findFirst().orElseThrow();
        assertEquals(List.of(home.id), script.pageIds);
        assertTrue(hasEdge(p, home.id, Relationship.LOADS_RESOURCE, script.id));
        assertTrue(hasEdge(p, home.id, Relationship.CALLS, users.id),
                "the endpoint's caller is recovered from the saved request's Referer");
        assertTrue(hasEdge(p, p.vulnerabilities.get(0).id, Relationship.AFFECTS, login.id));

        assertClean(check(dir));
    }

    @Test void redirectAndFindingRegisteredInTheWrongOrderAreConnected(@TempDir Path dir)
            throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("order");
        // The finding and the redirect target exist before the page that leads to them.
        c.createVulnerability("IDOR", Vulnerability.Severity.MEDIUM, "https://t.test/dashboard/");
        Page dashboard = c.registerPage(html("https://t.test/dashboard", "<html><body>hi</body></html>"));
        PageRegistration post = html("https://t.test/session", "");
        post.method = "POST";
        post.statusCode = 302;
        post.contentType = "";
        post.pageSource = "";
        post.responseHeaders = new ArrayList<>(List.of("Location: /dashboard"));
        Page session = c.registerPage(post);
        assertFalse(hasEdge(c.project(), session.id, Relationship.REDIRECTS_TO, dashboard.id));

        RepairReport r = repair(dir, false);
        Project p = load(dir);
        assertTrue(hasEdge(p, session.id, Relationship.REDIRECTS_TO, dashboard.id), r.render(true));
        assertEquals(DiscoverySource.HTTP_REDIRECT, page(p, "https://t.test/dashboard").discoverySourceKind);
        assertTrue(hasEdge(p, p.vulnerabilities.get(0).id, Relationship.AFFECTS, dashboard.id),
                "the finding's URL differs only by a trailing slash");
        assertClean(check(dir));
    }

    @Test void linksResolveAcrossHarmlessUrlDifferences(@TempDir Path dir) throws IOException {
        // The live controller and the repair share one lookup, so this holds without a repair.
        NotebookController c = new NotebookController(dir);
        c.create("links");
        PageRegistration home = html("https://t.test/", "<html><body></body></html>");
        for (String target : List.of("https://T.test:443/about/#team", "https://t.test/users/42")) {
            DiscoveredPage.DiscoveredLink l = new DiscoveredPage.DiscoveredLink();
            l.url = target;
            l.elementType = "anchor";
            home.discovered.links.add(l);
        }
        c.registerPage(home);
        Page about = c.registerPage(html("https://t.test/about", "<html><body>a</body></html>"));
        PageRegistration user = json("https://t.test/users/17", "{\"id\":17}");
        user.kind = Page.Kind.API;
        c.capture(user);          // auto-capture files it as /users/{id}
        c.flushCapture();
        c.saveAndGenerate();

        Project p = c.project();
        assertEquals(about.id, p.links.get(0).destinationPageId, "port, case, slash and fragment ignored");
        assertEquals(page(p, "https://t.test/users/17").id, p.links.get(1).destinationPageId,
                "a link to another id leads to the page that documents every id");
        assertClean(check(dir));
    }

    // ---- dangling references -------------------------------------------------

    @Test void danglingReferencesAreClearedButNotesAreKept(@TempDir Path dir) throws IOException {
        healthy(dir);
        damage(dir, p -> {
            Page home = page(p, "https://t.test/");
            home.formIds.add("form-9999");
            home.resourceIds.add(home.resourceIds.get(0));       // listed twice
            p.resources.get(0).pageIds.add("page-9999");
            p.links.get(0).destinationPageId = "page-9999";
            p.screenshots.get(0).relatedInteractionId = "act-9999";
            p.relationships.add(new Relationship(EntityType.PAGE, home.id,
                    Relationship.LINKS_TO, EntityType.PAGE, "page-9999"));
            p.relationships.add(p.relationships.get(0));          // duplicate edge
            Note lost = new Note();
            lost.id = p.nextId(EntityType.NOTE);
            lost.targetType = EntityType.FORM;
            lost.targetId = "form-9999";
            lost.text = "check the hidden field";
            p.notes.add(lost);
        });

        RepairReport r = repair(dir, false);
        assertEquals(0, r.attentionCount(), r.render(true));
        Project p = load(dir);
        Page home = page(p, "https://t.test/");
        assertFalse(home.formIds.contains("form-9999"));
        assertEquals(home.resourceIds.size(), home.resourceIds.stream().distinct().count());
        assertFalse(p.resources.get(0).pageIds.contains("page-9999"));
        assertNull(p.screenshots.get(0).relatedInteractionId);
        assertTrue(p.relationships.stream().noneMatch(e -> "page-9999".equals(e.toId)));
        assertEquals(p.relationships.size(), p.relationships.stream().distinct().count());

        Note kept = p.notes.stream().filter(n -> n.text.contains("check the hidden field"))
                .findFirst().orElseThrow();
        assertEquals(EntityType.PROJECT, kept.targetType, "the note survives at project level");
        assertTrue(kept.text.contains("form-9999"), "and says what it was about");
        assertClean(check(dir));
    }

    @Test void unreachableRecordsAreReportedAndOnlyRemovedOnRequest(@TempDir Path dir)
            throws IOException {
        healthy(dir);
        damage(dir, p -> {
            // The login page is gone; its form (with a tested parameter) and links remain.
            Page login = page(p, "https://t.test/login");
            p.pages.remove(login);
            // An untested, undescribed form on a page that never existed.
            Form stray = new Form();
            stray.id = p.nextId(EntityType.FORM);
            stray.pageId = "page-7777";
            Parameter sp = new Parameter();
            sp.id = p.nextId(EntityType.PARAMETER);
            sp.name = "q";
            stray.parameterIds.add(sp.id);
            p.forms.add(stray);
            p.parameters.add(sp);
        });

        RepairReport dry = check(dir);
        assertTrue(dry.attentionCount() > 0, "orphans need a decision, so they are not auto-fixed");

        RepairReport r = repair(dir, true);
        Project p = load(dir);
        assertTrue(p.forms.stream().noneMatch(f -> "page-7777".equals(f.pageId)),
                "the form nobody described or tested is pruned");
        assertTrue(p.parameters.stream().noneMatch(x -> x.name.equals("q")));
        assertTrue(p.forms.stream().anyMatch(f -> f.formIdentifier.equals("login")),
                "a form whose parameter was tested is the tester's work and is kept");
        assertTrue(r.attentionCount() > 0, "and it is still reported");
        assertTrue(r.brokenLinks.isEmpty(), r.render(true));
        assertTrue(p.parameterTests.get(0).pageId == null, "the test no longer names the deleted page");
    }

    // ---- resources -----------------------------------------------------------

    @Test void resourcesAreNormalizedMergedAndRetyped(@TempDir Path dir) throws IOException {
        healthy(dir);
        damage(dir, p -> {
            Page login = page(p, "https://t.test/login");
            Resource dup = new Resource();                 // the same script, recorded again
            dup.id = p.nextId(EntityType.RESOURCE);
            dup.url = "https://T.test:443/app.js?v=2#x";
            dup.type = Resource.Type.OTHER;
            dup.pageIds.add(login.id);
            dup.notes = "minified";
            p.resources.add(dup);
            login.resourceIds.add(dup.id);
            JsFinding again = new JsFinding();             // same finding on the duplicate
            again.id = p.nextId(EntityType.JS_FINDING);
            again.kind = JsFinding.Kind.ENDPOINT;
            again.value = "/api/secret";
            again.resourceId = dup.id;
            p.jsFindings.add(again);
            p.resources.stream().filter(x -> x.url.endsWith("/site.css")).findFirst()
                    .orElseThrow().type = Resource.Type.IMAGE;   // filed under the wrong type
        });
        // Documents for the damaged state, so the duplicate has a document to go stale.
        Project damaged = load(dir);
        new HtmlGenerator(damaged, new ProjectStore(dir).layout()).generateAll();
        String dupId = damaged.resources.get(damaged.resources.size() - 1).id;
        assertTrue(Files.exists(dir.resolve("files").resolve(dupId + ".html")));

        RepairReport r = repair(dir, false);
        assertEquals(0, r.attentionCount(), r.render(true));
        Project p = load(dir);
        List<Resource> scripts = p.resources.stream().filter(x -> x.url.endsWith("/app.js")).toList();
        assertEquals(1, scripts.size(), "one file, one record");
        Resource script = scripts.get(0);
        assertEquals(Resource.Type.SCRIPT, script.type);
        assertEquals(2, script.pageIds.size(), "both pages load it");
        assertTrue(script.notes.contains("minified"));
        assertTrue(script.observedUrls.contains("https://T.test:443/app.js?v=2#x"));
        assertEquals(1, p.jsFindings.size(), "the repeated finding collapsed");
        assertEquals(script.id, p.jsFindings.get(0).resourceId);
        assertFalse(page(p, "https://t.test/login").resourceIds.contains(dupId));

        Resource css = p.resources.stream().filter(x -> x.url.endsWith("/site.css")).findFirst().orElseThrow();
        assertEquals(Resource.Type.STYLESHEET, css.type);
        assertTrue(Files.exists(dir.resolve("scripts").resolve(css.id + ".html")));
        assertFalse(Files.exists(dir.resolve("files").resolve(css.id + ".html")),
                "its document moved with its type");
        assertFalse(Files.exists(dir.resolve("files").resolve(dupId + ".html")),
                "the merged record's document is gone");
        assertTrue(r.brokenLinks.isEmpty(), r.render(true));
        assertClean(check(dir));
    }

    /** The "register it as a page, a form and a resource to be safe" project. */
    private static NotebookController tripleRegistered(Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("triple");
        Page shell = c.registerPage(html("https://t.test/", "<html><body><div id='app'></div></body></html>"));
        c.registerPage(json("https://t.test/api/orders", "{\"orders\":[{\"id\":1,\"total\":9}]}"));
        Resource asResource = c.addResource("https://t.test/api/orders", Resource.Type.API,
                "needs the session cookie", shell.id);
        c.addNote(EntityType.RESOURCE, asResource.id, Note.Kind.TODO, "try another user's order id");
        c.saveAndGenerate();
        return c;
    }

    @Test void endpointRegisteredAsPageAndResourceBecomesOneEndpoint(@TempDir Path dir)
            throws IOException {
        NotebookController c = tripleRegistered(dir);
        String resourceId = c.project().resources.get(0).id;
        assertTrue(Files.exists(dir.resolve("scripts").resolve(resourceId + ".html")));

        RepairReport r = repair(dir, false);
        assertEquals(0, r.attentionCount(), r.render(true));
        Project p = load(dir);
        Page shell = page(p, "https://t.test/");
        Page orders = page(p, "https://t.test/api/orders");

        assertEquals(Page.Kind.API, orders.kind);
        assertTrue(p.resources.isEmpty(), "the duplicate resource is folded into the endpoint");
        assertTrue(shell.resourceIds.isEmpty());
        assertTrue(hasEdge(p, shell.id, Relationship.CALLS, orders.id), "its loader is now its caller");
        assertTrue(orders.notes.contains("needs the session cookie"));
        Note todo = p.notes.get(0);
        assertEquals(EntityType.PAGE, todo.targetType);
        assertEquals(orders.id, todo.targetId);
        assertEquals(List.of("orders", "orders[]", "orders[].id", "orders[].total"), orders.responseFields);
        assertFalse(Files.exists(dir.resolve("scripts").resolve(resourceId + ".html")));

        String doc = Files.readString(dir.resolve("pages").resolve(orders.id + ".html"));
        assertTrue(doc.contains("API ENDPOINT // " + orders.id));
        assertTrue(doc.contains("CALLED BY"));
        assertTrue(doc.contains("try another user&#39;s order id"));
        assertTrue(r.brokenLinks.isEmpty(), r.render(true));
        assertClean(check(dir));
    }

    @Test void reclassificationCanBeSwitchedOff(@TempDir Path dir) throws IOException {
        tripleRegistered(dir);
        ProjectRepair.Options o = new ProjectRepair.Options();
        o.apply = true;
        o.reclassify = false;
        new ProjectRepair(dir, o).run();
        Project p = load(dir);
        assertEquals(Page.Kind.PAGE, page(p, "https://t.test/api/orders").kind);
        assertEquals(1, p.resources.size());
    }

    // ---- files and documents ---------------------------------------------------

    @Test void movedMissingAndStrayFilesAreHandled(@TempDir Path dir) throws IOException {
        healthy(dir);
        Project before = load(dir);
        Page home = page(before, "https://t.test/");
        Page login = page(before, "https://t.test/login");
        String homeSource = home.sourceFiles.get(0);
        String variantFile = before.variants.get(0).sourceFile;
        String image = before.screenshots.get(0).imageFile;
        String formDoc = "forms/" + before.forms.get(0).id + ".html";

        Files.delete(dir.resolve(homeSource));                               // evidence lost
        Files.delete(dir.resolve(formDoc));                                  // document lost
        Files.writeString(dir.resolve("source").resolve("stray.txt"), "left behind");
        Files.writeString(dir.resolve("pages").resolve("page-9999.html"), "<html></html>");
        Files.writeString(dir.resolve("pages").resolve("my-own-notes.html"), "<html></html>");
        damage(dir, p -> {
            // As if the project had been copied over from a Windows machine.
            p.variants.get(0).sourceFile = "C:\\work\\acme\\" + variantFile.replace('/', '\\');
            p.screenshots.get(0).imageFile = "./" + image;
        });

        RepairReport dry = check(dir);
        assertEquals(1, dry.attentionCount(), dry.render(true));   // the stray file

        RepairReport r = repair(dir, true);
        assertEquals(0, r.attentionCount(), r.render(true));
        Project p = load(dir);
        assertFalse(page(p, "https://t.test/").sourceFiles.contains(homeSource),
                "a reference to a file that is gone is dropped");
        assertNull(page(p, "https://t.test/").baseline.sourceFile);
        assertEquals(variantFile, p.variants.get(0).sourceFile, "found again by name");
        assertEquals(image, p.screenshots.get(0).imageFile, "path normalized");
        assertEquals(login.sourceFiles, page(p, "https://t.test/login").sourceFiles);

        assertFalse(Files.exists(dir.resolve("source").resolve("stray.txt")));
        assertEquals("left behind", Files.readString(
                dir.resolve("_orphaned").resolve("source").resolve("stray.txt")),
                "an unreferenced file is moved aside, never deleted");
        assertFalse(Files.exists(dir.resolve("pages").resolve("page-9999.html")));
        assertTrue(Files.exists(dir.resolve("pages").resolve("my-own-notes.html")),
                "only documents the tool generates are ever removed");
        assertTrue(Files.exists(dir.resolve(formDoc)), "missing document regenerated");
        assertTrue(r.brokenLinks.isEmpty(), r.render(true));
        assertClean(check(dir));
    }

    @Test void screenshotWithoutItsImageNeedsAttention(@TempDir Path dir) throws IOException {
        healthy(dir);
        Files.delete(dir.resolve(load(dir).screenshots.get(0).imageFile));

        RepairReport kept = repair(dir, false);
        assertEquals(1, kept.attentionCount(), kept.render(true));
        assertEquals(1, load(dir).screenshots.size(), "the record (and its description) is kept");

        repair(dir, true);
        Project p = load(dir);
        assertTrue(p.screenshots.isEmpty());
        assertTrue(page(p, "https://t.test/").screenshotIds.isEmpty());
        assertClean(check(dir));
    }

    // ---- model structure -----------------------------------------------------

    @Test void nullFieldsCountersAndDuplicateIdsAreRepaired(@TempDir Path dir) throws IOException {
        healthy(dir);
        // Edit the JSON text itself: these states cannot be produced through the model classes.
        Path file = dir.resolve("project.json");
        JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject login = json.getAsJsonArray("pages").get(1).getAsJsonObject();
        login.add("formIds", JsonNull.INSTANCE);
        login.add("notes", JsonNull.INSTANCE);
        json.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("type", "NOT_A_TYPE");
        json.remove("capture");
        json.getAsJsonObject("idCounters").addProperty("page", 1);
        // A second page that reuses an existing id.
        JsonObject copy = json.getAsJsonArray("pages").get(0).getAsJsonObject().deepCopy();
        copy.addProperty("url", "https://t.test/copy");
        copy.add("formIds", JsonNull.INSTANCE);
        copy.add("linkIds", JsonNull.INSTANCE);
        copy.add("resourceIds", JsonNull.INSTANCE);
        copy.add("screenshotIds", JsonNull.INSTANCE);
        copy.add("sourceFiles", JsonNull.INSTANCE);
        copy.add("baseline", JsonNull.INSTANCE);
        json.getAsJsonArray("pages").add(copy);
        Gson gson = new GsonBuilder().serializeNulls().create();
        Files.writeString(file, gson.toJson(json));

        RepairReport r = repair(dir, false);
        assertEquals(0, r.attentionCount(), r.render(true));
        Project p = load(dir);
        Page loginPage = page(p, "https://t.test/login");
        assertEquals(List.of(p.forms.get(0).id), loginPage.formIds,
                "the emptied list is rebuilt from the form's own page reference");
        assertEquals("", loginPage.notes);
        assertNotNull(p.capture);
        assertNotNull(p.resources.get(0).type, "an unknown type falls back to a valid one");
        assertEquals(p.pages.size(), p.pages.stream().map(x -> x.id).distinct().count());
        assertTrue(p.idCounters.get("page") >= p.pages.size());

        // The repaired project works again: the next registration gets a fresh id.
        NotebookController c = new NotebookController(dir);
        c.open();
        Page fresh = c.registerPage(html("https://t.test/new", "<html><body>n</body></html>"));
        assertEquals(1, c.project().pages.stream().filter(x -> x.id.equals(fresh.id)).count());
        assertClean(check(dir));
    }

    @Test void interruptedSaveIsRecovered(@TempDir Path dir) throws IOException {
        healthy(dir);
        Path json = dir.resolve("project.json");
        Path tmp = dir.resolve("project.json.tmp");
        Files.copy(json, tmp);
        Files.writeString(json, "{\"name\": \"repair\", \"pages\": [ {\"id\": \"page-00");  // cut off

        RepairReport dry = check(dir);
        assertNull(dry.fatal);
        assertEquals(1, dry.count("Project file recovered from an interrupted save"));

        RepairReport r = repair(dir, false);
        assertNull(r.fatal);
        assertEquals(3, load(dir).pages.size(), "project.json is whole again");
        assertNotNull(r.backup);
        assertTrue(r.backup.getFileName().toString().startsWith("project.json.broken-"),
                "the unreadable file is kept for inspection");
        assertClean(check(dir));
    }

    @Test void leftoverTempFileIsSetAsideNotTrusted(@TempDir Path dir) throws IOException {
        healthy(dir);
        Files.copy(dir.resolve("project.json"), dir.resolve("project.json.tmp"));
        repair(dir, false);
        assertFalse(Files.exists(dir.resolve("project.json.tmp")));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.filter(f -> f.getFileName().toString()
                    .startsWith("project.json.interrupted-")).count());
        }
        assertClean(check(dir));
    }

    @Test void unreadableProjectIsRefusedAndLeftUntouched(@TempDir Path dir) throws IOException {
        healthy(dir);
        Files.writeString(dir.resolve("project.json"), "not json at all {{{");
        Map<String, String> before = snapshot(dir);

        RepairReport r = repair(dir, true);
        assertNotNull(r.fatal);
        assertFalse(r.applied);
        assertEquals(before, snapshot(dir));
    }

    // ---- command line ----------------------------------------------------------

    private static int tool(StringBuilder output, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8);
        int status = RepairTool.run(args, stream, stream);
        output.append(out.toString(StandardCharsets.UTF_8));
        return status;
    }

    @Test void commandLineExitStatusFollowsTheResult(@TempDir Path dir) throws IOException {
        healthy(dir);
        StringBuilder out = new StringBuilder();
        assertEquals(0, tool(out, dir.toString()), "consistent project");
        assertTrue(out.toString().contains("Nothing to repair"));

        damage(dir, p -> p.relationships.clear());
        out.setLength(0);
        assertEquals(1, tool(out, dir.toString()), "a dry run that finds problems");
        assertTrue(out.toString().contains("dry run"));
        assertTrue(out.toString().contains("--apply"));

        out.setLength(0);
        assertEquals(0, tool(out, dir.toString(), "--apply", "-v"), "everything was fixable");
        assertTrue(out.toString().contains("[fixed]"));
        assertTrue(out.toString().contains("project.json.bak-"));
        assertEquals(0, tool(new StringBuilder(), dir.toString()));

        assertEquals(2, tool(new StringBuilder()), "no directory");
        assertEquals(2, tool(new StringBuilder(), dir.toString(), "--frobnicate"));
        assertEquals(2, tool(new StringBuilder(), dir.resolve("nowhere").toString()));
        out.setLength(0);
        assertEquals(0, tool(out, "--help"));
        assertTrue(out.toString().contains("--prune"));
    }
}
