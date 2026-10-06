package com.assessmentnotebook.analyze;

import com.assessmentnotebook.model.CaptureRule;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Decides which auto-capture rule, if any, applies to an observed exchange.
 * Rules are tried in order and the first enabled one whose conditions all hold
 * wins; an empty condition matches anything.
 *
 * <p>This runs on Burp's HTTP threads for every response, so it looks only at
 * values that are already parsed (URL, method, status, content type) and never
 * at a body.
 */
public final class RuleMatcher {
    private RuleMatcher() {}

    /** The cheap, already-parsed facts about one exchange that rules test. */
    public static final class Facts {
        public String url = "";
        public String method = "";
        public int status;
        /** Response Content-Type header value, or "". */
        public String contentType = "";
        public boolean fromProxy = true;
        public boolean fromRepeater = false;
        /** Asked only when a rule is restricted to scope; may consult Burp. */
        public BooleanSupplier inScope = () -> true;
    }

    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();
    /** Stands in for a pattern that failed to compile: matches nothing. */
    private static final Pattern NEVER = Pattern.compile("(?!)");

    /** The first enabled rule matching {@code f}, or null. */
    public static CaptureRule firstMatch(List<CaptureRule> rules, Facts f) {
        if (rules == null) return null;
        for (CaptureRule r : rules) {
            if (r != null && r.enabled && matches(r, f)) return r;
        }
        return null;
    }

    public static boolean matches(CaptureRule r, Facts f) {
        if (!(r.fromProxy && f.fromProxy) && !(r.fromRepeater && f.fromRepeater)) return false;
        if (!blank(r.methods) && !inList(r.methods, f.method)) return false;
        if (!blank(r.status) && !statusMatches(r.status, f.status)) return false;
        if (!blank(r.host) && !hostMatches(r.host, UrlTemplates.host(f.url))) return false;
        if (!blank(r.path) && !pathMatches(r.path.trim(), f.url)) return false;
        if (!blank(r.contentType) && !containsAny(f.contentType, r.contentType)) return false;
        // Scope last: it is the only condition that calls out to Burp.
        return !r.inScopeOnly || f.inScope.getAsBoolean();
    }

    /** A message describing what is wrong with the rule, or null when it is usable. */
    public static String validate(CaptureRule r) {
        if (!r.fromProxy && !r.fromRepeater) return "Select at least one tool to listen to.";
        String p = r.path == null ? "" : r.path.trim();
        if (p.startsWith("re:")) {
            try {
                Pattern.compile(p.substring(3));
            } catch (PatternSyntaxException e) {
                return "Path regex is invalid: " + e.getDescription();
            }
        }
        if (!blank(r.status)) {
            for (String token : r.status.split(",")) {
                if (!token.isBlank() && statusRange(token.trim()) == null) {
                    return "Status \"" + token.trim() + "\" is not a code (200), "
                            + "a class (3xx) or a range (400-404).";
                }
            }
        }
        return null;
    }

    /** Any of the comma-separated host globs (so "app.test, *.app.test" covers both). */
    private static boolean hostMatches(String patterns, String host) {
        for (String p : patterns.split(",")) {
            if (!p.isBlank() && glob(p.trim(), true).matcher(host).matches()) return true;
        }
        return false;
    }

    private static boolean pathMatches(String pattern, String url) {
        if (pattern.startsWith("re:")) {
            String q = UrlTemplates.query(url);
            String target = UrlTemplates.path(url) + (q.isEmpty() ? "" : "?" + q);
            return regex(pattern.substring(3)).matcher(target).find();
        }
        return glob(pattern, false).matcher(UrlTemplates.path(url)).matches();
    }

    static boolean statusMatches(String spec, int status) {
        for (String token : spec.split(",")) {
            int[] range = statusRange(token.trim());
            if (range != null && status >= range[0] && status <= range[1]) return true;
        }
        return false;
    }

    /** {lo, hi} for "200", "3xx" or "400-404"; null when the token is not one of those. */
    private static int[] statusRange(String token) {
        try {
            String t = token.toLowerCase(Locale.ROOT);
            if (t.matches("[1-5]xx")) {
                int lo = (t.charAt(0) - '0') * 100;
                return new int[]{lo, lo + 99};
            }
            int dash = t.indexOf('-');
            if (dash > 0) {
                return new int[]{Integer.parseInt(t.substring(0, dash).trim()),
                        Integer.parseInt(t.substring(dash + 1).trim())};
            }
            int code = Integer.parseInt(t);
            return new int[]{code, code};
        } catch (RuntimeException notAStatus) {
            return null;
        }
    }

    private static boolean inList(String list, String value) {
        for (String item : list.split(",")) {
            if (item.trim().equalsIgnoreCase(value == null ? "" : value.trim())) return true;
        }
        return false;
    }

    private static boolean containsAny(String haystack, String needles) {
        String h = haystack == null ? "" : haystack.toLowerCase(Locale.ROOT);
        for (String n : needles.split(",")) {
            String needle = n.trim().toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && h.contains(needle)) return true;
        }
        return false;
    }

    /** Compile a glob where {@code *} is any run of characters and {@code ?} is one. */
    private static Pattern glob(String glob, boolean ignoreCase) {
        return COMPILED.computeIfAbsent((ignoreCase ? "gi:" : "g:") + glob, k -> {
            StringBuilder rx = new StringBuilder();
            StringBuilder literal = new StringBuilder();
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                if (c == '*' || c == '?') {
                    if (literal.length() > 0) {
                        rx.append(Pattern.quote(literal.toString()));
                        literal.setLength(0);
                    }
                    rx.append(c == '*' ? ".*" : ".");
                } else {
                    literal.append(c);
                }
            }
            if (literal.length() > 0) rx.append(Pattern.quote(literal.toString()));
            return Pattern.compile(rx.toString(), ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        });
    }

    private static Pattern regex(String rx) {
        return COMPILED.computeIfAbsent("re:" + rx, k -> {
            try {
                return Pattern.compile(rx);
            } catch (PatternSyntaxException bad) {
                return NEVER;
            }
        });
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
