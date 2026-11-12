package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.floor42.domain.DeliveryCopy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The copy of a mission's documents into its destination (ADR-0051): documents only, never over a file, confined. */
class DocumentWriterTest {

    @Test
    void onlyDocumentsAreCopiedNeverWhatAToolWouldRunOrFollow() {
        for (String doc :
                List.of("notes.md", "docs/out/report.pdf", "diagram.png", "table.csv", "a.docx", "docs/agenda.md")) {
            assertThat(DocumentWriter.notADocument(doc)).as(doc).isEmpty();
        }
        for (String not : List.of(
                "plan.svg",
                "index.htm",
                "AGENT.md",
                "agents/reviewer.md",
                "docs/commands/deploy.md",
                "skills/pdf/notes.md",
                "hooks/notes.txt",
                "CON .md",
                "docs/aux. .txt",
                "lpt1 .pdf",
                "run.sh",
                "src/App.java",
                "tests/conftest.py",
                "x.test.ts",
                "package.json",
                "config.yml",
                "page.html",
                "CLAUDE.md",
                "docs/claude.md",
                "AGENTS.md",
                ".github/workflows/notes.md",
                ".vscode/notes.md",
                ".mvn/notes.txt",
                "NUL.txt",
                "docs/com1.md",
                "trailing.md ",
                "Microsoft.PowerShell_profile.ps1")) {
            assertThat(DocumentWriter.notADocument(not)).as(not).isPresent();
        }
    }

    @Test
    void aFileWhoseBytesAreNotWhatItsNameSaysIsLeftOut() throws Exception {
        Path folder = DocumentWriter.prepare(Files.createTempDirectory("floor42-home"), "M-3");
        Path in = Files.createTempDirectory("floor42-in");
        Path fakePdf = Files.writeString(in.resolve("report.pdf"), "#!/bin/sh\necho not a pdf\n");
        Path fakePng = Files.writeString(in.resolve("diagram.png"), "<svg/>");
        Path binaryMd = Files.write(in.resolve("notes.md"), new byte[] {'#', ' ', 0, 1, 2});
        Path pdf = Files.writeString(in.resolve("ok.pdf"), "%PDF-1.4\n% sample\n");
        Path png = Files.write(in.resolve("ok.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        DocumentWriter w = new DocumentWriter(folder, 1000, 10_000);
        w.write("dev-1", "report.pdf", fakePdf, Files.size(fakePdf));
        w.write("dev-1", "diagram.png", fakePng, Files.size(fakePng));
        w.write("dev-1", "notes.md", binaryMd, Files.size(binaryMd));
        w.write("dev-1", "ok.pdf", pdf, Files.size(pdf));
        w.write("dev-1", "ok.png", png, Files.size(png));
        assertThat(w.outcomes())
                .extracting(DeliveryCopy::outcome)
                .containsExactly(
                        DeliveryCopy.LEFT_OUT,
                        DeliveryCopy.LEFT_OUT,
                        DeliveryCopy.LEFT_OUT,
                        DeliveryCopy.WRITTEN,
                        DeliveryCopy.WRITTEN);
        assertThat(w.outcomes().getFirst().reason()).contains("not a .pdf");
        assertThat(folder.resolve("report.pdf")).doesNotExist();
    }

    @Test
    void aLinkInsideTheMissionsFolderIsRefusedEvenWhenItStaysInside() throws Exception {
        Path home = Files.createTempDirectory("floor42-home");
        Path folder = DocumentWriter.prepare(home, "M-4");
        Path real = Files.createDirectories(folder.resolve("real"));
        assumeTrue(DeliveryDestinationTest.link(folder.resolve("alias"), real), "no junction or link can be made here");
        Path in = Files.createTempDirectory("floor42-in");
        Path notes = Files.writeString(in.resolve("notes.md"), "# Notes\n");
        DocumentWriter w = new DocumentWriter(folder, 1000, 10_000);
        w.write("dev-1", "alias/notes.md", notes, Files.size(notes));
        assertThat(w.outcomes()).extracting(DeliveryCopy::outcome).containsExactly(DeliveryCopy.LEFT_OUT);
        assertThat(real.resolve("notes.md")).doesNotExist();
    }

    @Test
    void theMissionsFolderMayNotBeALinkToAnotherFolder() throws Exception {
        Path home = Files.createTempDirectory("floor42-home");
        Path other = Files.createDirectories(home.resolve("other"));
        assumeTrue(DeliveryDestinationTest.link(home.resolve("M-5"), other), "no junction or link can be made here");
        assertThatThrownBy(() -> DocumentWriter.prepare(home, "M-5"))
                .isInstanceOf(RefusedException.class)
                .extracting("code")
                .isEqualTo("INVALID_DESTINATION");
    }

    @Test
    void anExistingFileIsNeverOverwrittenTheSameBytesAreLeftAndOtherBytesKeptBeside() throws Exception {
        Path home = Files.createTempDirectory("floor42-home");
        Path folder = DocumentWriter.prepare(home, "M-1");
        Files.writeString(folder.resolve("notes.md"), "the director's own notes\n");
        Files.createDirectories(folder.resolve("docs"));
        Files.writeString(folder.resolve("docs/same.md"), "same\n");
        Path in = Files.createTempDirectory("floor42-in");
        Path notes = Files.writeString(in.resolve("notes.md"), "the agent's notes\n");
        Path same = Files.writeString(in.resolve("same.md"), "same\n");
        Path fresh = Files.writeString(in.resolve("fresh.md"), "fresh\n");

        DocumentWriter w = new DocumentWriter(folder, 1000, 10_000);
        w.write("dev-1", "notes.md", notes, Files.size(notes));
        w.write("dev-1", "notes.md", notes, Files.size(notes));
        w.write("dev-1", "docs/same.md", same, Files.size(same));
        w.write("dev-1", "fresh.md", fresh, Files.size(fresh));

        assertThat(Files.readString(folder.resolve("notes.md"))).isEqualTo("the director's own notes\n");
        assertThat(Files.readString(folder.resolve("notes (2).md"))).isEqualTo("the agent's notes\n");
        assertThat(w.outcomes())
                .extracting(DeliveryCopy::writtenAs, DeliveryCopy::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("notes (2).md", DeliveryCopy.KEPT_BOTH),
                        org.assertj.core.groups.Tuple.tuple("notes (2).md", DeliveryCopy.SAME),
                        org.assertj.core.groups.Tuple.tuple("docs/same.md", DeliveryCopy.SAME),
                        org.assertj.core.groups.Tuple.tuple("fresh.md", DeliveryCopy.WRITTEN));
        try (var left = Files.walk(folder)) {
            assertThat(left.map(Path::toString)).noneMatch(p -> p.endsWith(".part"));
        }
    }

    @Test
    void aPathThatClimbsOutOrPassesTheCapsIsLeftOutWithItsReason() throws Exception {
        Path home = Files.createTempDirectory("floor42-home");
        Path folder = DocumentWriter.prepare(home, "M-2");
        Path in = Files.createTempDirectory("floor42-in");
        Path big = Files.writeString(in.resolve("big.md"), "x".repeat(2000));
        Path small = Files.writeString(in.resolve("small.md"), "small\n");
        DocumentWriter w = new DocumentWriter(folder, 1000, 10);
        w.write("dev-1", "../escape.md", small, Files.size(small));
        w.write("dev-1", "big.md", big, Files.size(big));
        w.write("dev-1", "one.md", small, Files.size(small));
        w.write("dev-1", "two.md", small, Files.size(small));
        assertThat(w.outcomes())
                .extracting(DeliveryCopy::outcome)
                .containsExactly(
                        DeliveryCopy.LEFT_OUT, DeliveryCopy.LEFT_OUT, DeliveryCopy.WRITTEN, DeliveryCopy.LEFT_OUT);
        assertThat(w.outcomes().getFirst().reason()).isNotBlank();
        assertThat(home.resolve("escape.md")).doesNotExist();
        assertThat(DocumentWriter.numbered("report.pdf", 3)).isEqualTo("report (3).pdf");
        assertThat(DocumentWriter.numbered("README", 2)).isEqualTo("README (2)");
    }
}
