package dev.floor42.server.iam;

/** CDI signal (not a journal event): Keycloak answered again after an outage, missions that waited can start. */
public record IamRecovered() {}
