package com.assessmentnotebook.analyze;

import com.assessmentnotebook.model.JsFinding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans a JavaScript source (a normal bundle, or a compiled Flutter
 * {@code main.dart.js}) for material worth reviewing: endpoints, hard-coded
 * secrets, dangerous sinks, exported functions and auth-bypass hints. It is a
 * read-only, heuristic pass — it never asserts a vulnerability, it surfaces
 * candidates the tester keeps or discards. Results carry a best-effort line
 * number and a short surrounding excerpt so each one is traceable in the source.
 */
public final class JsScanner {
    private JsScanner() {}

    /** A candidate discovered in the source, before the tester selects it. */
    public static final class Match {
        public final JsFinding.Kind kind;
        public final String value;
        public final String detail;
        public final String context;
        public final int line;
        Match(JsFinding.Kind kind, String value, String detail, String context, int line) {
            this.kind = kind; this.value = value; this.detail = detail;
            this.context = context; this.line = line;
        }
    }

    private static final class Rule {
        final JsFinding.Kind kind; final String detail; final Pattern pattern; final int group;
        Rule(JsFinding.Kind kind, String detail, String regex, int group) {
            this.kind = kind; this.detail = detail; this.group = group;
            this.pattern = Pattern.compile(regex);
        }
    }

    private static final List<Rule> RULES = new ArrayList<>();
    private static void rule(JsFinding.Kind k, String detail, String regex, int group) {
        RULES.add(new Rule(k, detail, regex, group));
    }
    static {
        // --- Endpoints / URLs -------------------------------------------------
        // Stop at quotes, whitespace, backticks, angle brackets, parens and
        // separators so the closing delimiter of a string literal is not swallowed.
        rule(JsFinding.Kind.ENDPOINT, "absolute URL",
                "https?://[^\\s\"'`<>(),;]+", 0);
        rule(JsFinding.Kind.ENDPOINT, "ws/wss URL",
                "wss?://[^\\s\"'`<>(),;]+", 0);
        // Quoted API-ish paths: "/api/..", "/rest/..", "/v1/..", "/graphql".
        rule(JsFinding.Kind.ENDPOINT, "API path literal",
                "[\"'`](/(?:api|rest|v\\d+|graphql|oauth|auth|admin|internal|user|users|account|"
                + "session|token|upload|download|file|files|search|query|rpc|ws)[\\w/%.~:?#()@!$&+,;=-]*)[\"'`]", 1);
        rule(JsFinding.Kind.ENDPOINT, "fetch/xhr target",
                "(?:fetch|axios(?:\\.\\w+)?|\\.open|XMLHttpRequest[^;]{0,40}open)\\s*\\(\\s*[\"'`]([^\"'`]{1,200})[\"'`]", 1);

        // --- Secrets / tokens / passwords ------------------------------------
        rule(JsFinding.Kind.SECRET, "JWT",
                "eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}", 0);
        rule(JsFinding.Kind.SECRET, "AWS access key id", "AKIA[0-9A-Z]{16}", 0);
        rule(JsFinding.Kind.SECRET, "Google API key", "AIza[0-9A-Za-z_-]{35}", 0);
        rule(JsFinding.Kind.SECRET, "Slack token", "xox[baprs]-[0-9A-Za-z-]{10,}", 0);
        rule(JsFinding.Kind.SECRET, "GitHub token", "gh[pousr]_[0-9A-Za-z]{20,}", 0);
        rule(JsFinding.Kind.SECRET, "private key block", "-----BEGIN [A-Z ]*PRIVATE KEY-----", 0);
        rule(JsFinding.Kind.SECRET, "Bearer token", "[Bb]earer\\s+[A-Za-z0-9._-]{12,}", 0);
        // key/secret/password assignments: name : "value" or name = "value".
        rule(JsFinding.Kind.SECRET, "credential assignment",
                "(?i)(?:api[_-]?key|secret|passwd|password|pwd|access[_-]?token|auth[_-]?token|"
                + "client[_-]?secret|private[_-]?key)[\"'`]?\\s*[:=]\\s*[\"'`]([^\"'`\\s]{4,})[\"'`]", 1);

        // --- Dangerous calls / sinks -----------------------------------------
        rule(JsFinding.Kind.DANGEROUS_CALL, "eval", "\\beval\\s*\\(", 0);
        rule(JsFinding.Kind.DANGEROUS_CALL, "Function constructor", "\\bnew\\s+Function\\s*\\(", 0);
        rule(JsFinding.Kind.DANGEROUS_CALL, "string setTimeout/Interval",
                "\\bset(?:Timeout|Interval)\\s*\\(\\s*[\"'`]", 0);
        rule(JsFinding.Kind.DANGEROUS_CALL, "document.write", "document\\.write\\s*\\(", 0);
        rule(JsFinding.Kind.DANGEROUS_CALL, "innerHTML sink", "\\.innerHTML\\s*=", 0);
        rule(JsFinding.Kind.DANGEROUS_CALL, "child_process/exec",
                "(?:child_process|\\bexec(?:Sync|File)?\\s*\\(|\\bspawn\\s*\\()", 0);

        // --- Exported functions ----------------------------------------------
        rule(JsFinding.Kind.EXPORTED_FUNCTION, "module.exports",
                "module\\.exports(?:\\.\\w+)?\\s*=", 0);
        rule(JsFinding.Kind.EXPORTED_FUNCTION, "exports.name", "\\bexports\\.(\\w+)\\s*=", 1);
        rule(JsFinding.Kind.EXPORTED_FUNCTION, "ES export", "\\bexport\\s+(?:default\\s+)?"
                + "(?:async\\s+)?(?:function\\*?|class|const|let|var)\\s+(\\w+)", 1);
        rule(JsFinding.Kind.EXPORTED_FUNCTION, "global assignment",
                "\\b(?:window|globalThis|self)\\.(\\w{3,})\\s*=\\s*(?:async\\s+)?function", 1);

        // --- Bypass / debug flags --------------------------------------------
        rule(JsFinding.Kind.BYPASS, "bypass/backdoor identifier",
                "(?i)\\b(bypass\\w*|backdoor\\w*|skip[_-]?auth\\w*|disable[_-]?(?:security|auth)\\w*|"
                + "no[_-]?auth\\w*|god[_-]?mode|master[_-]?key)\\b", 1);
        rule(JsFinding.Kind.BYPASS, "privilege flag",
                "(?i)[\"'`]?\\b(is[_-]?admin|isAdmin|admin[_-]?only|adminOnly|is[_-]?debug|"
                + "debug[_-]?mode|feature[_-]?flag|test[_-]?mode|sandbox[_-]?mode)\\b", 1);

        // --- Other interesting -----------------------------------------------
        rule(JsFinding.Kind.INTERESTING, "TODO/FIXME/HACK note",
                "(?://|/\\*)\\s*(?:TODO|FIXME|HACK|XXX|BUG)\\b[^\\n*]{0,120}", 0);
        rule(JsFinding.Kind.INTERESTING, "private IP",
                "\\b(?:10\\.\\d{1,3}|192\\.168|172\\.(?:1[6-9]|2\\d|3[01]))\\.\\d{1,3}\\.\\d{1,3}\\b", 0);
    }

    private static final int MAX_BYTES = 16 * 1024 * 1024;   // 16 MB scan ceiling
    private static final int MAX_VALUE = 400;                // truncate long matches
    private static final int MAX_PER_RULE = 500;             // cap noisy rules

    /** Scan the source, returning de-duplicated candidates in a stable order. */
    public static List<Match> scan(String source) {
        List<Match> out = new ArrayList<>();
        if (source == null || source.isEmpty()) return out;
        String text = source.length() > MAX_BYTES ? source.substring(0, MAX_BYTES) : source;
        int[] lineStarts = lineStarts(text);
        // Track (kind|value) so the same secret found by two rules appears once.
        Set<String> seen = new LinkedHashSet<>();
        for (Rule r : RULES) {
            Matcher m = r.pattern.matcher(text);
            int count = 0;
            while (m.find() && count < MAX_PER_RULE) {
                String raw = m.group(r.group);
                if (raw == null || raw.isBlank()) continue;
                String value = truncate(raw.trim());
                String key = r.kind + "|" + value;
                if (!seen.add(key)) continue;
                count++;
                int line = lineOf(lineStarts, m.start());
                out.add(new Match(r.kind, value, r.detail, excerpt(text, m.start(), m.end()), line));
            }
        }
        return out;
    }

    private static String truncate(String s) {
        return s.length() <= MAX_VALUE ? s : s.substring(0, MAX_VALUE) + "…";
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        int[] a = new int[starts.size()];
        for (int i = 0; i < a.length; i++) a[i] = starts.get(i);
        return a;
    }

    private static int lineOf(int[] starts, int offset) {
        int lo = 0, hi = starts.length - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= offset) { ans = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return ans + 1;
    }

    /** A short window around the match; minified single-line files still work. */
    private static String excerpt(String text, int start, int end) {
        int from = Math.max(0, start - 30);
        int to = Math.min(text.length(), end + 30);
        String s = text.substring(from, to).replaceAll("\\s+", " ").trim();
        return s.length() <= 160 ? s : s.substring(0, 160) + "…";
    }
}
