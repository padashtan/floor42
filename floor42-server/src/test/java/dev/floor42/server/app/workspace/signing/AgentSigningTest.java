package dev.floor42.server.app.workspace.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.floor42.domain.AgentId;
import dev.floor42.server.app.workspace.signing.AgentSigning.Verdict;
import dev.floor42.server.runtime.Git;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real git and ssh-keygen in a temp repository; skipped where ssh-keygen is not installed. */
class AgentSigningTest {

    private static final AgentId ADA = new AgentId("ada");

    @TempDir
    Path tmp;

    private final Git git = new Git();
    private AgentSigning signing;
    private Path repo;

    @BeforeEach
    void setUp() throws Exception {
        signing = new AgentSigning(tmp.resolve("data"), git);
        repo = Files.createDirectory(tmp.resolve("repo"));
        git.out(repo, "init", "-q");
        git.out(
                repo,
                "-c",
                "user.name=t",
                "-c",
                "user.email=t@t.invalid",
                "commit",
                "-q",
                "--allow-empty",
                "-m",
                "base");
        assumeTrue(!signing.signArgs(ADA).isEmpty(), "ssh-keygen is not available");
    }

    private String commit(AgentSigning by, AgentId agent, String message) {
        List<String> args = new ArrayList<>(List.of("-c", "user.name=t", "-c", "user.email=t@t.invalid"));
        if (by != null) args.addAll(by.signArgs(agent));
        args.addAll(List.of("commit", "-q", "--allow-empty", "-m", message));
        git.out(repo, args.toArray(String[]::new));
        return git.out(repo, "rev-parse", "HEAD").strip();
    }

    @Test
    void aCommitSignedWithTheAgentsKeyIsVerifiedAsThatAgent() {
        Verdict v = signing.verdict(repo, commit(signing, ADA, "signed"));
        assertThat(v).isEqualTo(new Verdict("VERIFIED", "ada", false));
    }

    @Test
    void anUnsignedCommitIsNotSigned() {
        assertThat(signing.verdict(repo, commit(null, ADA, "plain")).status()).isEqualTo("NOT_SIGNED");
    }

    @Test
    void aKeyFloor42DoesNotKnowIsAnUnknownKey() {
        AgentSigning stranger = new AgentSigning(tmp.resolve("elsewhere"), git);
        assertThat(signing.verdict(repo, commit(stranger, ADA, "foreign")).status())
                .isEqualTo("UNKNOWN_KEY");
    }

    @Test
    void retiringDeletesThePrivateKeyButOldCommitsStillVerifyAsRetired() throws Exception {
        String sha = commit(signing, ADA, "before");
        signing.retire(ADA);
        assertThat(signing.verdict(repo, sha)).isEqualTo(new Verdict("VERIFIED", "ada", true));
        try (var files = Files.walk(tmp.resolve("data"))) {
            assertThat(files.filter(f -> f.getFileName().toString().equals("key")))
                    .isEmpty();
        }
        // A restored agent gets a new key: its next commit verifies as current, not retired
        assertThat(signing.verdict(repo, commit(signing, ADA, "after")).retired())
                .isFalse();
    }

    @Test
    void nothingIsWrittenToTheRepositoryOrItsConfiguration() throws Exception {
        String before = Files.readString(repo.resolve(".git/config"));
        commit(signing, ADA, "signed");
        assertThat(Files.readString(repo.resolve(".git/config"))).isEqualTo(before);
        try (var files = Files.walk(repo)) {
            assertThat(files.filter(f -> f.getFileName().toString().startsWith("key")))
                    .isEmpty();
        }
    }

    @Test
    void theKeyFolderIsOwnerOnlyWhereTheSystemHasPermissions() throws Exception {
        Path dir = tmp.resolve("data/signing/ada/current");
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        assertThat(Files.getPosixFilePermissions(dir)).hasSize(3);
        assertThat(Files.getPosixFilePermissions(dir.resolve("key"))).hasSize(2);
    }

    @Test
    void aBadHashIsUncheckableNotAnError() {
        assertThat(signing.verdict(repo, "--oops").status()).isEqualTo("UNCHECKABLE");
        assertThat(signing.verdict(tmp.resolve("missing"), "a".repeat(40)).status())
                .isEqualTo("UNCHECKABLE");
    }

    /** {@code git log} follows refs/replace: an unsigned commit could borrow a signed one's signature. */
    @Test
    void aReplaceRefCannotLendASignatureToAnUnsignedCommit() {
        String signed = commit(signing, ADA, "signed");
        String plain = commit(null, ADA, "plain");
        git.out(repo, "replace", "--force", plain, signed);

        assertThat(signing.verdict(repo, plain).status()).isEqualTo("NOT_SIGNED");
    }

    @Test
    void aKeyWithoutItsPublicPartIsMadeAgainSoItsCommitsVerify() throws Exception {
        Files.delete(tmp.resolve("data/signing/ada/current/key.pub"));

        Verdict v = signing.verdict(repo, commit(signing, ADA, "again"));

        assertThat(v).isEqualTo(new Verdict("VERIFIED", "ada", false));
    }

    @Test
    void theAllowedSignersAreRebuiltAtStartAndWrittenWithoutAPartialFile() throws Exception {
        Path signers = tmp.resolve("data/signing/allowed_signers");
        Files.delete(signers);

        signing.rebuildAllowedSigners();

        assertThat(Files.readString(signers)).startsWith("ada@floor42.invalid namespaces=\"git\" ssh-ed25519 ");
        try (var files = Files.list(signers.getParent())) {
            assertThat(files.map(f -> f.getFileName().toString())).noneMatch(n -> n.endsWith(".partial"));
        }
    }
}
