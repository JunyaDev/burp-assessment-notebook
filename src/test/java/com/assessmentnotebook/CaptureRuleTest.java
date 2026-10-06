package com.assessmentnotebook;

import com.assessmentnotebook.analyze.RuleMatcher;
import com.assessmentnotebook.analyze.TrafficClassifier;
import com.assessmentnotebook.analyze.UrlTemplates;
import com.assessmentnotebook.model.CaptureRule;
import com.assessmentnotebook.model.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Rule matching, traffic classification and URL identity: the Burp-independent half of auto-capture. */
class CaptureRuleTest {

    private static RuleMatcher.Facts facts(String method, String url, int status, String ct) {
        RuleMatcher.Facts f = new RuleMatcher.Facts();
        f.method = method;
        f.url = url;
        f.status = status;
        f.contentType = ct;
        return f;
    }

    private static CaptureRule rule(String host, String path, CaptureRule.Action action) {
        CaptureRule r = new CaptureRule();
        r.host = host;
        r.path = path;
        r.action = action;
        return r;
    }

    @Test void emptyRuleMatchesAnythingFromItsTool() {
        CaptureRule any = new CaptureRule();
        RuleMatcher.Facts f = facts("GET", "https://app.test/x", 200, "text/html");
        assertTrue(RuleMatcher.matches(any, f));

        f.fromProxy = false;
        f.fromRepeater = true;
        assertFalse(RuleMatcher.matches(any, f), "rules listen to the proxy only by default");
        any.fromRepeater = true;
        assertTrue(RuleMatcher.matches(any, f));
    }

    @Test void firstEnabledMatchWinsSoIgnoreRulesCarveExceptions() {
        CaptureRule ignoreHealth = rule("", "/api/health*", CaptureRule.Action.IGNORE);
        CaptureRule api = rule("*.app.test", "/api/*", CaptureRule.Action.API);
        CaptureRule rest = rule("*.app.test", "", CaptureRule.Action.AUTO);
        List<CaptureRule> rules = List.of(ignoreHealth, api, rest);

        assertSame(ignoreHealth, RuleMatcher.firstMatch(rules,
                facts("GET", "https://api.app.test/api/healthz", 200, "application/json")));
        assertSame(api, RuleMatcher.firstMatch(rules,
                facts("GET", "https://API.app.test/api/users?id=1", 200, "application/json")));
        assertSame(rest, RuleMatcher.firstMatch(rules,
                facts("GET", "https://www.app.test/about", 200, "text/html")));
        assertNull(RuleMatcher.firstMatch(rules,
                facts("GET", "https://cdn.other.test/api/users", 200, "application/json")));

        ignoreHealth.enabled = false;
        assertSame(api, RuleMatcher.firstMatch(rules,
                facts("GET", "https://api.app.test/api/healthz", 200, "application/json")),
                "a disabled rule is skipped");
    }

    @Test void conditionsOnMethodStatusContentTypeAndRegex() {
        CaptureRule r = new CaptureRule();
        r.methods = "post, put";
        r.status = "2xx,302,400-404";
        r.contentType = "json, xml";
        r.path = "re:^/v\\d+/orders(\\?|$)";

        assertTrue(RuleMatcher.matches(r, facts("POST", "https://a.test/v2/orders?x=1", 201,
                "application/json; charset=utf-8")));
        assertTrue(RuleMatcher.matches(r, facts("PUT", "https://a.test/v1/orders", 403,
                "text/xml")));
        assertFalse(RuleMatcher.matches(r, facts("GET", "https://a.test/v2/orders", 200,
                "application/json")), "method");
        assertFalse(RuleMatcher.matches(r, facts("POST", "https://a.test/v2/orders", 500,
                "application/json")), "status");
        assertFalse(RuleMatcher.matches(r, facts("POST", "https://a.test/v2/orders", 200,
                "text/html")), "content type");
        assertFalse(RuleMatcher.matches(r, facts("POST", "https://a.test/v2/orders/7", 200,
                "application/json")), "regex");
    }

    @Test void scopeIsConsultedOnlyWhenTheRuleAsksAndEverythingElseMatched() {
        int[] asked = {0};
        RuleMatcher.Facts f = facts("GET", "https://a.test/x", 200, "text/html");
        f.inScope = () -> { asked[0]++; return false; };

        assertTrue(RuleMatcher.matches(new CaptureRule(), f));
        CaptureRule scoped = new CaptureRule();
        scoped.inScopeOnly = true;
        scoped.methods = "POST";
        assertFalse(RuleMatcher.matches(scoped, f));
        assertEquals(0, asked[0], "scope must not be queried for traffic that fails cheaper tests");
        scoped.methods = "";
        assertFalse(RuleMatcher.matches(scoped, f));
        assertEquals(1, asked[0]);
    }

    @Test void validateRejectsUnusableRules() {
        CaptureRule r = new CaptureRule();
        assertNull(RuleMatcher.validate(r));
        r.path = "re:([";
        assertNotNull(RuleMatcher.validate(r));
        // A broken regex never matches rather than throwing on the HTTP thread.
        assertFalse(RuleMatcher.matches(r, facts("GET", "https://a.test/(", 200, "")));
        r.path = "";
        r.status = "2xx,ok";
        assertNotNull(RuleMatcher.validate(r));
        r.status = "";
        r.fromProxy = false;
        assertNotNull(RuleMatcher.validate(r));
    }

    @Test void urlsThatJavaUriRejectsStillMatch() {
        CaptureRule r = rule("a.test", "/search", CaptureRule.Action.AUTO);
        assertTrue(RuleMatcher.matches(r,
                facts("GET", "https://a.test:8443/search?q={a|b} c", 200, "text/html")));
    }

    // ---- classification --------------------------------------------------

    private static TrafficClassifier.Kind kind(String method, String url, int status, String ct,
            String... headers) {
        return TrafficClassifier.classify(method, url, List.of(headers), status, ct).kind;
    }

    @Test void classifiesEachExchangeAsExactlyOneKind() {
        assertEquals(TrafficClassifier.Kind.PAGE,
                kind("GET", "https://a.test/login", 200, "text/html; charset=utf-8"));
        assertEquals(TrafficClassifier.Kind.API,
                kind("GET", "https://a.test/api/users", 200, "application/json"));
        assertEquals(TrafficClassifier.Kind.API,
                kind("POST", "https://a.test/graphql", 200, "application/graphql-response+json"));
        // No body or content type, but the request was a fetch with a JSON body.
        assertEquals(TrafficClassifier.Kind.API,
                kind("DELETE", "https://a.test/api/users/7", 204, "", "Sec-Fetch-Dest: empty"));
        assertEquals(TrafficClassifier.Kind.API,
                kind("POST", "https://a.test/track", 200, "text/plain",
                        "Content-Type: application/json"));
        // A navigation that redirects is part of the page flow.
        assertEquals(TrafficClassifier.Kind.PAGE,
                kind("POST", "https://a.test/login", 302, "", "Sec-Fetch-Dest: document"));
        assertEquals(TrafficClassifier.Kind.SKIP,
                kind("GET", "https://a.test/api/users", 304, ""));
        assertEquals(TrafficClassifier.Kind.SKIP,
                kind("OPTIONS", "https://a.test/api/users", 204, "",
                        "Access-Control-Request-Method: POST"), "CORS preflight");
    }

    @Test void staticAssetsAreResourcesWithTheirType() {
        TrafficClassifier.Result js = TrafficClassifier.classify("GET",
                "https://a.test/main.dart.js?v=3", null, 200, "application/javascript");
        assertEquals(TrafficClassifier.Kind.RESOURCE, js.kind);
        assertEquals(Resource.Type.SCRIPT, js.resourceType);

        TrafficClassifier.Result svg = TrafficClassifier.classify("GET",
                "https://a.test/logo", null, 200, "image/svg+xml");
        assertEquals(Resource.Type.IMAGE, svg.resourceType, "svg+xml is an image, not an API");

        TrafficClassifier.Result font = TrafficClassifier.classify("GET",
                "https://a.test/assets/fonts/MaterialIcons-Regular.otf", null, 304, "");
        assertEquals(TrafficClassifier.Kind.RESOURCE, font.kind, "a cached asset is still identified");
        assertEquals(Resource.Type.FONT, font.resourceType);

        TrafficClassifier.Result pdf = TrafficClassifier.classify("GET",
                "https://a.test/files/report", null, 200, "application/pdf");
        assertEquals(TrafficClassifier.Kind.RESOURCE, pdf.kind);
        assertEquals(Resource.Type.OTHER, pdf.resourceType);
    }

    // ---- identity --------------------------------------------------------

    @Test void pageIdentityIgnoresQueryAndCollapsesIds() {
        String a = UrlTemplates.key("https://A.test:443/api/users/17?full=1", true);
        String b = UrlTemplates.key("https://a.test/api/users/42/", true);
        assertEquals("https://a.test/api/users/{id}", a);
        assertEquals(a, b);
        assertEquals(UrlTemplates.key("https://a.test/o/3f2b8c1e-9a4d-4c7b-8f1e-2d3c4b5a6978", true),
                UrlTemplates.key("https://a.test/o/0b9d7c66-1111-4222-8333-944455556666", true));

        assertNotEquals(UrlTemplates.key("https://a.test/api/users/17", false),
                UrlTemplates.key("https://a.test/api/users/42", false));
        assertNotEquals(UrlTemplates.key("https://a.test/api/users/me", true), a,
                "a word is a different endpoint, not an id");
        assertNotEquals(UrlTemplates.key("http://a.test:8080/x", true),
                UrlTemplates.key("http://a.test/x", true));

        assertEquals("/api/users/{id}", UrlTemplates.templatePath("https://a.test/api/users/17"));
        assertEquals("", UrlTemplates.templatePath("https://a.test/api/users"));
        assertEquals("/", UrlTemplates.path("https://a.test"));
        assertEquals("a.test", UrlTemplates.host("https://user@A.test:8443/x"));
        assertEquals("q=1&r=2", UrlTemplates.query("https://a.test/x?q=1&r=2#frag"));
    }
}
