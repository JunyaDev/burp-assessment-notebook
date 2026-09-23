package com.assessmentnotebook.store;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Single source of truth for the timestamp format used throughout a project. */
public final class Timestamps {
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Timestamps() {}

    /** Local wall-clock timestamp, e.g. {@code 2026-09-23 17:04:11}. */
    public static String now() {
        return OffsetDateTime.now(ZoneId.systemDefault()).format(FMT);
    }
}
