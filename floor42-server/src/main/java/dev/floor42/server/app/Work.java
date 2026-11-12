package dev.floor42.server.app;

/** What a task does: produce or plan a ticket, review it, answer the reviewer's request, or run the committee. */
enum Work {
    PRODUCE,
    PLAN,
    REVIEW,
    CONSULT,
    COMMITTEE,
    /** One of the director's workflow tasks, worked as a whole by its role (ADR-0061). */
    TASK,
    /** No session: ask the director about a ticket whose dependencies were done on other branches (D10). */
    ASK_DEPENDENCY
}
