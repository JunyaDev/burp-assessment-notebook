package com.assessmentnotebook;

import com.assessmentnotebook.analyze.HtmlAnalyzer;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.core.PageRegistration;
import com.assessmentnotebook.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Registering the same URL again must update the page, never duplicate its children. */
class ReregistrationTest {

    private static final String HTML = "<html><head><title>Login</title></head><body>"
            + "<form id='login' action='/login' method='post'>"
            + "<input name='u'><input name='p' type='password'><button type='submit'>Go</button>"
            + "</form><a href='/about'>About</a><script src='/app.js'></script></body></html>";

    private static PageRegistration reg(String html) {
        PageRegistration reg = new PageRegistration();
        reg.url = "https://t.example/login";
        reg.method = "GET";
        reg.statusCode = 200;
        reg.contentType = "text/html";
        reg.pageSource = html;
        reg.rawRequest = "GET /login HTTP/1.1\r\n\r\n".getBytes();
        reg.rawResponse = ("HTTP/1.1 200 OK\r\n\r\n" + html).getBytes();
        reg.discovered = new HtmlAnalyzer().analyze(html, reg.url);
        return reg;
    }

    @Test
    void registeringTwiceKeepsOneCopyOfEverything(@TempDir Path dir) throws Exception {
        NotebookController c = new NotebookController(dir);
        c.create("t");
        c.registerPage(reg(HTML));
        Project p = c.project();
        int forms = p.forms.size(), params = p.parameters.size(), links = p.links.size();
        int resources = p.resources.size(), sources = p.pages.get(0).sourceFiles.size();
        assertEquals(1, forms);
        assertEquals(3, params);

        c.registerPage(reg(HTML));
        c.registerPage(reg(HTML));

        assertEquals(1, p.pages.size());
        assertEquals(forms, p.forms.size(), "forms duplicated");
        assertEquals(params, p.parameters.size(), "parameters duplicated");
        assertEquals(links, p.links.size(), "links duplicated");
        assertEquals(resources, p.resources.size(), "resources duplicated");
        assertEquals(1, p.pages.get(0).formIds.size());
        assertEquals(sources, p.pages.get(0).sourceFiles.size(),
                "identical source saved again");
        assertEquals(1, Files.list(dir.resolve("source"))
                .filter(f -> f.getFileName().toString().endsWith(".html")).count());
    }

    @Test
    void changedFormIsUpdatedInPlaceAndNewSourceIsKept(@TempDir Path dir) throws Exception {
        NotebookController c = new NotebookController(dir);
        c.create("t");
        Page page = c.registerPage(reg(HTML));
        String formId = page.formIds.get(0);
        String pId = c.project().findForm(formId).parameterIds.get(0);

        String changed = HTML.replace("<input name='u'>", "<input name='u' required>")
                .replace("</form>", "<input name='csrf' type='hidden' value='x'></form>");
        c.registerPage(reg(changed));

        Project p = c.project();
        assertEquals(1, p.forms.size());
        Form f = p.findForm(formId);
        assertSame(formId, f.id);
        assertEquals(4, f.parameterIds.size(), "new input added to the same form");
        assertEquals(pId, f.parameterIds.get(0), "existing parameter id is stable");
        assertTrue(p.findParameter(pId).required, "existing parameter updated in place");
        assertEquals(2, page.sourceFiles.size(), "a different body is saved as new evidence");
    }
}
