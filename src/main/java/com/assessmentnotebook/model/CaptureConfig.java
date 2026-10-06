package com.assessmentnotebook.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The project's auto-capture settings: whether traffic is registered
 * automatically, the ordered rules that decide what is, and how repeat
 * sightings of a known page are treated. Stored in {@code project.json} so the
 * rules travel with the assessment.
 */
public class CaptureConfig {
    public boolean enabled = false;
    public List<CaptureRule> rules = new ArrayList<>();
    /**
     * Treat id-like path segments (numbers, UUIDs, long hex) as one placeholder,
     * so {@code /api/users/17} and {@code /api/users/42} are one endpoint
     * instead of one record per id.
     */
    public boolean collapseIds = true;
    /** Stop adding automatic variants to a page once it has this many. */
    public int maxVariantsPerPage = 10;

    public CaptureConfig copy() {
        CaptureConfig c = new CaptureConfig();
        c.enabled = enabled;
        for (CaptureRule r : rules) c.rules.add(r.copy());
        c.collapseIds = collapseIds;
        c.maxVariantsPerPage = maxVariantsPerPage;
        return c;
    }
}
