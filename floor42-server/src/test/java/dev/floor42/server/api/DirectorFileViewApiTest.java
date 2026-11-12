package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import dev.floor42.server.app.BlobBytes;
import dev.floor42.server.security.SessionRegistry;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The director's own pending files in the viewer panel (ADR-0051): only a pending path of the mission's project,
 * shown under the same rules as a deliverable, and only to the director or a token of scope all.
 */
@QuarkusTest
class DirectorFileViewApiTest {

    @Inject
    SessionToken token;

    @Inject
    SessionRegistry sessions;

    @TempDir
    Path dir;

    io.restassured.specification.RequestSpecification api() {
        return given().header("Authorization", "Bearer " + token.value());
    }

    void refused(String url, int status, String code) {
        api().get(url).then().statusCode(status).body("code", equalTo(code));
    }

    /** A project with one committed file, and a mission on it: its id. */
    String mission(Path repo) throws Exception {
        Files.createDirectory(repo);
        DirectorFilesApiTest.git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("kept.txt"), "committed\n");
        DirectorFilesApiTest.git(repo, "add", ".");
        DirectorFilesApiTest.git(repo, "-c", "user.name=T", "-c", "user.email=t@t", "commit", "-q", "-m", "start");
        return api().contentType("application/json")
                .body("{\"title\": \"Album\", \"objective\": \"Sort photos\", \"repository\": \""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    /** Refused with `status` and exactly `code`, and the answer never holds the secret. */
    void refusedExactly(io.restassured.specification.RequestSpecification as, String url, int status, String code) {
        String body = as.get(url).then().statusCode(status).extract().asString();
        assertThat(body).doesNotContain("TOP-SECRET");
        assertThat(io.restassured.path.json.JsonPath.from(body).getString("code"))
                .as(url)
                .isEqualTo(code);
    }

    /** A Windows junction, made without the privilege a symbolic link needs. */
    static void junction(Path at, Path target) throws Exception {
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", at.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).as("mklink /J").isZero();
    }

    @Test
    void linksStreamsAndNarrowTokensNeverReachTheBytes() throws Exception {
        Path repo = dir.resolve("links");
        String base = "/api/missions/" + mission(repo) + "/files";
        Path outside = Files.createDirectory(dir.resolve("elsewhere"));
        Files.writeString(outside.resolve("secret.txt"), "TOP-SECRET\n");
        Files.createDirectories(repo.resolve("in/real"));
        Files.writeString(repo.resolve("in/real/x.txt"), "TOP-SECRET\n");
        Files.writeString(repo.resolve("in/notes.md"), "# Notes\n");
        // A pending file whose folder is swapped for a junction to another folder of the project before the read: git
        // still lists the path, Confinement accepts it (its real path stays inside), and the read's link guard refuses
        // it. Junctions are Windows'; elsewhere a linked folder is one entry for git, never a pending file under it.
        if (WINDOWS) {
            Files.createDirectories(repo.resolve("in/swap"));
            Files.writeString(repo.resolve("in/swap/x.txt"), "plain\n");
            api().get(base + "/file?path=in/swap/x.txt").then().statusCode(200);
            Files.delete(repo.resolve("in/swap/x.txt"));
            Files.delete(repo.resolve("in/swap"));
            junction(repo.resolve("in/swap"), repo.resolve("in/real"));
            junction(repo.resolve("in/door"), outside);
            // The entry names it a link, which the panel says instead of reading it; the bytes are refused.
            api().get(base + "/entry?path=in/swap/x.txt").then().statusCode(200).body("file.link", equalTo(true));
            refusedExactly(api(), base + "/entry?path=in/door/secret.txt", 400, "OUTSIDE_WORKSPACE");
            for (String route : new String[] {"/file?path=", "/file?check=true&path="}) {
                refusedExactly(api(), base + route + "in/swap/x.txt", 400, "LINK_REFUSED");
                // Out of the project through a junction: Confinement refuses it before the read.
                refusedExactly(api(), base + route + "in/door/secret.txt", 400, "OUTSIDE_WORKSPACE");
            }
            // An alternate data stream (NTFS), written by cmd: Java refuses a colon in a path, and so does the route.
            Process p = new ProcessBuilder("cmd", "/c", "echo TOP-SECRET>" + repo.resolve("in/notes.md") + ":hidden")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            assertThat(p.waitFor()).as("stream written").isZero();
        }
        // Windows refuses a colon in a path (an NTFS stream); elsewhere it is an ordinary character, and no such file
        // is
        // a pending file of the project: refused all the same, with that reason.
        String noSuchStream = WINDOWS ? "OUTSIDE_WORKSPACE" : "NOT_A_PENDING_FILE";
        for (String route : new String[] {"/file?path=", "/file?check=true&path="}) {
            refusedExactly(api(), base + route + "in/notes.md:hidden", 400, noSuchStream);
            refusedExactly(api(), base + route + "in/notes.md::$DATA", 400, noSuchStream);
        }

        // A PNG header followed by HTML: an image by its bytes, sent as one, never as a page.
        byte[] png = FileResponsesTest.PNG;
        byte[] html = "<html><script>window.pwned=1</script></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] poly = java.util.Arrays.copyOf(png, png.length + html.length);
        System.arraycopy(html, 0, poly, png.length, html.length);
        Files.write(repo.resolve("in/poly.png"), poly);
        api().get(base + "/file?path=in/poly.png")
                .then()
                .statusCode(200)
                .contentType("image/png")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", startsWith("sandbox; default-src 'none'; img-src"));

        // Narrow tokens never read the director's files, nor ask what they would get.
        for (SessionRegistry.Scope scope :
                new SessionRegistry.Scope[] {SessionRegistry.Scope.MISSIONS_WRITE, SessionRegistry.Scope.READ}) {
            String secret = sessions.issueScript(scope, 1, "viewer", "test").secret();
            for (String route : new String[] {"/file?path=", "/file?check=true&path="}) {
                refusedExactly(
                        given().header("Authorization", "Bearer " + secret),
                        base + route + "in/notes.md",
                        403,
                        "SCOPE");
            }
        }
    }

    @Test
    void aHeadAnswersTheHeadersAndLeavesNoFileOpen() throws Exception {
        Path repo = dir.resolve("head");
        String base = "/api/missions/" + mission(repo) + "/files/file?path=in/photo.png";
        Files.createDirectories(repo.resolve("in"));
        Files.write(repo.resolve("in/photo.png"), FileResponsesTest.PNG);
        LiveTestSupport.await("no file left open", java.time.Duration.ofSeconds(5), () -> BlobBytes.openNow() == 0);
        api().head(base).then().statusCode(200).contentType("image/png").header("X-Content-Type-Options", "nosniff");
        api().head(base + "&download=true").then().statusCode(200).contentType("application/octet-stream");
        api().head(base + "&check=true").then().statusCode(200);
        assertThat(BlobBytes.openNow()).as("files left open by HEAD").isZero();
    }

    @Test
    void aPendingFileIsShownByItsBytesAndNothingElseIsRead() throws Exception {
        Path repo = dir.resolve("photos");
        String m = mission(repo);
        Files.createDirectories(repo.resolve("in"));
        Files.write(repo.resolve("in/photo.png"), FileResponsesTest.PNG);
        Files.writeString(repo.resolve("in/trap.png"), "<html><script>window.pwned=1</script></html>");
        Files.writeString(repo.resolve("in/notes.md"), "# Notes\n");
        Path outside = Files.createDirectory(dir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret\n");
        String base = "/api/missions/" + m + "/files";

        api().get(base + "/entry?path=in/photo.png")
                .then()
                .statusCode(200)
                .body("file.kind", equalTo("IMAGE"))
                .body("file.size", equalTo(FileResponsesTest.PNG.length))
                .body("file.link", equalTo(false))
                .body("maxViewBytes", equalTo((int) dev.floor42.server.app.Deliverables.MAX_VIEW_BYTES));
        api().get(base + "/file?path=in/photo.png")
                .then()
                .statusCode(200)
                .contentType("image/png")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", startsWith("sandbox; default-src 'none'; img-src"));
        // One framing only (RFC 9112): a strict client (Node's) refuses a Content-Length beside a chunked body.
        var framed = api().header("Accept-Encoding", "gzip").get(base + "/file?path=in/photo.png");
        org.assertj.core.api.Assertions.assertThat(
                        framed.header("Content-Length") != null && framed.header("Transfer-Encoding") != null)
                .as("Content-Length and Transfer-Encoding together")
                .isFalse();
        // Markdown keeps its own type here too: one set of file responses for both viewers (FileResponses).
        api().get(base + "/file?path=in/notes.md")
                .then()
                .statusCode(200)
                .contentType("text/markdown;charset=UTF-8")
                .body(equalTo("# Notes\n"));
        api().get(base + "/file?path=in/trap.png&download=true")
                .then()
                .statusCode(200)
                .contentType("application/octet-stream")
                .header("Content-Disposition", startsWith("attachment;"));
        // HTML named .png: never rendered, never sent as an image; the viewer's check says so in a 200.
        refused(base + "/file?path=in/trap.png", 415, "NOT_VIEWABLE");
        api().get(base + "/file?path=in/trap.png&check=true")
                .then()
                .statusCode(200)
                .body("viewable", equalTo(false))
                .body("code", equalTo("NOT_VIEWABLE"));
        api().get(base + "/file?path=in/photo.png&check=true")
                .then()
                .statusCode(200)
                .body("viewable", equalTo(true))
                .body("type", equalTo("image/png"));
        // Committed, outside the project, git's own data, the project itself: refused.
        refused(base + "/file?path=kept.txt", 400, "NOT_A_PENDING_FILE");
        refused(base + "/file?path=../outside/secret.txt", 400, "OUTSIDE_WORKSPACE");
        refused(
                base + "/file?path=" + outside.resolve("secret.txt").toString().replace('\\', '/'),
                400,
                "OUTSIDE_WORKSPACE");
        refused(base + "/file?path=.git/config", 400, "OUTSIDE_WORKSPACE");
        refused(base + "/entry?path=.", 400, "NOT_A_FILE");

        // A read-only script token lists the paths but never reads the director's bytes.
        String read = sessions.issueScript(SessionRegistry.Scope.READ, 1, "viewer", "test")
                .secret();
        given().header("Authorization", "Bearer " + read)
                .get(base + "/file?path=in/photo.png")
                .then()
                .statusCode(403)
                .body("code", equalTo("SCOPE"));
        given().header("Authorization", "Bearer " + read)
                .get(base + "/entry?path=in/photo.png")
                .then()
                .statusCode(403);
    }
}
