package dev.floor42.domain;

/**
 * One objection of the director at acceptance (ADR-0060).
 *
 * @param ticket the done work ticket the objection is about; null for new work
 * @param text what the director objects to, or asks for
 */
public record Objection(TicketId ticket, String text) {}
