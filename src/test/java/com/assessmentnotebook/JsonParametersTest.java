package com.assessmentnotebook;

import com.assessmentnotebook.analyze.JsonParameters;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonParametersTest {

    @Test void flattensNestedObjectsAndArrays() {
        String json = "{\"username\":\"test\",\"settings\":{\"theme\":\"dark\"},"
                + "\"roles\":[\"admin\",\"user\"]}";
        Map<String, String> p = JsonParameters.flatten(json);
        assertEquals("test", p.get("username"));
        assertEquals("dark", p.get("settings.theme"));
        assertEquals("admin", p.get("roles[0]"));
        assertEquals("user", p.get("roles[1]"));
    }

    @Test void setAtPathPreservesStructure() {
        String json = "{\"username\":\"test\",\"settings\":{\"theme\":\"dark\"}}";
        String out = JsonParameters.setAtPath(json, "settings.theme", "light");
        Map<String, String> p = JsonParameters.flatten(out);
        assertEquals("light", p.get("settings.theme"));
        assertEquals("test", p.get("username"), "sibling untouched");
    }

    @Test void setAtPathInArray() {
        String json = "{\"roles\":[\"admin\",\"user\"]}";
        String out = JsonParameters.setAtPath(json, "roles[1]", "guest");
        assertEquals("guest", JsonParameters.flatten(out).get("roles[1]"));
    }

    @Test void unknownPathLeavesJsonUnchanged() {
        String json = "{\"a\":1}";
        assertEquals(json, JsonParameters.setAtPath(json, "b.c", "x"));
    }

    @Test void nonJsonYieldsNoParameters() {
        assertTrue(JsonParameters.flatten("username=test&x=1").isEmpty());
    }
}
