package com.assessmentnotebook;

import com.assessmentnotebook.analyze.VariantDiff;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.core.PageRegistration;
import com.assessmentnotebook.model.Page;
import com.assessmentnotebook.model.PageVariant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageVariantTest {

    private static PageVariant variant(String label, String q) {
        PageVariant v = new PageVariant();
        v.url = "https://s.test/search";
        v.method = "GET";
        v.label = label;
        v.queryParams.put("q", q);
        v.statusCode = 200;
        v.contentType = "text/html";
        return v;
    }

    private static String bodyFor(String q, int rows) {
        StringBuilder rowsHtml = new StringBuilder();
        for (int i = 0; i < rows; i++) rowsHtml.append("<li>result ").append(i).append("</li>");
        return "<html><head><title>Search: " + q + "</title></head><body>"
                + "<p>You searched for " + q + "</p><ul>" + rowsHtml + "</ul></body></html>";
    }

    @Test void variantsAreGroupedUnderOnePageAndDiffed(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("variants");

        c.registerVariant(variant("apple", "apple"), bodyFor("apple", 3));
        c.registerVariant(variant("banana", "banana"), bodyFor("banana", 5));

        assertEquals(1, c.project().pages.size(), "both variants attach to one page");
        assertEquals(2, c.project().variants.size());

        List<VariantDiff.Comparison> comps = c.compareVariants(c.project().pages.get(0).id);
        assertEquals(1, comps.size());
        VariantDiff.Comparison d = comps.get(0);

        assertTrue(d.inputDifferences.stream().anyMatch(s -> s.contains("q")
                && s.contains("apple") && s.contains("banana")), "input q change reported");
        assertTrue(d.outputDifferences.stream().anyMatch(s -> s.startsWith("title:")),
                "title change reported");
        assertTrue(d.outputDifferences.stream().anyMatch(s -> s.startsWith("length:")),
                "length change reported");
        assertTrue(d.reflectedInputs.contains("q"), "q value reflected in the response");
    }

    @Test void variantsGroupByPathEvenWhenPageHasQuery(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("group");

        // A page first registered WITH a query string (as Register Page does in Burp).
        PageRegistration reg = new PageRegistration();
        reg.url = "https://s.test/search?q=apple";
        reg.method = "GET";
        reg.statusCode = 200;
        reg.contentType = "text/html";
        Page page = c.registerPage(reg);
        int pagesBefore = c.project().pages.size();

        // Variants captured from other query values must attach to that same page.
        c.registerVariant(variant("q=apple", "apple"), bodyFor("apple", 3));
        c.registerVariant(variant("q=banana", "banana"), bodyFor("banana", 5));

        assertEquals(pagesBefore, c.project().pages.size(), "no extra page created per query value");
        assertEquals(2, c.project().variants.size());
        assertTrue(c.project().variants.stream().allMatch(v -> v.pageId.equals(page.id)),
                "both variants attached to the query-registered page");
        assertEquals(1, c.compareVariants(page.id).size());
    }

    @Test void identicalConditionsProduceNoOutputDiff(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("variants2");
        c.registerVariant(variant("a", "apple"), bodyFor("apple", 3));
        c.registerVariant(variant("a-again", "apple"), bodyFor("apple", 3));
        VariantDiff.Comparison d = c.compareVariants(c.project().pages.get(0).id).get(0);
        assertFalse(d.hasOutputChange(), "same input, same output");
    }
}
