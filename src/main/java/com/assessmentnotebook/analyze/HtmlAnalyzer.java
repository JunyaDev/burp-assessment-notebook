package com.assessmentnotebook.analyze;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Parses a response body into a {@link DiscoveredPage} of forms, links, inputs
 * and resources. Uses jsoup so malformed real-world markup is handled the way a
 * browser would, and resolves relative URLs against the page's own URL.
 *
 * <p>This class only proposes what it found. Nothing here decides that a
 * finding is a vulnerability or registers anything; the tester reviews the
 * result first (see the Register Page workflow).
 */
public final class HtmlAnalyzer {

    /**
     * @param body    the response body (HTML)
     * @param baseUrl the page's URL, used to absolutize relative links
     */
    public DiscoveredPage analyze(String body, String baseUrl) {
        DiscoveredPage out = new DiscoveredPage();
        if (body == null || body.isBlank()) return out;

        Document doc = Jsoup.parse(body, baseUrl == null ? "" : baseUrl);
        out.title = doc.title();

        // Forms and their inputs.
        for (Element form : doc.select("form")) {
            DiscoveredPage.DiscoveredForm f = new DiscoveredPage.DiscoveredForm();
            f.action = form.hasAttr("action") ? form.absUrl("action") : baseUrl;
            f.method = form.hasAttr("method") ? form.attr("method").toUpperCase() : "GET";
            f.encType = form.attr("enctype");
            f.identifier = !form.id().isEmpty() ? form.id() : form.attr("name");
            for (Element control : form.select("input, textarea, select, button")) {
                DiscoveredPage.DiscoveredInput in = new DiscoveredPage.DiscoveredInput();
                in.name = control.attr("name");
                in.type = "input".equals(control.tagName())
                        ? (control.hasAttr("type") ? control.attr("type") : "text")
                        : control.tagName();
                in.value = control.hasAttr("value") ? control.attr("value") : "";
                in.required = control.hasAttr("required");
                if (!in.name.isEmpty() || !in.type.isEmpty()) f.inputs.add(in);
            }
            out.forms.add(f);
        }

        // Anchor links.
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href");
            if (href.isBlank() || href.startsWith("javascript:")) continue;
            DiscoveredPage.DiscoveredLink link = new DiscoveredPage.DiscoveredLink();
            link.url = href;
            link.text = a.text().trim();
            link.elementType = "anchor";
            link.method = "GET";
            out.links.add(link);
        }
        // Form actions are navigational edges too.
        for (DiscoveredPage.DiscoveredForm f : out.forms) {
            if (f.action != null && !f.action.isBlank()) {
                DiscoveredPage.DiscoveredLink link = new DiscoveredPage.DiscoveredLink();
                link.url = f.action;
                link.text = f.identifier.isEmpty() ? "(form submission)" : f.identifier;
                link.elementType = "form-action";
                link.method = f.method;
                out.links.add(link);
            }
        }

        // Resources.
        addResources(doc, "script[src]", "src", "SCRIPT", out);
        addResources(doc, "link[rel=stylesheet][href]", "href", "STYLESHEET", out);
        addResources(doc, "img[src]", "src", "IMAGE", out);
        addResources(doc, "source[src]", "src", "IMAGE", out);
        // Fonts declared via <link rel=preload as=font> or link rel that mentions font.
        for (Element l : doc.select("link[href]")) {
            String rel = l.attr("rel").toLowerCase();
            String as = l.attr("as").toLowerCase();
            if (as.contains("font") || rel.contains("font")) {
                addResource(l.absUrl("href"), "FONT", out);
            }
        }
        return out;
    }

    private void addResources(Document doc, String selector, String attr,
                              String type, DiscoveredPage out) {
        for (Element el : doc.select(selector)) {
            addResource(el.absUrl(attr), type, out);
        }
    }

    private void addResource(String url, String type, DiscoveredPage out) {
        if (url == null || url.isBlank()) return;
        for (DiscoveredPage.DiscoveredResource r : out.resources) {
            if (r.url.equals(url) && r.type.equals(type)) return; // de-dupe
        }
        DiscoveredPage.DiscoveredResource r = new DiscoveredPage.DiscoveredResource();
        r.url = url;
        r.type = type;
        out.resources.add(r);
    }
}
