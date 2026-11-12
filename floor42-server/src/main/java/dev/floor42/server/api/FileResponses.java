package dev.floor42.server.api;

import dev.floor42.server.app.Deliverables;
import dev.floor42.server.app.Delivered.Blob;
import dev.floor42.server.app.RefusedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * How the daemon sends one file to the viewer panel or as a download (ADR-0051): a deliverable of an agent or one of
 * the director's own files. The type comes from the bytes, never from the name alone: a raster image only when its magic
 * bytes say so, a PDF only when it starts with {@code %PDF-}, an SVG only when its first bytes hold its root element.
 * Every answer carries {@code nosniff} and a Content-Security-Policy as narrow as its type allows.
 */
final class FileResponses {

    private FileResponses() {}

    /** No script, no plugin, no request of its own: the file is data, whatever it holds. */
    static final String SANDBOX = "sandbox; default-src 'none'";

    /**
     * An image (raster or SVG): the same sandbox; the image may draw itself and its own inline styles, and nothing
     * else. In an {@code <img>} a browser runs no script of an SVG and loads nothing it names.
     */
    static final String SANDBOX_IMAGE =
            "sandbox; default-src 'none'; img-src 'self' blob: data:; style-src 'unsafe-inline'";

    /**
     * A PDF: browsers' PDF viewers refuse to render under a {@code sandbox} directive (Chromium blocks the document), so
     * this one keeps every fetch closed and may only be framed by Floor42's own page. The bytes are checked to start
     * with {@code %PDF-} and the type is never sniffed: the viewer is the browser's, never an HTML page of the daemon.
     */
    static final String PDF = "default-src 'none'; object-src 'self'; frame-ancestors 'self'";

    /** A Markdown file keeps its own type, shown or downloaded; the sandbox and nosniff stay. */
    static final String MARKDOWN = "text/markdown; charset=UTF-8";

    /** How far into an SVG its root element is looked for: past an XML declaration, a doctype and comments. */
    static final int SVG_HEAD = 4096;

    /**
     * What the viewer would get, said without the bytes: always a 200, so the page asks before it puts a file in an
     * {@code <img>} or a frame, and a refusal never shows as a failed load (ADR-0051).
     *
     * @param viewable the file would be shown inline
     * @param type the type it would go out as; null when refused
     * @param code why not ({@code NOT_VIEWABLE}, {@code TOO_LARGE_TO_VIEW}); null when viewable
     */
    record ViewCheck(boolean viewable, String type, String code, String message) {}

    /** How a viewable file goes out: its type, its policy, its disposition. */
    private record Shown(String type, String csp, String how) {}

    /**
     * A HEAD: the headers the GET would send, and no body. RESTEasy would route HEAD to the GET method and never write
     * its body, so the file opened for the check would stay open; here it is released before the answer leaves.
     */
    static Response head(Blob blob, boolean download, boolean check) {
        try {
            if (check) return Response.ok().type("application/json").build();
            if (download) return headers(Response.ok(), blob, downloadType(blob), SANDBOX, "attachment");
            Shown s = shown(blob);
            return headers(Response.ok(), blob, s.type(), s.csp(), s.how());
        } finally {
            blob.release();
        }
    }

    /** The file shown, downloaded, or only checked; the blob is released when no body will send it. */
    static Response of(Blob blob, boolean download, boolean check) {
        try {
            if (check) return Response.ok(check(blob), "application/json").build();
            if (download) return send(blob, downloadType(blob), SANDBOX, "attachment");
            Shown s = shown(blob);
            return send(blob, s.type(), s.csp(), s.how());
        } catch (RuntimeException e) {
            blob.release();
            throw e;
        } finally {
            if (check) blob.release();
        }
    }

    private static ViewCheck check(Blob blob) {
        try {
            return new ViewCheck(true, shown(blob).type(), null, null);
        } catch (RefusedException e) {
            // Handled: the refusal is the answer the viewer asked for, said in a 200
            return new ViewCheck(false, null, e.code(), e.getMessage());
        }
    }

    /** The file as the viewer may show it, by its name and its bytes; refused when it is neither (Download stays). */
    private static Shown shown(Blob blob) {
        String kind = Deliverables.kind(blob.name());
        String image = imageType(blob.head());
        if (kind.equals("IMAGE") && image != null) {
            cap(blob, Deliverables.MAX_VIEW_BYTES);
            return new Shown(image, SANDBOX_IMAGE, "inline");
        }
        if (kind.equals("IMAGE") && svgName(blob.name())) {
            cap(blob, Deliverables.MAX_VIEW_BYTES);
            if (!svg(blob)) throw notViewable(blob);
            // An image only: in an <img> a browser runs no script and loads nothing; opened on its own, the
            // attachment disposition downloads it and the sandbox would block any script anyway.
            return new Shown("image/svg+xml", SANDBOX_IMAGE, "attachment");
        }
        if (kind.equals("PDF") && startsWith(blob.head(), "%PDF-")) {
            cap(blob, Deliverables.MAX_VIEW_BYTES);
            return new Shown("application/pdf", PDF, "inline");
        }
        if (kind.equals("TEXT")) {
            cap(blob, Deliverables.MAX_VIEW_TEXT);
            if (binary(blob)) throw notViewable(blob);
            // HTML and scripts included: shown as text, never rendered by the daemon's origin.
            return new Shown(markdown(blob.name()) ? MARKDOWN : "text/plain; charset=utf-8", SANDBOX, "inline");
        }
        throw notViewable(blob);
    }

    private static String downloadType(Blob blob) {
        return markdown(blob.name()) ? MARKDOWN : "application/octet-stream";
    }

    private static boolean markdown(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    static Response send(Blob blob, String type, String csp, String how) {
        StreamingOutput body = out -> {
            // From the channel opened at the check, cut at the size measured then (BlobBytes).
            try {
                blob.copyTo(out);
            } finally {
                blob.release();
            }
        };
        // No Content-Length: a streamed body goes out chunked, and both framings at once (RFC 9112) make strict
        // clients (Node's parser) refuse the answer.
        return headers(Response.ok(body), blob, type, csp, how);
    }

    private static Response headers(Response.ResponseBuilder r, Blob blob, String type, String csp, String how) {
        return r.type(type)
                .header("Content-Disposition", disposition(how, blob.name()))
                .header("Content-Security-Policy", csp)
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .header("Cache-Control", "no-store")
                .build();
    }

    private static void cap(Blob blob, long max) {
        if (blob.size() > max) {
            throw RefusedException.tooLarge(
                    "TOO_LARGE_TO_VIEW", blob.name() + " is larger than " + (max / 1024) + " KB to view: download it");
        }
    }

    private static RefusedException notViewable(Blob blob) {
        return new RefusedException(415, "NOT_VIEWABLE", blob.name() + " cannot be shown here: download it");
    }

    /** A text holds no NUL byte; read within the view cap. */
    private static boolean binary(Blob blob) {
        try {
            byte[] bytes = blob.first((int) Deliverables.MAX_VIEW_TEXT);
            for (byte b : bytes) if (b == 0) return true;
            return false;
        } catch (IOException e) {
            // Handled: a file that cannot be read is treated as binary: never shown inline
            return true;
        }
    }

    private static boolean svgName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".svg");
    }

    /** An SVG is text (no NUL byte) whose first {@value #SVG_HEAD} bytes hold its root element. */
    static boolean svg(Blob blob) {
        try {
            byte[] bytes = blob.first(SVG_HEAD);
            for (byte b : bytes) if (b == 0) return false;
            return new String(bytes, StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .contains("<svg");
        } catch (IOException e) {
            // Handled: a file that cannot be read is no image to show: it is offered for download only
            return false;
        }
    }

    /** The image type the bytes really are, or null. */
    static String imageType(byte[] h) {
        if (h.length >= 8 && (h[0] & 0xff) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "image/png";
        if (h.length >= 3 && (h[0] & 0xff) == 0xff && (h[1] & 0xff) == 0xd8 && (h[2] & 0xff) == 0xff)
            return "image/jpeg";
        if (startsWith(h, "GIF87a") || startsWith(h, "GIF89a")) return "image/gif";
        if (h.length >= 12 && startsWith(h, "RIFF") && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] h, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
        if (h.length < p.length) return false;
        for (int i = 0; i < p.length; i++) if (h[i] != p[i]) return false;
        return true;
    }

    /** A filename only of safe characters, and the real one percent-encoded: no quote or line break reaches a header. */
    static String disposition(String how, String name) {
        String safe = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 120) safe = safe.substring(safe.length() - 120);
        if (safe.isBlank() || safe.matches("[._]*")) safe = "file";
        String encoded = URLEncoder.encode(name == null ? safe : name, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return how + "; filename=\"" + safe + "\"; filename*=UTF-8''" + encoded;
    }
}
