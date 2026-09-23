package com.assessmentnotebook.model;

/**
 * A timestamped, classified note attachable to any entity. This single type is
 * the whole "notes and observations" system: the {@link Kind} preserves the
 * investigative status of a thought (a hunch vs. a confirmed finding vs. a
 * dead end) so the reasoning history survives, not just the conclusions.
 *
 * <p>The "Add Observation" workflow simply creates a Note with a chosen kind.
 */
public class Note {
    public enum Kind {
        OBSERVATION("Observation"),
        HYPOTHESIS("Hypothesis"),
        TODO("TODO"),
        CONFIRMED("Confirmed finding"),
        REJECTED("Rejected hypothesis"),
        NOTE("Note");

        public final String label;
        Kind(String label) { this.label = label; }
    }

    public String id;
    public Kind kind = Kind.OBSERVATION;
    /** The entity this note is about. */
    public EntityType targetType = EntityType.PROJECT;
    public String targetId = "";
    public String text = "";
    public String createdAt;
    public String updatedAt;
}
