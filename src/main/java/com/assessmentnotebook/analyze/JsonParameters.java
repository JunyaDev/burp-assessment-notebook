package com.assessmentnotebook.analyze;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Treats a JSON request body as a first-class parameter source (spec §17): it
 * flattens nested objects and arrays to dotted/indexed paths
 * ({@code settings.theme}, {@code roles[0]}) and can set a single leaf by path
 * while preserving the rest of the structure, so a probe changes only the value
 * under test.
 */
public final class JsonParameters {
    private JsonParameters() {}

    private static final com.google.gson.Gson PRETTY = new com.google.gson.GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    /**
     * Re-indent a JSON document for reading. Anything that is not a JSON object
     * or array (or is too large to be worth it) is returned untouched.
     */
    public static String pretty(String json) {
        if (json == null || json.length() > 2_000_000) return json;
        String t = json.stripLeading();
        if (t.isEmpty() || (t.charAt(0) != '{' && t.charAt(0) != '[')) return json;
        try {
            return PRETTY.toJson(JsonParser.parseString(t));
        } catch (RuntimeException notJson) {
            return json;
        }
    }

    /** Parse JSON and return leaf parameters as path -> string value, in order. */
    public static Map<String, String> flatten(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonElement root = JsonParser.parseString(json);
            walk("", root, out);
        } catch (RuntimeException notJson) {
            // Not JSON: nothing to flatten.
        }
        return out;
    }

    private static void walk(String prefix, JsonElement el, Map<String, String> out) {
        if (el == null || el.isJsonNull()) {
            if (!prefix.isEmpty()) out.put(prefix, "null");
        } else if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.entrySet().isEmpty() && !prefix.isEmpty()) return;
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                walk(key, e.getValue(), out);
            }
        } else if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                walk(prefix + "[" + i + "]", a.get(i), out);
            }
        } else { // primitive
            out.put(prefix, el.getAsString());
        }
    }

    /**
     * Return a copy of {@code json} with the leaf at {@code path} replaced by
     * {@code newValue} (kept as a JSON string), preserving all other structure.
     * Returns the original text unchanged if the path does not resolve.
     */
    public static String setAtPath(String json, String path, String newValue) {
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonElement parent = navigateToParent(root, path);
            String leaf = lastSegment(path);
            if (parent == null) return json;
            if (leaf.startsWith("[") && parent.isJsonArray()) {
                int idx = Integer.parseInt(leaf.substring(1, leaf.length() - 1));
                JsonArray arr = parent.getAsJsonArray();
                if (idx < 0 || idx >= arr.size()) return json;
                arr.set(idx, new JsonPrimitive(newValue));
            } else if (parent.isJsonObject()) {
                JsonObject obj = parent.getAsJsonObject();
                if (!obj.has(leaf)) return json;
                obj.add(leaf, new JsonPrimitive(newValue));
            } else {
                return json;
            }
            return root.toString();
        } catch (RuntimeException e) {
            return json;
        }
    }

    private static JsonElement navigateToParent(JsonElement root, String path) {
        String[] segments = splitPath(path);
        JsonElement current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            current = step(current, segments[i]);
            if (current == null) return null;
        }
        return current;
    }

    private static JsonElement step(JsonElement current, String segment) {
        if (segment.startsWith("[")) {
            if (!current.isJsonArray()) return null;
            int idx = Integer.parseInt(segment.substring(1, segment.length() - 1));
            JsonArray arr = current.getAsJsonArray();
            return (idx >= 0 && idx < arr.size()) ? arr.get(idx) : null;
        }
        if (!current.isJsonObject()) return null;
        return current.getAsJsonObject().get(segment);
    }

    private static String lastSegment(String path) {
        String[] s = splitPath(path);
        return s.length == 0 ? path : s[s.length - 1];
    }

    /** Split "settings.roles[0].name" into [settings, roles, [0], name]. */
    private static String[] splitPath(String path) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else if (c == '[') {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                cur.append('[');
            } else if (c == ']') {
                cur.append(']');
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
