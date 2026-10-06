package com.assessmentnotebook.repair;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a project check found, grouped so a long list of similar problems reads
 * as one line with examples. Every entry is either something the repair fixes
 * by itself or something only the tester can decide ("needs attention").
 */
public final class RepairReport {
    public enum Category {
        STRUCTURE("Model structure"),
        REFERENCES("Dangling references"),
        CONNECTIONS("Missing connections"),
        RESOURCES("Resources and API endpoints"),
        FILES("Evidence files"),
        DOCUMENTS("Generated documents");

        public final String label;
        Category(String label) { this.label = label; }
    }

    /** One kind of problem within a category, with every occurrence of it. */
    public static final class Group {
        public final Category category;
        public final String title;
        public final boolean fixable;
        public final List<String> details = new ArrayList<>();

        Group(Category category, String title, boolean fixable) {
            this.category = category;
            this.title = title;
            this.fixable = fixable;
        }
    }

    private final Map<String, Group> groups = new LinkedHashMap<>();

    /** Set when the project could not be read at all; nothing else is reported then. */
    public String fatal;
    public Path root;
    public String projectSummary = "";
    /** True when fixes were written; false for a dry run. */
    public boolean applied;
    /** The copy of project.json taken before it was rewritten, if it was. */
    public Path backup;
    public int documentsNew;
    public int documentsChanged;
    public int documentsChecked;
    public int linksChecked;
    /** Internal links in the generated documents that lead nowhere ("doc → target"). */
    public final List<String> brokenLinks = new ArrayList<>();

    /** Record a problem the repair fixes. */
    public void fixed(Category category, String title, String detail) {
        add(category, title, true, detail);
    }

    /** Record a problem that is reported but left for the tester to resolve. */
    public void attention(Category category, String title, String detail) {
        add(category, title, false, detail);
    }

    private void add(Category category, String title, boolean fixable, String detail) {
        groups.computeIfAbsent(category + "|" + fixable + "|" + title,
                k -> new Group(category, title, fixable)).details.add(detail);
    }

    public List<Group> groups() { return new ArrayList<>(groups.values()); }

    public int fixableCount() { return count(true); }
    public int attentionCount() { return count(false); }

    private int count(boolean fixable) {
        int n = 0;
        for (Group g : groups.values()) if (g.fixable == fixable) n += g.details.size();
        return n;
    }

    /** Number of occurrences recorded under a group title (for callers and tests). */
    public int count(String title) {
        int n = 0;
        for (Group g : groups.values()) if (g.title.equals(title)) n += g.details.size();
        return n;
    }

    /** True when nothing is wrong: no problems of either kind and no broken links. */
    public boolean clean() {
        return fatal == null && groups.isEmpty() && brokenLinks.isEmpty();
    }

    /**
     * Whether the project is consistent as it now stands on disk: after a
     * repair, nothing is left for the tester; after a dry run, nothing was found.
     */
    public boolean consistent() {
        if (fatal != null) return false;
        if (!applied) return clean();
        return attentionCount() == 0 && brokenLinks.isEmpty();
    }

    private static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** The report as plain text for a terminal. */
    public String render(boolean verbose) {
        StringBuilder b = new StringBuilder();
        b.append("Assessment Notebook project ").append(applied ? "repair" : "check").append(": ")
                .append(root).append('\n');
        if (fatal != null) {
            return b.append("\nERROR: ").append(fatal).append('\n').toString();
        }
        b.append(projectSummary).append('\n');
        if (!applied) {
            b.append("(dry run: nothing was changed; [fixable] lines say what --apply would do)\n");
        }

        String fixMark = applied ? "[fixed]" : "[fixable]";
        for (Category c : Category.values()) {
            boolean header = false;
            for (Group g : groups.values()) {
                if (g.category != c) continue;
                if (!header) {
                    b.append('\n').append(c.label.toUpperCase()).append('\n');
                    header = true;
                }
                b.append("  ").append(g.fixable ? fixMark : "[needs attention]").append(' ')
                        .append(g.title).append(" (").append(g.details.size()).append(")\n");
                int shown = verbose ? g.details.size() : Math.min(4, g.details.size());
                for (int i = 0; i < shown; i++) b.append("      ").append(g.details.get(i)).append('\n');
                if (shown < g.details.size()) {
                    b.append("      ... and ").append(g.details.size() - shown)
                            .append(" more (--verbose lists all)\n");
                }
            }
        }

        b.append('\n');
        if (applied) {
            b.append("Documents: ").append(documentsChecked).append(" generated (")
                    .append(documentsNew).append(" new, ").append(documentsChanged)
                    .append(" changed).\n");
        }
        if (brokenLinks.isEmpty()) {
            b.append("Link check: all ").append(linksChecked).append(" internal links in ")
                    .append(documentsChecked).append(" documents resolve.\n");
        } else {
            b.append("Link check: ").append(brokenLinks.size()).append(" of ").append(linksChecked)
                    .append(" internal links lead nowhere")
                    .append(applied ? ":\n" : " in the documents as they are now:\n");
            int shown = verbose ? brokenLinks.size() : Math.min(6, brokenLinks.size());
            for (int i = 0; i < shown; i++) b.append("      ").append(brokenLinks.get(i)).append('\n');
            if (shown < brokenLinks.size()) {
                b.append("      ... and ").append(brokenLinks.size() - shown).append(" more\n");
            }
        }

        b.append('\n');
        int fix = fixableCount();
        int todo = attentionCount();
        if (applied) {
            b.append(fix == 0 ? "No problems needed fixing" : "Fixed " + plural(fix, "problem"));
            b.append(todo == 0 ? "." : "; " + todo + (todo == 1 ? " needs" : " need")
                    + " your attention (listed above).");
            b.append('\n');
            if (backup != null) b.append("Previous project.json kept as ").append(backup.getFileName()).append('\n');
        } else if (fix == 0 && todo == 0 && brokenLinks.isEmpty()) {
            b.append("The project is consistent. Nothing to repair.\n");
        } else {
            b.append(plural(fix, "problem")).append(" can be fixed automatically");
            b.append(todo == 0 ? "." : "; " + todo + (todo == 1 ? " needs" : " need")
                    + " your attention.");
            b.append("\nRun again with --apply to repair (project.json is backed up first).\n");
        }
        return b.toString();
    }
}
