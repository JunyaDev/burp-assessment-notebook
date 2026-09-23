package com.assessmentnotebook.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The root aggregate for one assessment. Everything the tool knows lives here
 * and serializes to a single {@code project.json}; generated HTML and binary
 * evidence are derived from this object, never the source of truth.
 *
 * <p>Entities are stored in flat lists (friendly to JSON and to diffing) and
 * looked up by id through the {@code findX} helpers. Ids are minted by
 * {@link #nextId(EntityType)} using per-type counters so they are stable and
 * human-readable ({@code page-0007}).
 */
public class Project {
    /** Schema version, to allow future migrations of project.json. */
    public int schemaVersion = 1;
    public String name = "Untitled assessment";
    public String createdAt;
    public String updatedAt;

    public TargetInfo target = new TargetInfo();

    public List<Technology> technologies = new ArrayList<>();
    public List<Page> pages = new ArrayList<>();
    public List<Resource> resources = new ArrayList<>();
    public List<Link> links = new ArrayList<>();
    public List<Form> forms = new ArrayList<>();
    public List<Parameter> parameters = new ArrayList<>();
    public List<Interaction> interactions = new ArrayList<>();
    public List<RequestRecord> requests = new ArrayList<>();
    public List<ResponseRecord> responses = new ArrayList<>();
    public List<Screenshot> screenshots = new ArrayList<>();
    public List<Note> notes = new ArrayList<>();
    public List<Vulnerability> vulnerabilities = new ArrayList<>();
    public List<Relationship> relationships = new ArrayList<>();

    /** Per-type id counters (last used number for each type slug). */
    public Map<String, Integer> idCounters = new java.util.HashMap<>();

    /** Mint the next stable id for a type, e.g. {@code form-0003}. */
    public synchronized String nextId(EntityType type) {
        int n = idCounters.getOrDefault(type.slug, 0) + 1;
        idCounters.put(type.slug, n);
        return String.format("%s-%04d", type.slug, n);
    }

    // ---- lookups ---------------------------------------------------------
    public Page findPage(String id) { return byId(pages, id); }
    public Resource findResource(String id) { return byId(resources, id); }
    public Link findLink(String id) { return byId(links, id); }
    public Form findForm(String id) { return byId(forms, id); }
    public Parameter findParameter(String id) { return byId(parameters, id); }
    public Interaction findInteraction(String id) { return byId(interactions, id); }
    public RequestRecord findRequest(String id) { return byId(requests, id); }
    public ResponseRecord findResponse(String id) { return byId(responses, id); }
    public Screenshot findScreenshot(String id) { return byId(screenshots, id); }
    public Vulnerability findVulnerability(String id) { return byId(vulnerabilities, id); }
    public Technology findTechnology(String id) { return byId(technologies, id); }

    /** Find an existing page by URL+method, or null. Used to avoid duplicates. */
    public Page findPageByRequest(String url, String method) {
        for (Page p : pages) {
            if (p.url.equals(url) && p.method.equalsIgnoreCase(method)) return p;
        }
        return null;
    }

    /** Notes attached to a given entity. */
    public List<Note> notesFor(EntityType type, String id) {
        List<Note> out = new ArrayList<>();
        for (Note n : notes) {
            if (n.targetType == type && id.equals(n.targetId)) out.add(n);
        }
        return out;
    }

    /** Technologies grouped by category, in category declaration order. */
    public Map<Technology.Category, List<Technology>> technologiesByCategory() {
        Map<Technology.Category, List<Technology>> m = new EnumMap<>(Technology.Category.class);
        for (Technology t : technologies) {
            m.computeIfAbsent(t.category, k -> new ArrayList<>()).add(t);
        }
        return m;
    }

    private static <T> T byId(List<T> list, String id) {
        if (id == null) return null;
        for (T t : list) {
            try {
                java.lang.reflect.Field f = t.getClass().getField("id");
                if (id.equals(f.get(t))) return t;
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        return null;
    }
}
