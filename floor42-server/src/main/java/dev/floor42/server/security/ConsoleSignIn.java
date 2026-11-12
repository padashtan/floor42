package dev.floor42.server.security;

/** Fired when the director signed in with the console link (or its token): the break-glass way in when IAM is on. */
public record ConsoleSignIn(String session) {}
