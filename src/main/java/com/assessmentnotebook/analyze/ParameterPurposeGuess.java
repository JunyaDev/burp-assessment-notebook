package com.assessmentnotebook.analyze;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Suggests what a parameter is probably for, from its name alone. It is a
 * starting point for the tester's own annotation — offered on request, never
 * written to the notebook unless the tester keeps it.
 */
public final class ParameterPurposeGuess {
    private ParameterPurposeGuess() {}

    private static final Map<Pattern, String> RULES = new LinkedHashMap<>();

    private static void rule(String regex, String purpose) {
        RULES.put(Pattern.compile(regex), purpose);
    }

    static {
        // Matched against the last path segment of the name, lower-cased; first hit wins.
        rule("csrf|xsrf|authenticity|requestverification|nonce", "Anti-CSRF token");
        rule("pass(word|wd)?|pwd|secret|(^|_)pin$", "Credential (secret)");
        rule("otp|mfa|2fa|totp|verification_?code", "One-time / MFA code");
        rule("refresh_?token", "Refresh token");
        rule("token|jwt|api_?key|access_?key|session|(^|_)sid$|^auth|authorization",
                "Session / access token");
        rule("content_?type|mime", "Content type");
        rule("user(name)?$|login|email|e_?mail|account$", "Account identifier");
        rule("^(q|query|search|keyword|term|filter)s?$", "Search / filter text");
        rule("page|offset|limit|per_?page|size$|cursor|^skip$|^take$", "Pagination");
        rule("sort|order(_?by)?$|direction$", "Sorting");
        rule("redirect|return(_?(url|to))?$|^next$|^continue$|callback|goto|^dest",
                "Redirect / return target");
        rule("url$|uri$|link$|href$|endpoint$|webhook", "URL (check for SSRF / open redirect)");
        rule("file|upload|attachment|document|image$|avatar|photo", "File reference / upload");
        rule("path$|(^|_)dir$|folder|template", "Filesystem path / template name");
        rule("role|admin|^is_[a-z_]+$|permission|scope$|group$|privilege",
                "Authorization attribute");
        rule("price|amount|total|cost|qty|quantity|discount|coupon|balance",
                "Monetary / quantity value");
        rule("(^|_)(id|uuid|guid)$|(user|account|order|item|product|object|doc|record)id$",
                "Object identifier (check for IDOR)");
        rule("lang|locale|currency|country|(^|_)tz$|timezone", "Locale preference");
        rule("(^|_)date(_|$)|datetime|timestamp|time$|^(from|to|since|until)$|(^|_)(start|end)(_|$)",
                "Date / time range");
        rule("debug|^test$|verbose|trace|mode$|(^|_)env$", "Debug / mode switch");
        rule("action|cmd|command|^op$|operation|method$|type$", "Operation selector");
        rule("phone|mobile|address|zip|postal|name$|dob|birth", "Personal data");
        rule("comment|message|body|content|text|description|title|note",
                "Free text (stored content)");
    }

    /** A suggested purpose for the parameter name, or "" when nothing fits. */
    public static String guess(String name) {
        if (name == null || name.isBlank()) return "";
        // settings.user.email and items[0].price are judged by their last segment.
        String leaf = name.replaceAll("\\[\\d*]", "");
        int dot = leaf.lastIndexOf('.');
        if (dot >= 0) leaf = leaf.substring(dot + 1);
        // Split camelCase so userId reads as user_id.
        leaf = leaf.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT)
                .replace('-', '_');
        for (Map.Entry<Pattern, String> e : RULES.entrySet()) {
            if (e.getKey().matcher(leaf).find()) return e.getValue();
        }
        return "";
    }
}
