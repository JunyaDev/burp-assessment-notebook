package com.assessmentnotebook;

import com.assessmentnotebook.analyze.DiscoveredPage;
import com.assessmentnotebook.analyze.HtmlAnalyzer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HtmlAnalyzerTest {

    private static final String HTML =
            "<html><head><title>Login</title>"
            + "<link rel=stylesheet href=/css/app.css>"
            + "<script src=/js/app.js></script></head><body>"
            + "<form id=loginForm action=/session method=post enctype=multipart/form-data>"
            + "  <input name=username type=text required>"
            + "  <input name=password type=password>"
            + "  <input name=csrf type=hidden value=abc123>"
            + "</form>"
            + "<a href=/dashboard>Dashboard</a>"
            + "<a href=\"https://cdn.example.com/x.png\">img</a>"
            + "<img src=/img/logo.png>"
            + "</body></html>";

    @Test void extractsTitleFormsInputs() {
        DiscoveredPage d = new HtmlAnalyzer().analyze(HTML, "https://site.test/login");
        assertEquals("Login", d.title);
        assertEquals(1, d.forms.size());

        DiscoveredPage.DiscoveredForm f = d.forms.get(0);
        assertEquals("POST", f.method);
        assertEquals("multipart/form-data", f.encType);
        assertEquals("loginForm", f.identifier);
        assertTrue(f.action.endsWith("/session"), "action absolutized: " + f.action);
        assertEquals(3, f.inputs.size());

        DiscoveredPage.DiscoveredInput user = f.inputs.get(0);
        assertEquals("username", user.name);
        assertEquals("text", user.type);
        assertTrue(user.required);
        assertEquals("password", f.inputs.get(1).type);
        assertEquals("hidden", f.inputs.get(2).type);
        assertEquals("abc123", f.inputs.get(2).value);
    }

    @Test void extractsLinksAndResolvesRelative() {
        DiscoveredPage d = new HtmlAnalyzer().analyze(HTML, "https://site.test/login");
        boolean dashboard = d.links.stream().anyMatch(l ->
                l.url.equals("https://site.test/dashboard") && l.elementType.equals("anchor"));
        assertTrue(dashboard, "relative anchor resolved against base");
        boolean formAction = d.links.stream().anyMatch(l -> l.elementType.equals("form-action"));
        assertTrue(formAction, "form action recorded as a navigational edge");
    }

    @Test void extractsResourcesByType() {
        DiscoveredPage d = new HtmlAnalyzer().analyze(HTML, "https://site.test/login");
        assertTrue(has(d, "SCRIPT", "/js/app.js"));
        assertTrue(has(d, "STYLESHEET", "/css/app.css"));
        assertTrue(has(d, "IMAGE", "/img/logo.png"));
    }

    @Test void emptyBodyYieldsEmptyResult() {
        DiscoveredPage d = new HtmlAnalyzer().analyze("", "https://x");
        assertTrue(d.forms.isEmpty() && d.links.isEmpty() && d.resources.isEmpty());
    }

    private static boolean has(DiscoveredPage d, String type, String suffix) {
        return d.resources.stream().anyMatch(r -> r.type.equals(type) && r.url.endsWith(suffix));
    }
}
