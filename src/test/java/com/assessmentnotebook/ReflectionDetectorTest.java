package com.assessmentnotebook;

import com.assessmentnotebook.analyze.ReflectionDetector;
import com.assessmentnotebook.model.Reflection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReflectionDetectorTest {
    private final ReflectionDetector det = new ReflectionDetector();

    @Test void detectsAttributeContext() {
        String body = "<input value=\"TEST123\">";
        List<Reflection> r = det.detect(body, null, "text/html", "TEST123");
        assertEquals(1, r.size());
        assertEquals(Reflection.Context.HTML_ATTRIBUTE, r.get(0).context);
    }

    @Test void detectsHtmlTextContext() {
        String body = "<p>hello TEST123 world</p>";
        List<Reflection> r = det.detect(body, null, "text/html", "TEST123");
        assertEquals(Reflection.Context.HTML_TEXT, r.get(0).context);
    }

    @Test void detectsScriptContext() {
        String body = "<script>var x = 'TEST123';</script>";
        List<Reflection> r = det.detect(body, null, "text/html", "TEST123");
        assertEquals(Reflection.Context.JAVASCRIPT, r.get(0).context);
    }

    @Test void detectsHeaderContext() {
        List<Reflection> r = det.detect("<html></html>",
                List.of("Set-Cookie: last=TEST123"), "text/html", "TEST123");
        assertEquals(1, r.size());
        assertEquals(Reflection.Context.HTTP_HEADER, r.get(0).context);
        assertEquals("Set-Cookie header", r.get(0).location);
    }

    @Test void detectsJsonContext() {
        List<Reflection> r = det.detect("{\"q\":\"TEST123\"}", null, "application/json", "TEST123");
        assertEquals(Reflection.Context.JSON, r.get(0).context);
    }

    @Test void noValueNoReflection() {
        assertTrue(det.detect("anything", null, "text/html", "").isEmpty());
        assertTrue(det.detect("nothing here", null, "text/html", "ABSENT").isEmpty());
    }
}
