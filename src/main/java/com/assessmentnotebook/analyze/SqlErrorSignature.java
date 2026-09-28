package com.assessmentnotebook.analyze;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Detects database-error fingerprints in a response body. A probe that pushes a
 * lone quote into a parameter and gets one of these back is strong evidence the
 * value reaches a SQL query unparameterized. The list covers the common engines
 * (SQLite, MySQL/MariaDB, PostgreSQL, SQL Server, Oracle) plus the generic ORM
 * wrappers. Matching is read-only and never asserts exploitability by itself.
 */
public final class SqlErrorSignature {
    private SqlErrorSignature() {}

    private static final Pattern[] SIGNATURES = {
        Pattern.compile("sqlite_error|sqlite3?::|unrecognized token|unterminated"
                + "\\s+quoted\\s+string"),
        Pattern.compile("you have an error in your sql syntax|mysql_fetch|"
                + "com\\.mysql\\.jdbc|mariadb"),
        Pattern.compile("pg::|postgresql|psqlexception|syntax error at or near|"
                + "unterminated quoted string at or near"),
        Pattern.compile("microsoft (odbc|ole db|sql server)|"
                + "unclosed quotation mark|incorrect syntax near|system\\.data\\.sqlclient"),
        Pattern.compile("ora-\\d{5}|oracle error|quoted string not properly terminated"),
        Pattern.compile("sequelize[a-z]*error|sqlexception|jdbc|"
                + "syntaxerrorexception|native sql exception"),
    };

    /** True if the body carries any known SQL-error fingerprint. */
    public static boolean matches(String body) {
        if (body == null || body.isBlank()) return false;
        String low = body.toLowerCase(Locale.ROOT);
        for (Pattern p : SIGNATURES) if (p.matcher(low).find()) return true;
        return false;
    }
}
