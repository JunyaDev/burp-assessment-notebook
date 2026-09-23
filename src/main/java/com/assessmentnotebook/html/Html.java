package com.assessmentnotebook.html;

/**
 * Minimal HTML helpers. Everything user- or target-derived is escaped through
 * {@link #esc} before it reaches a generated document, so captured markup can
 * never break out into the documentation itself.
 */
public final class Html {
    private Html() {}

    /** Escape text for element content and quoted attribute values. */
    public static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    /** Escaped text, or an em-dash when the value is blank. */
    public static String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : esc(s);
    }
}
