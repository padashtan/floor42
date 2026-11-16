package dev.floor42.server.security;

/** What a request presents, read from its raw headers: the one reading, for the guard and for the sockets. */
public final class RequestCredentials {

    private RequestCredentials() {}

    /** @param bearer true for an Authorization bearer (a script token or the director's), false for the cookie */
    public record Credential(boolean bearer, String secret) {}

    /** The bearer secret of an Authorization header; null when there is none. */
    public static String bearerOf(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }

    /** The session cookie of a Cookie header; null when there is none. */
    public static String cookieOf(String header) {
        if (header == null) return null;
        for (io.netty.handler.codec.http.cookie.Cookie c :
                io.netty.handler.codec.http.cookie.ServerCookieDecoder.STRICT.decode(header)) {
            if (c.name().equals(LocalAccessGuard.COOKIE)) return c.value();
        }
        return null;
    }

    /** The credential of a request: a bearer wins over a cookie, as in the guard; null when it carries neither. */
    public static Credential credentialOf(String authorization, String cookieHeader) {
        String bearer = bearerOf(authorization);
        if (bearer != null) return new Credential(true, bearer);
        String cookie = cookieOf(cookieHeader);
        return cookie == null ? null : new Credential(false, cookie);
    }
}
