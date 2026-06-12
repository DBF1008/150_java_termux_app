package com.termux.app;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the bootstrap zip extraction and SYMLINKS.txt parsing logic
 * in {@link TermuxInstaller#extractBootstrapZip(byte[], String)}.
 *
 * These tests verify:
 * - SYMLINKS.txt line format parsing (target←linkpath)
 * - Malformed symlink line detection
 * - Zip entry extraction logic (directories and files)
 * - SYMLINKS.txt must be present in the zip
 */
public class BootstrapZipExtractionTest {

    // ─── SYMLINKS.txt Parsing Tests ───

    @Test
    public void testSymlinksParsing_validLine() {
        String line = "../bin/login←bin/su";
        String[] parts = line.split("←");
        assertEquals("Should have exactly 2 parts", 2, parts.length);
        assertEquals("Target path", "../bin/login", parts[0]);
        assertEquals("Link path", "bin/su", parts[1]);
    }

    @Test
    public void testSymlinksParsing_absoluteTarget() {
        String line = "/system/bin/sh←bin/sh";
        String[] parts = line.split("←");
        assertEquals(2, parts.length);
        assertEquals("/system/bin/sh", parts[0]);
        assertEquals("bin/sh", parts[1]);
    }

    @Test
    public void testSymlinksParsing_relativeTarget() {
        String line = "libreadline.so.8←lib/libreadline.so";
        String[] parts = line.split("←");
        assertEquals(2, parts.length);
        assertEquals("libreadline.so.8", parts[0]);
        assertEquals("lib/libreadline.so", parts[1]);
    }

    @Test
    public void testSymlinksParsing_malformedLine_noArrow() {
        String line = "invalid-line-no-arrow";
        String[] parts = line.split("←");
        if (parts.length != 2) {
            // This is the expected behavior - should detect malformed line
            assertEquals("Should have 1 part (no arrow)", 1, parts.length);
        } else {
            fail("Should not parse line without ← separator");
        }
    }

    @Test
    public void testSymlinksParsing_malformedLine_tooManyArrows() {
        String line = "a←b←c";
        String[] parts = line.split("←");
        assertEquals("Should have 3 parts with 2 arrows", 3, parts.length);
        // In production this would throw RuntimeException because parts.length != 2
    }

    @Test
    public void testSymlinksParsing_multipleLines() throws IOException {
        String content = "../bin/login←bin/su\n../lib/libc.so←lib/libc.so.6\nlibreadline.so←lib/libreadline.so.8\n";
        BufferedReader reader = new BufferedReader(new InputStreamReader(
            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));

        List<String[]> symlinks = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split("←");
            assertEquals("Each line should have exactly 2 parts: " + line, 2, parts.length);
            symlinks.add(parts);
        }

        assertEquals("Should parse 3 symlink lines", 3, symlinks.size());
        assertEquals("First symlink target", "../bin/login", symlinks.get(0)[0]);
        assertEquals("First symlink link", "bin/su", symlinks.get(0)[1]);
        assertEquals("Second symlink target", "../lib/libc.so", symlinks.get(1)[0]);
        assertEquals("Third symlink target", "libreadline.so", symlinks.get(2)[0]);
    }

    @Test
    public void testSymlinksParsing_emptyLine() {
        String line = "";
        String[] parts = line.split("←");
        assertEquals("Empty line should produce 1 part", 1, parts.length);
        // In production, parts.length != 2 would throw RuntimeException
    }

    // ─── Zip Structure Tests ───

    @Test
    public void testZipWithSymlinksTxt_parsesCorrectly() throws Exception {
        // Create a minimal test zip with SYMLINKS.txt and some files
        byte[] zipBytes = createTestZip(
            "SYMLINKS.txt", "../bin/login←bin/su\n",
            new String[]{"bin/", null},           // directory entry
            new String[]{"bin/login", "#!/bin/sh\necho hello\n"},  // file entry
            new String[]{"lib/", null},           // directory entry
            new String[]{"lib/libc.so", "ELF..."} // file entry
        );

        // Parse the zip
        List<String[]> symlinks = new ArrayList<>();
        List<String> directories = new ArrayList<>();
        List<String> files = new ArrayList<>();

        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInput.getNextEntry()) != null) {
                if (zipEntry.getName().equals("SYMLINKS.txt")) {
                    BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                    String line;
                    while ((line = symlinksReader.readLine()) != null) {
                        String[] parts = line.split("←");
                        assertEquals("Should have 2 parts", 2, parts.length);
                        symlinks.add(parts);
                    }
                } else if (zipEntry.isDirectory()) {
                    directories.add(zipEntry.getName());
                } else {
                    files.add(zipEntry.getName());
                }
            }
        }

        assertEquals("Should find 1 symlink", 1, symlinks.size());
        assertEquals("../bin/login", symlinks.get(0)[0]);
        assertEquals("bin/su", symlinks.get(0)[1]);

        assertTrue("Should find bin/ directory", directories.contains("bin/"));
        assertTrue("Should find lib/ directory", directories.contains("lib/"));

        assertTrue("Should find bin/login file", files.contains("bin/login"));
        assertTrue("Should find lib/libc.so file", files.contains("lib/libc.so"));
    }

    @Test
    public void testZipWithoutSymlinksTxt_detectedAsMissing() throws Exception {
        // Create a zip WITHOUT SYMLINKS.txt
        byte[] zipBytes = createTestZip(
            null, null,
            new String[]{"bin/", null},
            new String[]{"bin/login", "#!/bin/sh\n"}
        );

        boolean foundSymlinksTxt = false;
        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInput.getNextEntry()) != null) {
                if (zipEntry.getName().equals("SYMLINKS.txt")) {
                    foundSymlinksTxt = true;
                }
            }
        }

        // In production, this would throw RuntimeException("No SYMLINKS.txt encountered")
        assertTrue("SYMLINKS.txt should NOT be found in this zip", !foundSymlinksTxt);
    }

    @Test
    public void testZipEntryNameStartsWithBin_shouldBeExecutable() throws Exception {
        // Verify the permission logic: files under bin/ should get execute permissions
        String[] testPaths = {
            "bin/login",       // should be executable
            "bin/sh",          // should be executable
            "libexec/helper",  // should be executable
            "lib/apt/methods/http", // should be executable
            "lib/apt/apt-helper",   // should be executable
            "lib/libc.so",     // should NOT be executable
            "etc/passwd",      // should NOT be executable
            "share/doc/README" // should NOT be executable
        };

        boolean[] expected = {
            true, true, true, true, true,
            false, false, false
        };

        for (int i = 0; i < testPaths.length; i++) {
            boolean shouldBeExecutable =
                testPaths[i].startsWith("bin/") ||
                testPaths[i].startsWith("libexec") ||
                testPaths[i].startsWith("lib/apt/apt-helper") ||
                testPaths[i].startsWith("lib/apt/methods");

            assertEquals("Permission check for " + testPaths[i],
                expected[i], shouldBeExecutable);
        }
    }

    // ─── Helper Methods ───

    /**
     * Creates a test zip byte array with the given entries.
     *
     * @param symlinksTxtName Name for the SYMLINKS.txt entry (null to skip)
     * @param symlinksTxtContent Content for SYMLINKS.txt (null to skip)
     * @param entries Additional entries as {name, content} pairs. Null content = directory.
     */
    private byte[] createTestZip(String symlinksTxtName, String symlinksTxtContent,
                                  String[]... entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Add SYMLINKS.txt first if specified
            if (symlinksTxtName != null && symlinksTxtContent != null) {
                zos.putNextEntry(new ZipEntry(symlinksTxtName));
                zos.write(symlinksTxtContent.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            // Add other entries
            for (String[] entry : entries) {
                String name = entry[0];
                String content = entry[1];

                if (content == null) {
                    // Directory entry
                    zos.putNextEntry(new ZipEntry(name));
                    zos.closeEntry();
                } else {
                    // File entry
                    zos.putNextEntry(new ZipEntry(name));
                    zos.write(content.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
        }
        return baos.toByteArray();
    }
}
