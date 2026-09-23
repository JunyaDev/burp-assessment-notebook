package com.assessmentnotebook.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A single input to a {@link Form} (or, more generally, a request parameter).
 *
 * <p>A parameter is a first-class entity so observations, reflections and
 * findings can point at it directly. Reflections are embedded here because a
 * reflection has no meaning apart from the parameter whose value reflected.
 */
public class Parameter {
    public String id;
    public String name = "";
    /** Input type as declared in HTML (text, password, hidden, file, ...). */
    public String inputType = "";
    public String defaultValue = "";
    public boolean required = false;
    /** Distinct values the tester has seen submitted or returned. */
    public List<String> observedValues = new ArrayList<>();
    /** What the parameter appears to be for, in the tester's words. */
    public String purpose = "";
    /** Observed reflections of this parameter's value. Not auto-classified. */
    public List<Reflection> reflections = new ArrayList<>();
    public String notes = "";
    public String createdAt;
    public String updatedAt;
}
