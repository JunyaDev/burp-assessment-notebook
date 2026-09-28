package com.assessmentnotebook;

import com.assessmentnotebook.analyze.HtmlAnalyzer;
import com.assessmentnotebook.analyze.JsScanner;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class JsScanTest {

    private static final String SAMPLE =
            "var api='https://api.juice-sh.op/v2';\n"
          + "fetch('/rest/user/whoami');\n"
          + "const token='eyJhbGciOi.eyJzdWIiOiIx.SIG_abc123';\n"
          + "var apiKey=\"AIzaSyA1234567890abcdefghijklmnopqrstuvz\";\n"
          + "function run(x){ return eval(x); }\n"
          + "window.doLogin = function(){};\n"
          + "if (user.isAdmin || bypassAuth) grant();\n"
          + "module.exports = run;\n";

    private static Set<JsFinding.Kind> kinds(List<JsScanner.Match> m) {
        return m.stream().map(x -> x.kind).collect(Collectors.toSet());
    }

    @Test void scannerFindsEachCategory() {
        List<JsScanner.Match> m = JsScanner.scan(SAMPLE);
        Set<JsFinding.Kind> ks = kinds(m);
        assertTrue(ks.contains(JsFinding.Kind.ENDPOINT), "endpoints");
        assertTrue(ks.contains(JsFinding.Kind.SECRET), "secrets");
        assertTrue(ks.contains(JsFinding.Kind.DANGEROUS_CALL), "dangerous calls");
        assertTrue(ks.contains(JsFinding.Kind.EXPORTED_FUNCTION), "exports");
        assertTrue(ks.contains(JsFinding.Kind.BYPASS), "bypass hints");
        // The absolute URL and the API path are both captured.
        List<String> vals = m.stream().map(x -> x.value).toList();
        assertTrue(vals.contains("https://api.juice-sh.op/v2"));
        assertTrue(vals.stream().anyMatch(v -> v.contains("/rest/user/whoami")));
        assertTrue(vals.stream().anyMatch(v -> v.startsWith("eyJ")), "JWT captured");
    }

    @Test void scannerLinesAndEmptyInput() {
        assertTrue(JsScanner.scan("").isEmpty());
        assertTrue(JsScanner.scan(null).isEmpty());
        List<JsScanner.Match> m = JsScanner.scan(SAMPLE);
        assertTrue(m.stream().allMatch(x -> x.line >= 1), "every match has a 1-based line");
    }

    @Test void findingsRecordedAndListedByPage(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("jsscan");

        // A page that loads main.dart.js, so the script resource exists and links.
        String scriptUrl = "https://site.test/main.dart.js";
        String html = "<html><body><script src=\"" + scriptUrl + "\"></script></body></html>";
        PageRegistration reg = new PageRegistration();
        reg.url = "https://site.test/";
        reg.method = "GET";
        reg.statusCode = 200;
        reg.contentType = "text/html";
        reg.discovered = new HtmlAnalyzer().analyze(html, reg.url);
        Page page = c.registerPage(reg);

        // Scan and record the selected findings.
        List<JsScanner.Match> matches = JsScanner.scan(SAMPLE);
        List<JsFinding> findings = new ArrayList<>();
        for (JsScanner.Match m : matches) {
            JsFinding f = new JsFinding();
            f.kind = m.kind; f.value = m.value; f.detail = m.detail;
            f.context = m.context; f.lineNumber = m.line;
            findings.add(f);
        }
        List<JsFinding> saved = c.recordJsFindings(scriptUrl, null, SAMPLE, findings);
        assertFalse(saved.isEmpty());

        // Re-recording the same set adds nothing (dedup on resource+kind+value).
        int before = c.project().jsFindings.size();
        c.recordJsFindings(scriptUrl, null, SAMPLE, findings);
        assertEquals(before, c.project().jsFindings.size(), "duplicate findings are not re-added");

        Resource script = c.project().resources.stream()
                .filter(r -> r.url.contains("main.dart.js")).findFirst().orElseThrow();

        // Resource page shows the findings and the captured body.
        String resHtml = Files.readString(c.layout().root.resolve("scripts/" + script.id + ".html"));
        assertTrue(resHtml.contains("DISCOVERED IN THIS SCRIPT"), "resource lists findings");
        assertTrue(resHtml.contains("api.juice-sh.op"), "endpoint shown on resource");
        assertTrue(resHtml.contains("CAPTURED SOURCE"), "scanned body saved");

        // The page that loads the script lists the discovered material too.
        String pageHtml = Files.readString(c.layout().pages().resolve(page.id + ".html"));
        assertTrue(pageHtml.contains("DISCOVERED IN SCRIPTS"), "page lists script findings");
        assertTrue(pageHtml.contains(script.id), "page links back to the script");
    }
}
