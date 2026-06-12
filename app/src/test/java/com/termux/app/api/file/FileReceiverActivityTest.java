package com.termux.app.api.file;

import com.termux.app.api.file.FileReceiverActivity;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class FileReceiverActivityTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testIsSharedTextAnUrl() {
        List<String> validUrls = new ArrayList<>();
        validUrls.add("http://example.com");
        validUrls.add("https://example.com");
        validUrls.add("https://example.com/path/parameter=foo");
        validUrls.add("magnet:?xt=urn:btih:d540fc48eb12f2833163eed6421d449dd8f1ce1f&dn=Ubuntu+desktop+19.04+%2864bit%29&tr=udp%3A%2F%2Ftracker.openbittorrent.com%3A80&tr=udp%3A%2F%2Ftracker.publicbt.com%3A80&tr=udp%3A%2F%2Ftracker.ccc.de%3A80");
        for (String url : validUrls) {
            Assert.assertTrue(FileReceiverActivity.isSharedTextAnUrl(url));
        }

        List<String> invalidUrls = new ArrayList<>();
        invalidUrls.add("a test with example.com");
        invalidUrls.add("");
        invalidUrls.add(null);
        for (String url : invalidUrls) {
            Assert.assertFalse(FileReceiverActivity.isSharedTextAnUrl(url));
        }
    }

    // --- getNonConflictingFile: a same-name file must never be silently overwritten ---

    @Test
    public void getNonConflictingFile_noConflict_returnsSameName() {
        File result = FileReceiverActivity.getNonConflictingFile(folder.getRoot(), "report.txt");
        Assert.assertNotNull(result);
        Assert.assertEquals("report.txt", result.getName());
    }

    @Test
    public void getNonConflictingFile_oneConflict_appendsSuffix() throws IOException {
        folder.newFile("report.txt");
        File result = FileReceiverActivity.getNonConflictingFile(folder.getRoot(), "report.txt");
        Assert.assertNotNull(result);
        Assert.assertEquals("report-1.txt", result.getName());
    }

    @Test
    public void getNonConflictingFile_twoConflicts_incrementsSuffix() throws IOException {
        folder.newFile("report.txt");
        folder.newFile("report-1.txt");
        File result = FileReceiverActivity.getNonConflictingFile(folder.getRoot(), "report.txt");
        Assert.assertNotNull(result);
        Assert.assertEquals("report-2.txt", result.getName());
    }

    @Test
    public void getNonConflictingFile_noExtension_appendsSuffix() throws IOException {
        folder.newFile("README");
        File result = FileReceiverActivity.getNonConflictingFile(folder.getRoot(), "README");
        Assert.assertNotNull(result);
        Assert.assertEquals("README-1", result.getName());
    }

    @Test
    public void getNonConflictingFile_dotfile_keepsLeadingDot() throws IOException {
        folder.newFile(".bashrc");
        File result = FileReceiverActivity.getNonConflictingFile(folder.getRoot(), ".bashrc");
        Assert.assertNotNull(result);
        Assert.assertEquals(".bashrc-1", result.getName());
    }

    @Test
    public void getNonConflictingFile_multipleDots_suffixesBeforeLastExtension() throws IOException {
        folder.newFile("archive.tar.gz");
        File result = FileReceiverActivity.getNonConflictingFile(folder.getRoot(), "archive.tar.gz");
        Assert.assertNotNull(result);
        Assert.assertEquals("archive.tar-1.gz", result.getName());
    }

    // --- sanitizeReceivedFileName: drop directory components and reject unusable names ---

    @Test
    public void sanitizeReceivedFileName_stripsDirectoryComponents() {
        Assert.assertEquals("passwd", FileReceiverActivity.sanitizeReceivedFileName("../../etc/passwd"));
        Assert.assertEquals("file.txt", FileReceiverActivity.sanitizeReceivedFileName("some/dir/file.txt"));
    }

    @Test
    public void sanitizeReceivedFileName_scrubsIllegalCharacters() {
        Assert.assertEquals("a_b_c.txt", FileReceiverActivity.sanitizeReceivedFileName("a:b?c.txt"));
    }

    @Test
    public void sanitizeReceivedFileName_rejectsEmptyAndDotNames() {
        Assert.assertNull(FileReceiverActivity.sanitizeReceivedFileName(null));
        Assert.assertNull(FileReceiverActivity.sanitizeReceivedFileName(""));
        Assert.assertNull(FileReceiverActivity.sanitizeReceivedFileName("   "));
        Assert.assertNull(FileReceiverActivity.sanitizeReceivedFileName("."));
        Assert.assertNull(FileReceiverActivity.sanitizeReceivedFileName(".."));
        Assert.assertNull(FileReceiverActivity.sanitizeReceivedFileName("/"));
    }

    @Test
    public void sanitizeReceivedFileName_keepsValidName() {
        Assert.assertEquals("notes.md", FileReceiverActivity.sanitizeReceivedFileName("notes.md"));
    }

}
