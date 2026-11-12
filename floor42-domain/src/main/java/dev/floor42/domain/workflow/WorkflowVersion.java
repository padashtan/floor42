package dev.floor42.domain.workflow;

import java.time.Instant;

/**
 * One save of a company workflow (ADR-0052). Versions are derived, never stored: a workflow's version is the number of
 * its saves since its latest removal, from 1.
 *
 * @param name as the director saved it
 * @param number 1 for the first save since the latest removal
 * @param at when it was saved
 * @param reading the workflow, or the reason this build cannot read that save
 */
public record WorkflowVersion(
        String name, int number, Instant at, int schemaVersion, WorkflowDocument.Reading reading) {}
