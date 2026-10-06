package com.assessmentnotebook;

import com.assessmentnotebook.analyze.HtmlAnalyzer;
import com.assessmentnotebook.analyze.ParameterPurposeGuess;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.core.NotebookController.ParameterEdit;
import com.assessmentnotebook.core.PageRegistration;
import com.assessmentnotebook.model.Form;
import com.assessmentnotebook.model.Page;
import com.assessmentnotebook.model.Parameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The tester can describe what each parameter is for, and it shows in the docs. */
class ParameterAnnotationTest {

    private static final String HTML = "<html><body><form id='login' action='/login' method='post'>"
            + "<input name='user'><input name='csrf_token' type='hidden' value='x'>"
            + "</form></body></html>";

    private static PageRegistration reg() {
        PageRegistration reg = new PageRegistration();
        reg.url = "https://t.example/login";
        reg.statusCode = 200;
        reg.contentType = "text/html";
        reg.pageSource = HTML;
        reg.discovered = new HtmlAnalyzer().analyze(HTML, reg.url);
        return reg;
    }

    @Test void purposeAndNotesAreSavedRenderedAndSurviveReregistration(@TempDir Path dir)
            throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("t");
        Page page = c.registerPage(reg());
        Form form = c.project().findForm(page.formIds.get(0));
        String user = form.parameterIds.get(0);
        String csrf = form.parameterIds.get(1);

        int changed = c.annotateParameters(List.of(
                new ParameterEdit(user, "Login name", "Enumerable: error differs for unknown users"),
                new ParameterEdit(csrf, "Anti-CSRF token", null)),
                Map.of(form.id, "Main login form"));
        assertEquals(3, changed);

        Parameter p = c.project().findParameter(user);
        assertEquals("Login name", p.purpose);
        assertEquals("", c.project().findParameter(csrf).notes, "a null field is left untouched");

        String formDoc = Files.readString(dir.resolve("forms").resolve(form.id + ".html"));
        assertTrue(formDoc.contains("Login name"));
        assertTrue(formDoc.contains("Enumerable: error differs for unknown users"));
        assertTrue(formDoc.contains("Main login form"));
        String pageDoc = Files.readString(dir.resolve("pages").resolve(page.id + ".html"));
        assertTrue(pageDoc.contains("Anti-CSRF token"), "the page lists its parameters' purposes");

        assertEquals(0, c.annotateParameters(List.of(new ParameterEdit(user, "Login name", null)),
                Map.of()), "saving the same text again changes nothing");

        // Registering the page again must not wipe what the tester wrote.
        c.registerPage(reg());
        assertEquals("Login name", c.project().findParameter(user).purpose);
        assertEquals("Main login form", c.project().findForm(form.id).notes);

        NotebookController reopened = new NotebookController(dir);
        reopened.open();
        assertEquals("Enumerable: error differs for unknown users",
                reopened.project().findParameter(user).notes);
    }

    @Test void purposeGuessReadsTheLastSegmentOfTheName() {
        assertEquals("Anti-CSRF token", ParameterPurposeGuess.guess("csrf_token"));
        assertEquals("Credential (secret)", ParameterPurposeGuess.guess("user.password"));
        assertEquals("Object identifier (check for IDOR)", ParameterPurposeGuess.guess("orderId"));
        assertEquals("Object identifier (check for IDOR)", ParameterPurposeGuess.guess("items[0].id"));
        assertEquals("Pagination", ParameterPurposeGuess.guess("page"));
        assertEquals("Search / filter text", ParameterPurposeGuess.guess("q"));
        assertEquals("Authorization attribute", ParameterPurposeGuess.guess("isAdmin"));
        assertEquals("Redirect / return target", ParameterPurposeGuess.guess("returnUrl"));
        // Names that merely contain a keyword are not mislabeled.
        assertEquals("", ParameterPurposeGuess.guess("valid"));
        assertEquals("", ParameterPurposeGuess.guess("history"));
        assertEquals("", ParameterPurposeGuess.guess("x"));
    }
}
