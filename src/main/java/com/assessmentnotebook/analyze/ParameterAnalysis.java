package com.assessmentnotebook.analyze;

import com.assessmentnotebook.model.ParameterTest;

import java.util.List;

/**
 * Turns raw probe responses into conservative observations (spec §14, §16). It
 * never declares a vulnerability: it describes what changed relative to the
 * baseline and, at most, flags a reflection as a <i>potential</i> issue for the
 * tester to review and promote.
 */
public final class ParameterAnalysis {
    private ParameterAnalysis() {}

    /** Fill {@code observation} and {@code classification} from the recorded numbers. */
    public static void characterize(ParameterTest t) {
        StringBuilder obs = new StringBuilder();
        boolean statusChanged = t.responseStatus != t.baselineStatus;
        boolean lengthChanged = t.responseLength != t.baselineLength;

        if (statusChanged) {
            obs.append("status ").append(t.baselineStatus).append("->").append(t.responseStatus)
               .append("; ");
        }
        if (lengthChanged) {
            obs.append("length ").append(t.baselineLength).append("->").append(t.responseLength)
               .append("; ");
        }
        if (t.reflected) obs.append("value reflected in response; ");

        if (t.responseStatus >= 400 && t.responseStatus < 500) {
            obs.append("rejected with client error; ");
        }
        if (!statusChanged && !lengthChanged && !t.reflected) {
            obs.append("no observable change from baseline; ");
        }

        t.observation = obs.toString().trim();
        // Only a reflection canary that actually reflects is worth flagging as a
        // potential issue; everything else is a neutral observation.
        boolean canary = "QUOTE_CANARY".equals(t.probeKind) || "MARKUP_CANARY".equals(t.probeKind);
        t.classification = (t.reflected && canary)
                ? ParameterTest.Classification.POTENTIAL_ISSUE
                : ParameterTest.Classification.OBSERVED_BEHAVIOR;
    }

    /** A per-parameter summary across all its probe results (spec §14). */
    public static final class Summary {
        public boolean acceptsEmpty;
        public boolean acceptsOmitted;
        public boolean required;         // omitting/emptying yields a client error
        public boolean reflected;
        public boolean changesStatus;
        public boolean changesLength;
        public boolean lengthRestricted; // long value rejected / truncated response
    }

    public static Summary summarize(List<ParameterTest> tests) {
        Summary s = new Summary();
        Integer baseStatus = null;
        Integer baseLength = null;
        for (ParameterTest t : tests) {
            if ("ORIGINAL".equals(t.probeKind)) { baseStatus = t.responseStatus; baseLength = t.responseLength; }
        }
        for (ParameterTest t : tests) {
            if (t.reflected) s.reflected = true;
            if (baseStatus != null && t.responseStatus != baseStatus) s.changesStatus = true;
            if (baseLength != null && t.responseLength != baseLength) s.changesLength = true;
            boolean clientError = t.responseStatus >= 400 && t.responseStatus < 500;
            switch (t.probeKind) {
                case "EMPTY":
                    s.acceptsEmpty = !clientError;
                    if (clientError) s.required = true;
                    break;
                case "OMITTED":
                    s.acceptsOmitted = !clientError;
                    if (clientError) s.required = true;
                    break;
                case "LONG":
                case "BOUNDARY_MAX":
                    if (clientError) s.lengthRestricted = true;
                    break;
                default: break;
            }
        }
        return s;
    }
}
