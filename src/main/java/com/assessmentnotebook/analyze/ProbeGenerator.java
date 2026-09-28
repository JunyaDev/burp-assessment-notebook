package com.assessmentnotebook.analyze;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Produces a set of <b>basic, non-destructive</b> probe values for a parameter
 * (spec §15, §16). Each probe is a controlled modification of the baseline value
 * used to characterize how the parameter behaves — required vs optional,
 * validated, length-limited, reflected — without sending anything that could
 * change server state. The set is configurable: a caller passes the kinds it
 * wants to run, so no application is assumed safe for arbitrary payloads.
 */
public final class ProbeGenerator {
    private ProbeGenerator() {}

    /** The controlled modifications available. All are non-destructive. */
    public enum Kind {
        ORIGINAL("Original value"),
        EMPTY("Empty value"),
        OMITTED("Parameter omitted"),
        SHORT("Short alternative"),
        LONG("Long alternative"),
        NUMERIC("Numeric value"),
        STRING("String value"),
        WHITESPACE("Whitespace"),
        BOUNDARY_MIN("Boundary: single char"),
        BOUNDARY_MAX("Boundary: long length"),
        SPECIAL_CHARS("Special characters"),
        QUOTE_CANARY("Quote reflection canary"),
        MARKUP_CANARY("Markup reflection canary"),
        // SQL injection probes: read-only, no state change. Meant to be sent as a
        // sequence — break the query with a lone quote, stabilize it, then test a
        // boolean bypass — so a SQL error and its disappearance are both visible.
        SQL_QUOTE("SQLi: single quote  '", true),
        SQL_QUOTE_DOUBLED("SQLi: doubled quote  ''", true),
        SQL_COMMENT_DASH("SQLi: quote + comment  '--", true),
        SQL_COMMENT_HASH("SQLi: quote + hash  '#", true),
        SQL_OR_TRUE("SQLi: boolean  ' OR '1'='1", true),
        SQL_OR_TRUE_COMMENT("SQLi: boolean  ' OR 1=1--", true);

        public final String label;
        /** True for SQL-injection probes: opt-in, shown in their own group. */
        public final boolean sqli;
        Kind(String label) { this(label, false); }
        Kind(String label, boolean sqli) { this.label = label; this.sqli = sqli; }
    }

    /** A single probe: the kind, a human label, and either a value or omission. */
    public static final class Probe {
        public final Kind kind;
        public final String label;
        public final String value;   // null when omitted
        public final boolean omit;
        Probe(Kind kind, String value, boolean omit) {
            this.kind = kind;
            this.label = kind.label;
            this.value = value;
            this.omit = omit;
        }
        /** True for probes whose purpose is to observe reflection context. */
        public boolean isReflectionCanary() {
            return kind == Kind.QUOTE_CANARY || kind == Kind.MARKUP_CANARY;
        }
    }

    /** The default, safe probe set. */
    public static Set<Kind> defaultKinds() {
        return EnumSet.of(Kind.ORIGINAL, Kind.EMPTY, Kind.OMITTED, Kind.SHORT, Kind.LONG,
                Kind.NUMERIC, Kind.STRING, Kind.WHITESPACE, Kind.SPECIAL_CHARS,
                Kind.QUOTE_CANARY, Kind.MARKUP_CANARY);
    }

    /** The SQL-injection probe set, in send order. Opt-in; never in the default. */
    public static java.util.List<Kind> sqliKinds() {
        java.util.List<Kind> out = new ArrayList<>();
        for (Kind k : Kind.values()) if (k.sqli) out.add(k);
        return out;
    }

    public static List<Probe> generate(String original) {
        return generate(original, defaultKinds());
    }

    public static List<Probe> generate(String original, Set<Kind> kinds) {
        String orig = original == null ? "" : original;
        List<Probe> out = new ArrayList<>();
        for (Kind k : Kind.values()) {          // stable order
            if (!kinds.contains(k)) continue;
            switch (k) {
                case ORIGINAL:      out.add(new Probe(k, orig, false)); break;
                case EMPTY:         out.add(new Probe(k, "", false)); break;
                case OMITTED:       out.add(new Probe(k, null, true)); break;
                case SHORT:         out.add(new Probe(k, "a", false)); break;
                case LONG:          out.add(new Probe(k, "A".repeat(256), false)); break;
                case NUMERIC:       out.add(new Probe(k, "1234567890", false)); break;
                case STRING:        out.add(new Probe(k, "assessmenttest", false)); break;
                case WHITESPACE:    out.add(new Probe(k, "   ", false)); break;
                case BOUNDARY_MIN:  out.add(new Probe(k, "A", false)); break;
                case BOUNDARY_MAX:  out.add(new Probe(k, "A".repeat(1024), false)); break;
                case SPECIAL_CHARS: out.add(new Probe(k, "!@#$%^&*()_+-= .,/", false)); break;
                // Reflection canaries: distinctive, inert markers (no executable payload).
                case QUOTE_CANARY:  out.add(new Probe(k, "anbq7'\"x", false)); break;
                case MARKUP_CANARY: out.add(new Probe(k, "anb<z7>x", false)); break;
                // SQL injection probes (read-only auth/boolean tests, no mutation).
                case SQL_QUOTE:            out.add(new Probe(k, "'", false)); break;
                case SQL_QUOTE_DOUBLED:    out.add(new Probe(k, "''", false)); break;
                case SQL_COMMENT_DASH:     out.add(new Probe(k, "'--", false)); break;
                case SQL_COMMENT_HASH:     out.add(new Probe(k, "'#", false)); break;
                case SQL_OR_TRUE:          out.add(new Probe(k, "' OR '1'='1", false)); break;
                case SQL_OR_TRUE_COMMENT:  out.add(new Probe(k, "' OR 1=1--", false)); break;
                default: break;
            }
        }
        return out;
    }
}
