package com.assessmentnotebook.analyze;

import com.assessmentnotebook.model.Page;
import com.assessmentnotebook.model.Project;
import com.assessmentnotebook.model.Technology;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Derives the project-setup fields (spec §4) from traffic already captured, so
 * the wizard can pre-fill Domain, Main URL, Server, Frameworks and
 * Authentication instead of making the tester guess. Every value is a
 * suggestion the tester can edit.
 */
public final class ProjectMetadataInference {
    private ProjectMetadataInference() {}

    public static final class Suggestion {
        public String domain = "";
        public String mainUrl = "";
        public String server = "";
        public String frameworks = "";
        public String authentication = "";
    }

    public static Suggestion infer(Project project) {
        Suggestion s = new Suggestion();
        s.domain = mostCommonHost(project);
        s.mainUrl = deriveMainUrl(project, s.domain);
        s.server = join(project, Technology.Category.WEB_SERVER, Technology.Category.OPERATING_SYSTEM);
        s.frameworks = join(project, Technology.Category.FRAMEWORK, Technology.Category.JS_FRAMEWORK,
                Technology.Category.LANGUAGE, Technology.Category.CMS);
        s.authentication = join(project, Technology.Category.AUTHENTICATION);
        return s;
    }

    private static String mostCommonHost(Project project) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Page p : project.pages) {
            String host = hostOf(p.url);
            if (!host.isEmpty()) counts.merge(host, 1, Integer::sum);
        }
        String best = "";
        int max = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > max) { max = e.getValue(); best = e.getKey(); }
        }
        if (!best.isEmpty()) return best;
        return project.target != null ? project.target.domain : "";
    }

    private static String deriveMainUrl(Project project, String domain) {
        // Prefer the shortest-path page on the chosen host (usually the root).
        String best = null;
        int bestLen = Integer.MAX_VALUE;
        for (Page p : project.pages) {
            if (!hostOf(p.url).equals(domain)) continue;
            int len = pathLength(p.url);
            if (len < bestLen) { bestLen = len; best = p.url; }
        }
        if (best != null) {
            try {
                URI u = URI.create(best);
                String scheme = u.getScheme() == null ? "https" : u.getScheme();
                return scheme + "://" + domain + "/";
            } catch (RuntimeException ignored) { /* fall through */ }
        }
        return domain.isEmpty() ? "" : "https://" + domain + "/";
    }

    private static String join(Project project, Technology.Category... cats) {
        StringJoiner j = new StringJoiner(", ");
        for (Technology.Category cat : cats) {
            for (Technology t : project.technologies) {
                if (t.category == cat) {
                    j.add(t.version == null || t.version.isBlank()
                            ? t.name : t.name + " " + t.version);
                }
            }
        }
        return j.toString();
    }

    private static String hostOf(String url) {
        try {
            URI u = URI.create(url.trim());
            String h = u.getHost();
            if (h == null) return "";
            String host = h.toLowerCase();
            int port = u.getPort();
            boolean defaultPort = ("http".equalsIgnoreCase(u.getScheme()) && port == 80)
                    || ("https".equalsIgnoreCase(u.getScheme()) && port == 443);
            return (port > 0 && !defaultPort) ? host + ":" + port : host;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int pathLength(String url) {
        try {
            String p = URI.create(url.trim()).getPath();
            return p == null ? 0 : p.length();
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }
}
