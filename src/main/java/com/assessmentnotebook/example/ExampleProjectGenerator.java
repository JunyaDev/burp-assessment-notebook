package com.assessmentnotebook.example;

import com.assessmentnotebook.analyze.HtmlAnalyzer;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.core.PageRegistration;
import com.assessmentnotebook.model.*;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds a small but complete sample project by driving the real
 * {@link NotebookController}, so the shipped example is guaranteed to match
 * what the extension actually produces. Run with an output directory argument
 * (default {@code ./example-project}).
 *
 * <p>The traffic here is invented for illustration; nothing is fetched.
 */
public final class ExampleProjectGenerator {

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "example-project");
        NotebookController c = new NotebookController(out);
        c.create("Acme Portal — sample assessment");

        Project p = c.project();
        p.target.domain = "portal.acme.test";
        p.target.mainUrl = "https://portal.acme.test/";
        p.target.assessmentName = "Acme Portal web application assessment";
        p.target.assessmentDate = "2026-09-23";
        p.target.notes = "Scope: portal.acme.test only. Authorized, credentialed testing.";

        c.addTechnology(Technology.Category.WEB_SERVER, "nginx", "1.25", "Server response header", "");
        c.addTechnology(Technology.Category.LANGUAGE, "PHP", "8.2", "X-Powered-By header", "");
        c.addTechnology(Technology.Category.FRAMEWORK, "Laravel", "10", "XSRF-TOKEN cookie", "");
        c.addTechnology(Technology.Category.JS_FRAMEWORK, "Vue.js", "3", "app.js bundle", "");
        c.addTechnology(Technology.Category.DATABASE, "MySQL", "", "error message on /search", "");
        c.addTechnology(Technology.Category.AUTHENTICATION, "Session cookie + CSRF token", "",
                "login form + Set-Cookie", "");

        HtmlAnalyzer analyzer = new HtmlAnalyzer();

        // ---- home page ---------------------------------------------------
        String homeHtml = "<html><head><title>Acme Portal</title>"
                + "<link rel=stylesheet href=/css/app.css>"
                + "<script src=/js/app.js></script></head><body>"
                + "<nav><a href=/login>Sign in</a><a href=/products>Products</a></nav>"
                + "<h1>Welcome to Acme Portal</h1></body></html>";
        Page home = register(c, analyzer, "https://portal.acme.test/", 200,
                "direct navigation", homeHtml, null);

        // ---- login page --------------------------------------------------
        String loginHtml = "<html><head><title>Sign in</title></head><body>"
                + "<form id=loginForm action=/session method=post>"
                + "<input name=email type=email required>"
                + "<input name=password type=password required>"
                + "<input name=_token type=hidden value=CSRF>"
                + "</form></body></html>";
        Page login = register(c, analyzer, "https://portal.acme.test/login", 200,
                "linked from home", loginHtml, home.id);

        // ---- dashboard (reached via redirect after login) ----------------
        String dashHtml = "<html><head><title>Dashboard</title>"
                + "<script src=/js/app.js></script></head><body>"
                + "<nav><a href=/dashboard/profile>Profile</a>"
                + "<a href=/dashboard/settings>Settings</a>"
                + "<a href=/admin>Admin</a></nav>"
                + "<form id=searchForm action=/search method=get>"
                + "<input name=q type=text></form></body></html>";
        Page dash = register(c, analyzer, "https://portal.acme.test/dashboard", 200,
                "302 redirect from /session", dashHtml, login.id);
        c.relate(EntityType.PAGE, login.id, Relationship.REDIRECTS_TO,
                EntityType.PAGE, dash.id, "302 after successful auth");

        register(c, analyzer, "https://portal.acme.test/dashboard/profile", 200,
                "linked from dashboard", "<html><head><title>Profile</title>"
                + "<script src=/js/app.js></script></head>"
                + "<body><form action=/profile method=post>"
                + "<input name=display_name type=text value=juniper></form></body></html>", dash.id);
        register(c, analyzer, "https://portal.acme.test/search", 200,
                "search form submission",
                "<html><head><title>Search</title></head><body>"
                + "<p>Results for <b>test</b></p></body></html>", dash.id);
        register(c, analyzer, "https://portal.acme.test/api/users", 200,
                "observed XHR from dashboard",
                "{\"users\":[{\"id\":1,\"email\":\"a@acme.test\"}]}", dash.id);

        // ---- an interaction and stepped screenshots ----------------------
        Interaction act = c.addInteraction(dash.id, "Clicked the account dropdown",
                "A menu with Profile / Settings / Admin appeared", null);
        c.addScreenshot(dash.id, screenshot("DASHBOARD", "default state", new Color(0x13, 0x19, 0x16)),
                "Default page", 0, null);
        c.addScreenshot(dash.id, screenshot("DASHBOARD", "account menu open", new Color(0x0d, 0x12, 0x10)),
                "Account menu opened", 1, act.id);

        // ---- a reflection observation on the search parameter ------------
        Parameter q = c.project().parameters.stream()
                .filter(x -> x.name.equals("q")).findFirst().orElse(null);
        if (q != null) {
            List<Reflection> refl = c.detectReflections(
                    "<html><body><p>Results for <b>ZZQCANARY</b></p></body></html>",
                    List.of("Content-Type: text/html"), "text/html", "ZZQCANARY");
            c.attachReflections(q.id, refl);
            c.addNote(EntityType.PARAMETER, q.id, Note.Kind.HYPOTHESIS,
                    "q reflects unencoded into HTML text — probe for XSS.");
        }

        // ---- notes across kinds -----------------------------------------
        c.addNote(EntityType.PAGE, login.id, Note.Kind.OBSERVATION,
                "Login sets a session cookie without the Secure flag over the test TLS endpoint.");
        c.addNote(EntityType.PAGE, dash.id, Note.Kind.TODO,
                "Check whether /admin is reachable without the admin role.");

        // ---- findings, wired to the components they involve --------------
        Vulnerability xss = c.createVulnerability(
                "Reflected XSS via search parameter q", Vulnerability.Severity.HIGH,
                "https://portal.acme.test/search");
        xss.affectedComponent = "search form, parameter q";
        xss.description = "The q parameter is reflected into the HTML response without "
                + "output encoding, allowing script injection.";
        xss.technicalObservation = "A canary value appeared verbatim in HTML text context.";
        xss.stepsToReproduce = "1. Submit q=<script>alert(1)</script> on /search.\n"
                + "2. Observe the script executes in the response.";
        xss.impact = "Session theft, actions performed as the victim.";
        xss.remediation = "Context-aware output encoding; a strict Content-Security-Policy.";
        xss.status = Vulnerability.Status.CONFIRMED;
        if (q != null) {
            c.relate(EntityType.VULNERABILITY, xss.id, Relationship.AFFECTS,
                    EntityType.PARAMETER, q.id, "reflected parameter");
        }

        Vulnerability cookie = c.createVulnerability(
                "Session cookie missing Secure attribute", Vulnerability.Severity.LOW,
                "https://portal.acme.test/login");
        cookie.description = "The session cookie is issued without the Secure attribute.";
        cookie.remediation = "Set the Secure and HttpOnly attributes on session cookies.";

        // ---- page variants of /search, and their differences (spec §12, §13) --
        c.registerVariant(searchVariant("apple", "3"),
                searchBody("apple", 3));
        c.registerVariant(searchVariant("banana", "5"),
                searchBody("banana", 5));
        c.registerVariant(searchVariant("admin", "0"),
                "<html><head><title>Search</title></head><body>"
                + "<p>No results for <b>admin</b></p><p class=note>Access denied</p></body></html>");

        // ---- interesting strings for wordlists (spec §8) -----------------
        c.addInterestingString("admin", InterestingString.Category.USERNAMES,
                dash.id, "nav link /admin", "<a href=/admin>Admin</a>", "");
        c.addInterestingString("administrator", InterestingString.Category.USERNAMES, null, "", "", "");
        c.addInterestingString("/dashboard/settings", InterestingString.Category.DIRECTORIES,
                dash.id, "nav link", "", "");
        c.addInterestingString("_token", InterestingString.Category.PARAMETERS,
                login.id, "hidden input", "", "CSRF token field name");
        c.addInterestingString("XSRF-TOKEN", InterestingString.Category.TECHNOLOGY_SPECIFIC,
                null, "cookie", "", "Laravel CSRF cookie");
        c.exportWordlists();

        // ---- an annotated screenshot (spec §9) ---------------------------
        Screenshot annotated = c.addScreenshot(dash.id,
                screenshot("DASHBOARD", "admin link visible", new Color(0x0d, 0x12, 0x10)),
                "Admin link visible to a standard user", 2, null);
        c.addAnnotation(annotated.id, 40, 30, 220, 40,
                "Admin link", "Shown to a non-admin session — verify access control", null);

        c.saveAndGenerate();
        System.out.println("Example project written to " + out.toAbsolutePath());
        System.out.println("Open " + out.toAbsolutePath().resolve("index.html") + " in a browser.");
    }

    private static Page register(NotebookController c, HtmlAnalyzer analyzer, String url,
            int status, String discovery, String body, String parentId) throws IOException {
        PageRegistration reg = new PageRegistration();
        reg.url = url;
        reg.method = "GET";
        reg.statusCode = status;
        boolean json = body.startsWith("{");
        reg.contentType = json ? "application/json" : "text/html";
        if (json) reg.kind = Page.Kind.API;
        reg.discoverySource = discovery;
        reg.discoverySourceKind = kindFor(discovery);
        reg.parentPageId = parentId;
        // Realistic response headers so automatic technology detection has
        // header/cookie evidence to work from (spec §5).
        reg.responseHeaders = List.of(
                "Server: nginx/1.25.3",
                "X-Powered-By: PHP/8.2.11",
                "Set-Cookie: laravel_session=eyJ; path=/; HttpOnly",
                "Set-Cookie: XSRF-TOKEN=abc; path=/",
                "Content-Type: " + reg.contentType);
        reg.discovered = analyzer.analyze(body, url);
        reg.pageSource = body;
        return c.registerPage(reg);
    }

    private static PageVariant searchVariant(String q, String label) {
        PageVariant v = new PageVariant();
        v.url = "https://portal.acme.test/search";
        v.method = "GET";
        v.label = "q=" + q;
        v.queryParams.put("q", q);
        v.statusCode = 200;
        v.contentType = "text/html";
        return v;
    }

    private static String searchBody(String q, int rows) {
        StringBuilder li = new StringBuilder();
        for (int i = 0; i < rows; i++) li.append("<li>match ").append(i).append("</li>");
        return "<html><head><title>Search: " + q + "</title></head><body>"
                + "<p>Results for <b>" + q + "</b></p><ul>" + li + "</ul></body></html>";
    }

    private static com.assessmentnotebook.model.DiscoverySource kindFor(String detail) {
        String d = detail.toLowerCase();
        if (d.contains("redirect")) return com.assessmentnotebook.model.DiscoverySource.HTTP_REDIRECT;
        if (d.contains("linked")) return com.assessmentnotebook.model.DiscoverySource.LINK_FROM_PAGE;
        if (d.contains("form")) return com.assessmentnotebook.model.DiscoverySource.FORM_SUBMISSION;
        if (d.contains("xhr")) return com.assessmentnotebook.model.DiscoverySource.XHR_FETCH;
        return com.assessmentnotebook.model.DiscoverySource.DIRECT_NAVIGATION;
    }

    /** Render a small retro-styled placeholder PNG so the example needs no external images. */
    private static byte[] screenshot(String title, String state, Color bg) throws IOException {
        int w = 640, h = 360;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(bg);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x24, 0x30, 0x2a));
        g.drawRect(8, 8, w - 17, h - 17);
        g.setColor(new Color(0xf2, 0xb1, 0x34));
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
        g.drawString(title, 28, 56);
        g.setColor(new Color(0x6e, 0xe7, 0x87));
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        g.drawString("// " + state, 28, 92);
        for (int i = 0; i < 6; i++) {
            g.setColor(new Color(0x5f, 0x74, 0x66));
            g.drawString("> " + "█".repeat(6 + i * 3), 28, 140 + i * 28);
        }
        // faint scanlines
        g.setColor(new Color(0, 0, 0, 40));
        for (int y = 0; y < h; y += 3) g.drawLine(0, y, w, y);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
