package com.assessmentnotebook;

import com.assessmentnotebook.analyze.DiscoveredPage;
import com.assessmentnotebook.analyze.JsonRequestForm;
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
 * JSON/XHR endpoints expose their fields as form parameters, so probe results
 * attach to them by name and render on the form — closing the gap where a JSON
 * login could not be documented as a form and its probes went nowhere.
 */
class JsonParameterSupportTest {

    @Test void jsonBodyBecomesFormInputs() {
        DiscoveredPage.DiscoveredForm df = JsonRequestForm.build(
                "https://site.test/rest/user/login", "POST",
                "{\"email\":\"a@b.c\",\"password\":\"x\"}");
        assertNotNull(df);
        assertEquals("application/json", df.encType);
        List<String> names = df.inputs.stream().map(i -> i.name).toList();
        assertTrue(names.contains("email"));
        assertTrue(names.contains("password"));
        assertEquals("json", df.inputs.get(0).type);
        assertNull(JsonRequestForm.build("u", "POST", "not json"),
                "no form when the body has no JSON parameters");
    }

    @Test void probeAttachesToJsonParameterAndRendersOnForm(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("json");

        PageRegistration reg = new PageRegistration();
        reg.url = "https://site.test/rest/user/login";
        reg.method = "POST";
        reg.statusCode = 401;
        reg.contentType = "application/json";
        reg.discovered = new DiscoveredPage();
        reg.discovered.forms.add(JsonRequestForm.build(reg.url, reg.method,
                "{\"email\":\"a@b.c\",\"password\":\"x\"}"));
        Page page = c.registerPage(reg);

        // The JSON fields are now real parameters on a form.
        Parameter email = c.project().parameters.stream()
                .filter(p -> "email".equals(p.name)).findFirst().orElse(null);
        assertNotNull(email, "email became a documented parameter");
        assertEquals("json", email.inputType);

        // A probe recorded with only pageId + name (parameterId null) resolves to it.
        ParameterTest t = new ParameterTest();
        t.pageId = page.id;
        t.parameterName = "email";
        t.source = ParameterTest.Source.JSON;
        t.probeKind = "SQL_QUOTE";
        t.probeLabel = "SQLi: single quote  '";
        t.sentValue = "'";
        t.responseStatus = 500;
        t.responseLength = 1183;
        t.sqlErrorSignature = true;
        List<ParameterTest> saved = c.recordParameterTests(List.of(t));
        assertEquals(email.id, saved.get(0).parameterId, "probe attached to the JSON parameter");

        Form form = c.project().findForm(email.id) != null ? null
                : c.project().forms.get(0);
        String formHtml = Files.readString(c.layout().root.resolve("forms/" + form.id + ".html"));
        assertTrue(formHtml.contains("Parameter tests"), "probe renders on the form");
        assertTrue(formHtml.contains("SQL error signature"), "SQLi observation shown");
    }
}
