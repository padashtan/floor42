package dev.floor42.server.iam;

import dev.floor42.server.iam.ResourceCatalogue.Resource;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * The only way a brokered call gets its address: the catalogue's base URL plus an allowed path prefix, nothing the
 * agent writes can change the scheme, host or port. The host is resolved and every address checked before the call.
 * {@code IllegalArgumentException} carries the refusal code.
 */
@Singleton
public class SafeUrls {

    /** Name to addresses; replaced in tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Resolver resolver;

    @Inject
    public SafeUrls() {
        this(InetAddress::getAllByName);
    }

    public SafeUrls(Resolver resolver) {
        this.resolver = resolver;
    }

    /** The URI as the catalogue names it, the checked address to connect to, and the real host name. */
    public record Target(URI uri, InetAddress address, String host) {}

    /** The address to call for {@code method} on {@code path} (with its query) of the resource. */
    public URI build(Resource r, String method, String path) {
        return resolve(r, method, path).uri();
    }

    /** As {@link #build}, and the one address checked: the call must connect to it, not resolve the name again. */
    public Target resolve(Resource r, String method, String path) {
        if (method == null || !r.methods().contains(method.toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("METHOD_NOT_ALLOWED");
        String scheme = r.baseUrl().getScheme();
        String host = r.baseUrl().getHost();
        if (host == null) throw new IllegalArgumentException("HOST_NOT_ALLOWED");
        boolean https = "https".equalsIgnoreCase(scheme);
        if (!https && !("http".equalsIgnoreCase(scheme) && r.local()))
            throw new IllegalArgumentException("SCHEME_NOT_ALLOWED");
        URI uri = target(r, path);
        return new Target(uri, addresses(r, host), host);
    }

    private static URI target(Resource r, String path) {
        if (path == null || !path.startsWith("/") || !clean(path))
            throw new IllegalArgumentException("PATH_NOT_ALLOWED");
        int q = path.indexOf('?');
        String bare = q < 0 ? path : path.substring(0, q);
        if (bare.contains("//") || r.pathPrefixes().stream().noneMatch(p -> under(bare, p)))
            throw new IllegalArgumentException("PATH_NOT_ALLOWED");
        URI base = r.baseUrl();
        try {
            URI uri = new URI(base.getScheme() + "://" + base.getRawAuthority() + path);
            if (!base.getScheme().equalsIgnoreCase(uri.getScheme())
                    || !base.getHost().equalsIgnoreCase(uri.getHost())
                    || base.getPort() != uri.getPort()
                    || uri.getRawUserInfo() != null) throw new IllegalArgumentException("PATH_NOT_ALLOWED");
            return uri;
        } catch (java.net.URISyntaxException e) {
            // Handled: not a valid address, so not allowed
            throw new IllegalArgumentException("PATH_NOT_ALLOWED", e);
        }
    }

    /** Printable ASCII only (no control, space or non-ASCII), and none of the sequences that move the path. */
    private static boolean clean(String path) {
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == '\\' || c == '@' || c == '#') return false;
        }
        String low = path.toLowerCase(Locale.ROOT);
        return !low.contains("..")
                && !low.contains("%2e")
                && !low.contains("%2f")
                && !low.contains("%5c")
                && !low.contains("%25")
                && !low.contains("%00");
    }

    private static boolean under(String path, String prefix) {
        return prefix.endsWith("/") ? path.startsWith(prefix) : path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private InetAddress addresses(Resource r, String host) {
        InetAddress[] all;
        try {
            all = resolver.resolve(host);
        } catch (UnknownHostException e) {
            // Handled: a host that does not resolve is not callable
            throw new IllegalArgumentException("HOST_NOT_ALLOWED", e);
        }
        if (all.length == 0) throw new IllegalArgumentException("HOST_NOT_ALLOWED");
        for (InetAddress a : all) {
            boolean loop = a.isLoopbackAddress();
            if (loop ? !r.local() : r.local() || !publicAddress(a))
                throw new IllegalArgumentException("HOST_NOT_ALLOWED");
        }
        return all[0];
    }

    /**
     * Not private, link-local, multicast, unspecified, carrier-grade NAT, reserved or IPv6 unique-local; an IPv4
     * address inside an IPv6 one (compatible, NAT64, 6to4) is judged as that IPv4 address.
     */
    static boolean publicAddress(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress())
            return false;
        byte[] b = a.getAddress();
        if (b.length == 4) return publicV4(b[0] & 0xff, b[1] & 0xff, b[2] & 0xff);
        if ((b[0] & 0xfe) == 0xfc) return false;
        if (zeros(b, 0, 12)) return false; // ::a.b.c.d (IPv4-compatible), ::, ::1
        if (b[0] == 0 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b)
            return zeros(b, 4, 12) && publicV4(b[12] & 0xff, b[13] & 0xff, b[14] & 0xff); // 64:ff9b::/96
        if (b[0] == 0x20 && b[1] == 0x02) return publicV4(b[2] & 0xff, b[3] & 0xff, b[4] & 0xff); // 6to4
        return !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0); // Teredo 2001::/32
    }

    private static boolean zeros(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) if (b[i] != 0) return false;
        return true;
    }

    private static boolean publicV4(int a, int b, int c) {
        return !(a == 0
                || a == 10
                || a == 127
                || a >= 240
                || (a == 100 && (b & 0xc0) == 64)
                || (a == 169 && b == 254)
                || (a == 172 && (b & 0xf0) == 16)
                || (a == 192 && b == 168)
                || (a == 192 && b == 0 && c == 0)
                || (a == 198 && (b & 0xfe) == 18));
    }
}
