package dev.floor42.server.iam;

import java.time.Instant;

/** An act that waits for a second pair of eyes, as the desk shows it (ADR-0081). */
public record AssuredWaiting(String id, String kind, String ref, String firstSubject, Instant until, String path) {}
