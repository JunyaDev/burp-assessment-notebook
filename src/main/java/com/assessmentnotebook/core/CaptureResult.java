package com.assessmentnotebook.core;

/** What auto-capture did with one exchange. */
public final class CaptureResult {
    public enum Outcome {
        NEW_PAGE, NEW_ENDPOINT, NEW_RESOURCE,
        /** A known page answered differently; recorded as a page variant. */
        VARIANT,
        /** A known record gained a connection (e.g. another page loads the resource). */
        UPDATED,
        /** Already documented and nothing new to add. */
        DUPLICATE,
        /** Differs from what is documented, but the page already has its limit of variants. */
        CAPPED
    }

    public final Outcome outcome;
    /** Id of the page, variant or resource concerned (may be null for duplicates). */
    public final String entityId;
    public final String detail;

    public CaptureResult(Outcome outcome, String entityId, String detail) {
        this.outcome = outcome;
        this.entityId = entityId;
        this.detail = detail == null ? "" : detail;
    }

    /** True when the notebook now holds something it did not before. */
    public boolean changed() {
        return outcome != Outcome.DUPLICATE && outcome != Outcome.CAPPED;
    }
}
