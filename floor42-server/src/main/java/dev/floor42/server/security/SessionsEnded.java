package dev.floor42.server.security;

/** Fired when a browser session was ended behind the director's back (a sign-in link used twice): sockets follow. */
public record SessionsEnded() {}
