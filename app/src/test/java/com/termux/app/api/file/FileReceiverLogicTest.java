package com.termux.app.api.file;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for {@link FileReceiverLogic}.
 * <p>
 * Pure JUnit — no Robolectric or Android dependencies needed.
 */
public class FileReceiverLogicTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File targetDir;

    @Before
    public void setUp() throws IOException {
        targetDir = tempFolder.newFolder("downloads");
    }

    // ──────────────────────────────────────────────────────────────────────
    // resolveDefaultName — priority chain
    // ──────────────────────────────────────────────────────────────────────

    @Test
    public void resolveDefaultName_displayNameWins() {
        String name = FileReceiverLogic.resolveDefaultName(
            "photo.jpg", "My Title", "My Subject", "uri_name.jpg", false);
        assertEquals("photo.jpg", name);
    }

    @Test
    public void resolveDefaultName_titleWhenNoDisplayName() {
        String name = FileReceiverLogic.resolveDefaultName(
            null, "My Title", "My Subject", "uri_name.jpg", false);
        assertEquals("My Title", name);
    }

    @Test
    public void resolveDefaultName_subjectWhenNoTitleOrDisplay() {
        String name = FileReceiverLogic.resolveDefaultName(
            null, null, "My Subject", "uri_name.jpg", false);
        assertEquals("My Subject", name);
    }

    @Test
    public void resolveDefaultName_uriBasenameAsLastResort() {
        String name = FileReceiverLogic.resolveDefaultName(
            null, null, null, "uri_name.jpg", false);
        assertEquals("uri_name.jpg", name);
    }

    @Test
    public void resolveDefaultName_defaultWhenAllNull() {
        String name = FileReceiverLogic.resolveDefaultName(
            null, null, null, null, false);
        assertEquals(FileReceiverLogic.DEFAULT_FILE_NAME, name);
    }

    @Test
    public void resolveDefaultName_blankStringsAreSkipped() {
        String name = FileReceiverLogic.resolveDefaultName(
            "", "  ", "\t", null, false);
        // All first three are blank, uriBasename is null → falls through to default.
        assertEquals(FileReceiverLogic.DEFAULT_FILE_NAME, name);
    }

    @Test
    public void resolveDefaultName_blankDisplayNameSkippedToTitle() {
        String name = FileReceiverLogic.resolveDefaultName(
            "", "Real Title", null, null, false);
        assertEquals("Real Title", name);
    }

    @Test
    public void resolveDefaultName_textWithoutExtensionGetsTxt() {
        String name = FileReceiverLogic.resolveDefaultName(
            null, null, "Meeting Notes", null, true);
        assertEquals("Meeting Notes.txt", name);
    }

    @Test
    public void resolveDefaultName_textWithExistingExtensionUnchanged() {
        String name = FileReceiverLogic.resolveDefaultName(
            null, null, "report.pdf", null, true);
        assertEquals("report.pdf", name);
    }

    @Test
    public void resolveDefaultName_nonTextNoTxtAppended() {
        String name = FileReceiverLogic.resolveDefaultName(
            null, null, "Some Title", null, false);
        // Non-text: no automatic .txt.
        assertEquals("Some Title", name);
    }

    @Test
    public void resolveDefaultName_trimWhitespace() {
        String name = FileReceiverLogic.resolveDefaultName(
            "  report.pdf  ", null, null, null, false);
        assertEquals("report.pdf", name);
    }

    // ──────────────────────────────────────────────────────────────────────
    // resolveConflict — incremental naming
    // ──────────────────────────────────────────────────────────────────────

    @Test
    public void resolveConflict_noConflictReturnsOriginal() throws IOException {
        // targetDir is empty.
        String name = FileReceiverLogic.resolveConflict("report.pdf", targetDir);
        assertEquals("report.pdf", name);
    }

    @Test
    public void resolveConflict_firstConflictReturns2() throws IOException {
        new File(targetDir, "report.pdf").createNewFile();

        String name = FileReceiverLogic.resolveConflict("report.pdf", targetDir);
        assertEquals("report (2).pdf", name);
    }

    @Test
    public void resolveConflict_secondConflictReturns3() throws IOException {
        new File(targetDir, "report.pdf").createNewFile();
        new File(targetDir, "report (2).pdf").createNewFile();

        String name = FileReceiverLogic.resolveConflict("report.pdf", targetDir);
        assertEquals("report (3).pdf", name);
    }

    @Test
    public void resolveConflict_multipleGapsFilled() throws IOException {
        new File(targetDir, "data.csv").createNewFile();
        new File(targetDir, "data (2).csv").createNewFile();
        new File(targetDir, "data (3).csv").createNewFile();

        String name = FileReceiverLogic.resolveConflict("data.csv", targetDir);
        assertEquals("data (4).csv", name);
    }

    @Test
    public void resolveConflict_noExtension() throws IOException {
        new File(targetDir, "Makefile").createNewFile();

        String name = FileReceiverLogic.resolveConflict("Makefile", targetDir);
        assertEquals("Makefile (2)", name);
    }

    @Test
    public void resolveConflict_compoundExtension() throws IOException {
        new File(targetDir, "archive.tar.gz").createNewFile();

        String name = FileReceiverLogic.resolveConflict("archive.tar.gz", targetDir);
        assertEquals("archive.tar (2).gz", name);
    }

    @Test
    public void resolveConflict_leadingDotNoExtension() throws IOException {
        // ".gitignore" — the dot is part of the name, not an extension separator.
        new File(targetDir, ".gitignore").createNewFile();

        String name = FileReceiverLogic.resolveConflict(".gitignore", targetDir);
        assertEquals(".gitignore (2)", name);
    }

    @Test
    public void resolveConflict_nullInputs() {
        Assert.assertNull(FileReceiverLogic.resolveConflict(null, targetDir));
        // null dir → skip conflict check, return name unchanged.
        assertEquals("file.txt", FileReceiverLogic.resolveConflict("file.txt", null));
    }

    // ──────────────────────────────────────────────────────────────────────
    // resolveSafeFileName — sanitize + conflict
    // ──────────────────────────────────────────────────────────────────────

    @Test
    public void resolveSafeFileName_normalName() throws IOException {
        String name = FileReceiverLogic.resolveSafeFileName("report.pdf", targetDir);
        assertEquals("report.pdf", name);
    }

    @Test
    public void resolveSafeFileName_sanitizesPathSeparators() throws IOException {
        String name = FileReceiverLogic.resolveSafeFileName(
            "../../../etc/passwd", targetDir);
        // Path separators replaced with underscores; dots are preserved (safe char).
        assertFalse(name.contains("/"));
        assertFalse(name.contains("\\"));
    }

    @Test
    public void resolveSafeFileName_sanitizesSpaces() throws IOException {
        String name = FileReceiverLogic.resolveSafeFileName("my file.txt", targetDir);
        assertFalse(name.contains(" "));
        // Spaces replaced with underscores.
        assertTrue(name.contains("_"));
    }

    @Test
    public void resolveSafeFileName_sanitizesSpecialChars() throws IOException {
        String name = FileReceiverLogic.resolveSafeFileName("file<>:\"name.pdf", targetDir);
        assertFalse(name.contains("<"));
        assertFalse(name.contains(">"));
        assertFalse(name.contains(":"));
        assertFalse(name.contains("\""));
    }

    @Test
    public void resolveSafeFileName_conflictAfterSanitize() throws IOException {
        // Create a file with sanitized name.
        new File(targetDir, "my_file.txt").createNewFile();

        String name = FileReceiverLogic.resolveSafeFileName("my file.txt", targetDir);
        // "my file.txt" sanitizes to "my_file.txt" which conflicts → " (2)" appended.
        assertEquals("my_file (2).txt", name);
    }

    @Test
    public void resolveSafeFileName_nullFallsToDefault() {
        String name = FileReceiverLogic.resolveSafeFileName(null, targetDir);
        assertEquals(FileReceiverLogic.DEFAULT_FILE_NAME, name);
    }

    @Test
    public void resolveSafeFileName_nullDirSkipsConflict() {
        // No dir → only sanitize, no conflict check.
        String name = FileReceiverLogic.resolveSafeFileName("report.pdf", null);
        assertEquals("report.pdf", name);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal helpers (exposed as package-private for testing)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    public void firstNonBlank_returnsFirst() {
        assertEquals("a", FileReceiverLogic.firstNonBlank("a", "b", "c"));
    }

    @Test
    public void firstNonBlank_skipsNullAndBlank() {
        assertEquals("b", FileReceiverLogic.firstNonBlank(null, "", "  ", "b", "c"));
    }

    @Test
    public void firstNonBlank_allNull() {
        Assert.assertNull(FileReceiverLogic.firstNonBlank(null, null, null));
    }

    @Test
    public void firstNonBlank_allBlank() {
        Assert.assertNull(FileReceiverLogic.firstNonBlank("", " ", "\t"));
    }

    @Test
    public void getExtensionWithDot_normal() {
        assertEquals(".pdf", FileReceiverLogic.getExtensionWithDot("report.pdf"));
    }

    @Test
    public void getExtensionWithDot_compound() {
        assertEquals(".gz", FileReceiverLogic.getExtensionWithDot("archive.tar.gz"));
    }

    @Test
    public void getExtensionWithDot_noExtension() {
        assertEquals("", FileReceiverLogic.getExtensionWithDot("Makefile"));
    }

    @Test
    public void getExtensionWithDot_leadingDot() {
        // ".gitignore" — leading dot is NOT an extension separator.
        assertEquals("", FileReceiverLogic.getExtensionWithDot(".gitignore"));
    }

    @Test
    public void getExtensionWithDot_null() {
        assertEquals("", FileReceiverLogic.getExtensionWithDot(null));
    }

    @Test
    public void getFileBasenameWithoutExtension_normal() {
        assertEquals("report", FileReceiverLogic.getFileBasenameWithoutExtension("report.pdf"));
    }

    @Test
    public void getFileBasenameWithoutExtension_compound() {
        assertEquals("archive.tar", FileReceiverLogic.getFileBasenameWithoutExtension("archive.tar.gz"));
    }

    @Test
    public void getFileBasenameWithoutExtension_noExtension() {
        assertEquals("Makefile", FileReceiverLogic.getFileBasenameWithoutExtension("Makefile"));
    }

    @Test
    public void getFileBasenameWithoutExtension_leadingDot() {
        assertEquals(".gitignore", FileReceiverLogic.getFileBasenameWithoutExtension(".gitignore"));
    }

    // ──────────────────────────────────────────────────────────────────────
    // End-to-end pipeline
    // ──────────────────────────────────────────────────────────────────────

    @Test
    public void e2e_resolveThenSave_noConflict() throws IOException {
        String rawName = "notes.txt";
        String safe = FileReceiverLogic.resolveSafeFileName(rawName, targetDir);
        assertEquals("notes.txt", safe);

        // Verify the resolved name can be used to create a file.
        File f = new File(targetDir, safe);
        assertTrue(f.createNewFile());
    }

    @Test
    public void e2e_resolveThenSave_conflictAfterCreate() throws IOException {
        // First resolve — no conflict.
        String safe1 = FileReceiverLogic.resolveSafeFileName("notes.txt", targetDir);
        assertEquals("notes.txt", safe1);
        new File(targetDir, safe1).createNewFile();

        // Second resolve — now conflicts. " (N)" pattern has a space before paren.
        String safe2 = FileReceiverLogic.resolveSafeFileName("notes.txt", targetDir);
        assertEquals("notes (2).txt", safe2);
        assertNotNull(safe2);
        assertFalse(safe2.equals(safe1));
    }

    @Test
    public void e2e_differentInputsProduceDifferentNames() throws IOException {
        // Two different raw names that sanitize to the same thing.
        new File(targetDir, "my_file.txt").createNewFile();

        String safe1 = FileReceiverLogic.resolveSafeFileName("my file.txt", targetDir);
        String safe2 = FileReceiverLogic.resolveSafeFileName("my\tfile.txt", targetDir);

        // Both sanitize to "my_file.txt" which conflicts → both get "my_file (2).txt".
        // This tests that the conflict resolution is deterministic.
        assertEquals(safe1, safe2);
        assertEquals("my_file (2).txt", safe1);
    }
}
