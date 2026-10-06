package com.assessmentnotebook.repair;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Command-line entry point for {@link ProjectRepair}; {@code
 * scripts/repair-project.sh} is a thin wrapper around it.
 *
 * <p>Exit status: 0 when the project is consistent (nothing found, or
 * everything found was fixed), 1 when problems remain (a dry run found some, or
 * a repair left some for the tester), 2 when the project could not be read or
 * the arguments were wrong.
 */
public final class RepairTool {
    private RepairTool() {}

    static final String USAGE = String.join("\n",
            "Usage: repair-project.sh <project-directory> [options]",
            "",
            "Checks an Assessment Notebook project for inconsistencies: records that point",
            "at things that no longer exist, pages that lost track of their forms and links,",
            "links and redirects that were never connected, duplicated or mistyped",
            "resources, evidence files that moved, and stale generated documents.",
            "",
            "Without options it only reports (a dry run). Nothing is changed.",
            "",
            "Options:",
            "  --apply           Fix what can be fixed, rebuild every document and check",
            "                    every internal link. project.json is backed up first as",
            "                    project.json.bak-<time>.",
            "  --prune           With --apply: also remove auto-discovered records that",
            "                    nothing can reach, and move evidence files that no record",
            "                    refers to into _orphaned/ (they are moved, not deleted).",
            "  --no-reclassify   Do not turn JSON/XHR pages into API endpoints or fold a",
            "                    resource that duplicates an endpoint into it.",
            "  -v, --verbose     List every occurrence instead of a few examples.",
            "  -h, --help        Show this help.",
            "",
            "Exit status: 0 consistent, 1 problems found or left, 2 could not read project.",
            "",
            "Close the project in Burp first, or re-open it right after a repair: the",
            "extension keeps the project in memory and would otherwise save its old copy",
            "over the repaired one.");

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Run with the given arguments; returns the exit status. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        ProjectRepair.Options options = new ProjectRepair.Options();
        boolean verbose = false;
        String dir = null;
        for (String arg : args) {
            switch (arg) {
                case "--apply": options.apply = true; break;
                case "--prune": options.prune = true; break;
                case "--no-reclassify": options.reclassify = false; break;
                case "-v": case "--verbose": verbose = true; break;
                case "-h": case "--help":
                    out.println(USAGE);
                    return 0;
                default:
                    if (arg.startsWith("-")) {
                        err.println("Unknown option: " + arg + "\n\n" + USAGE);
                        return 2;
                    }
                    if (dir != null) {
                        err.println("Only one project directory can be given.\n\n" + USAGE);
                        return 2;
                    }
                    dir = arg;
            }
        }
        if (dir == null) {
            err.println(USAGE);
            return 2;
        }
        if (options.prune && !options.apply) {
            out.println("Note: --prune has no effect on its own; this dry run shows what "
                    + "--apply --prune would do.\n");
        }
        RepairReport report;
        try {
            report = new ProjectRepair(Path.of(dir), options).run();
        } catch (Exception e) {
            err.println("The repair stopped with an error: " + e);
            err.println("project.json is saved before anything else is touched, so the model is "
                    + "either the old or the repaired one; run the check again to see where "
                    + "things stand.");
            return 2;
        }
        out.print(report.render(verbose));
        if (report.fatal != null) return 2;
        if (report.applied) {
            out.println("If this project is open in Burp, re-open it now (New / Open Project…) "
                    + "so the extension loads the repaired copy.");
        }
        return report.consistent() ? 0 : 1;
    }
}
