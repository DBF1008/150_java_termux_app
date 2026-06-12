package com.termux.filepicker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression tests for {@link TermuxDocumentsProvider}.
 *
 * Covers document ID encoding/decoding, path-traversal prevention, symlink escape
 * detection, child-document boundary checks, and directory operation null-safety.
 */
@RunWith(RobolectricTestRunner.class)
public class TermuxDocumentsProviderTest {

    private File tempBaseDir;
    private File originalBaseDir;
    private String originalBaseDirCanonical;

    @Before
    public void setUp() throws Exception {
        tempBaseDir = Files.createTempDirectory("termux_test_home").toFile();
        originalBaseDir = TermuxDocumentsProvider.BASE_DIR;
        originalBaseDirCanonical = TermuxDocumentsProvider.BASE_DIR_CANONICAL;

        // Override BASE_DIR and BASE_DIR_CANONICAL to use the temp directory
        TermuxDocumentsProvider.BASE_DIR = tempBaseDir;
        TermuxDocumentsProvider.BASE_DIR_CANONICAL = tempBaseDir.getCanonicalPath();
    }

    @After
    public void tearDown() throws Exception {
        // Restore original values
        TermuxDocumentsProvider.BASE_DIR = originalBaseDir;
        TermuxDocumentsProvider.BASE_DIR_CANONICAL = originalBaseDirCanonical;

        // Clean up temp directory
        deleteRecursively(tempBaseDir);
    }

    // =========================================================================
    // Document ID encoding / decoding
    // =========================================================================

    @Test
    public void testGetDocIdForFile_root() {
        assertEquals(TermuxDocumentsProvider.ROOT_DOC_ID,
            TermuxDocumentsProvider.getDocIdForFile(tempBaseDir));
    }

    @Test
    public void testGetDocIdForFile_childFile() throws IOException {
        File subDir = new File(tempBaseDir, "subdir");
        subDir.mkdir();
        File child = new File(subDir, "notes.txt");
        child.createNewFile();

        assertEquals("root/subdir/notes.txt",
            TermuxDocumentsProvider.getDocIdForFile(child));
    }

    @Test
    public void testGetDocIdForFile_nestedChild() throws IOException {
        File deep = new File(tempBaseDir, "a/b/c/file.txt");
        deep.getParentFile().mkdirs();
        deep.createNewFile();

        assertEquals("root/a/b/c/file.txt",
            TermuxDocumentsProvider.getDocIdForFile(deep));
    }

    @Test
    public void testGetFileForDocId_root() throws FileNotFoundException {
        File result = TermuxDocumentsProvider.getFileForDocId("root");
        assertEquals(tempBaseDir.getAbsolutePath(), result.getAbsolutePath());
    }

    @Test
    public void testGetFileForDocId_child() throws IOException {
        File child = new File(tempBaseDir, "test.txt");
        child.createNewFile();

        File result = TermuxDocumentsProvider.getFileForDocId("root/test.txt");
        assertEquals(child.getCanonicalPath(), result.getCanonicalPath());
    }

    @Test
    public void testGetFileForDocId_nestedChild() throws IOException {
        File deep = new File(tempBaseDir, "a/b/c.txt");
        deep.getParentFile().mkdirs();
        deep.createNewFile();

        File result = TermuxDocumentsProvider.getFileForDocId("root/a/b/c.txt");
        assertEquals(deep.getCanonicalPath(), result.getCanonicalPath());
    }

    // =========================================================================
    // Path traversal prevention
    // =========================================================================

    @Test(expected = FileNotFoundException.class)
    public void testGetFileForDocId_pathTraversal_rejected() throws FileNotFoundException {
        TermuxDocumentsProvider.getFileForDocId("root/../../../etc/passwd");
    }

    @Test(expected = FileNotFoundException.class)
    public void testGetFileForDocId_absolutePathOutside_rejected() throws FileNotFoundException {
        // Absolute paths outside BASE_DIR should be rejected
        TermuxDocumentsProvider.getFileForDocId("/etc/passwd");
    }

    @Test
    public void testGetFileForDocId_absolutePathInside_backwardCompat() throws IOException {
        // Old-style absolute path within BASE_DIR should still work (backward compat)
        File child = new File(tempBaseDir, "legacy.txt");
        child.createNewFile();
        File result = TermuxDocumentsProvider.getFileForDocId(child.getAbsolutePath());
        assertEquals(child.getCanonicalPath(), result.getCanonicalPath());
    }

    @Test(expected = FileNotFoundException.class)
    public void testGetFileForDocId_null_rejected() throws FileNotFoundException {
        TermuxDocumentsProvider.getFileForDocId(null);
    }

    @Test(expected = FileNotFoundException.class)
    public void testGetFileForDocId_empty_rejected() throws FileNotFoundException {
        TermuxDocumentsProvider.getFileForDocId("");
    }

    @Test(expected = FileNotFoundException.class)
    public void testGetFileForDocId_badFormat_rejected() throws FileNotFoundException {
        TermuxDocumentsProvider.getFileForDocId("notroot/something");
    }

    @Test(expected = FileNotFoundException.class)
    public void testGetFileForDocId_nonExistent_rejected() throws FileNotFoundException {
        TermuxDocumentsProvider.getFileForDocId("root/does_not_exist.txt");
    }

    @Test
    public void testEnforceInsideBaseDir_inside_ok() throws IOException, FileNotFoundException {
        File child = new File(tempBaseDir, "allowed.txt");
        child.createNewFile();
        // Should not throw
        TermuxDocumentsProvider.enforceInsideBaseDir(child);
    }

    @Test
    public void testEnforceInsideBaseDir_baseDir_ok() throws FileNotFoundException {
        // BASE_DIR itself should be allowed
        TermuxDocumentsProvider.enforceInsideBaseDir(tempBaseDir);
    }

    @Test(expected = FileNotFoundException.class)
    public void testEnforceInsideBaseDir_outside_rejected() throws FileNotFoundException {
        File outside = new File("/etc/passwd");
        TermuxDocumentsProvider.enforceInsideBaseDir(outside);
    }

    @Test(expected = FileNotFoundException.class)
    public void testEnforceInsideBaseDir_prefixAttack_rejected() throws Exception {
        // A directory that shares the base dir's prefix but is not inside it
        // e.g. if base is /tmp/termux_test_home, create /tmp/termux_test_home_evil
        File evilDir = new File(tempBaseDir.getParentFile(), tempBaseDir.getName() + "_evil");
        evilDir.mkdir();
        try {
            TermuxDocumentsProvider.enforceInsideBaseDir(evilDir);
        } finally {
            evilDir.delete();
        }
    }

    // =========================================================================
    // Symlink escape prevention
    // =========================================================================

    @Test(expected = FileNotFoundException.class)
    public void testGetFileForDocId_symlinkEscape_rejected() throws Exception {
        // Create a symlink inside BASE_DIR that points outside
        File outsideDir = Files.createTempDirectory("termux_test_outside").toFile();
        File symlink = new File(tempBaseDir, "escape_link");
        try {
            Files.createSymbolicLink(symlink.toPath(), outsideDir.toPath());
            // Attempt to access via docId — should be rejected
            TermuxDocumentsProvider.getFileForDocId("root/escape_link");
        } finally {
            symlink.delete();
            outsideDir.delete();
        }
    }

    @Test
    public void testEnforceInsideBaseDir_symlinkToOutside_rejected() throws Exception {
        File outsideDir = Files.createTempDirectory("termux_test_outside2").toFile();
        File symlink = new File(tempBaseDir, "link_outside");
        try {
            Files.createSymbolicLink(symlink.toPath(), outsideDir.toPath());
            try {
                TermuxDocumentsProvider.enforceInsideBaseDir(symlink);
                fail("Expected FileNotFoundException for symlink escaping base dir");
            } catch (FileNotFoundException expected) {
                // Expected
            }
        } finally {
            symlink.delete();
            outsideDir.delete();
        }
    }

    @Test
    public void testEnforceInsideBaseDir_symlinkToInside_allowed() throws Exception {
        File insideDir = new File(tempBaseDir, "real_dir");
        insideDir.mkdir();
        File symlink = new File(tempBaseDir, "link_inside");
        try {
            Files.createSymbolicLink(symlink.toPath(), insideDir.toPath());
            // Should not throw — symlink resolves inside BASE_DIR
            TermuxDocumentsProvider.enforceInsideBaseDir(symlink);
        } finally {
            symlink.delete();
        }
    }

    // =========================================================================
    // isChildDocument boundary checks
    // =========================================================================

    @Test
    public void testIsChildDocument_basicChild() throws IOException {
        File child = new File(tempBaseDir, "a.txt");
        child.createNewFile();
        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        assertTrue(provider.isChildDocument("root", "root/a.txt"));
    }

    @Test
    public void testIsChildDocument_nestedChild() throws IOException {
        File deep = new File(tempBaseDir, "a/b/c.txt");
        deep.getParentFile().mkdirs();
        deep.createNewFile();
        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        assertTrue(provider.isChildDocument("root", "root/a/b/c.txt"));
    }

    @Test
    public void testIsChildDocument_sameAsParent() {
        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        assertTrue(provider.isChildDocument("root", "root"));
    }

    @Test
    public void testIsChildDocument_prefixBoundaryProtection() throws IOException {
        // Create a directory that shares the base dir's name prefix
        // e.g., base=/tmp/termux_test_home, sibling=/tmp/termux_test_home_evil
        // This tests that "root/ab" is NOT a child of "root/a"
        File dirA = new File(tempBaseDir, "a");
        dirA.mkdir();
        File fileAb = new File(tempBaseDir, "ab");
        fileAb.createNewFile();

        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        assertFalse(provider.isChildDocument("root/a", "root/ab"));
    }

    @Test
    public void testIsChildDocument_invalidDocId_returnsFalse() {
        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        assertFalse(provider.isChildDocument("root", "/etc/passwd"));
    }

    @Test
    public void testIsChildDocument_nonExistent_returnsFalse() {
        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        assertFalse(provider.isChildDocument("root", "root/nonexistent.txt"));
    }

    // =========================================================================
    // createDocument validation
    // =========================================================================

    @Test(expected = FileNotFoundException.class)
    public void testCreateDocument_displayNameTraversal_rejected() throws FileNotFoundException {
        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        // displayName with "../" should be rejected
        provider.createDocument("root", "text/plain", "../../../etc/evil.txt");
    }

    @Test
    public void testCreateDocument_validFile() throws FileNotFoundException {
        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        String docId = provider.createDocument("root", "text/plain", "new_file.txt");
        assertEquals("root/new_file.txt", docId);
        assertTrue(new File(tempBaseDir, "new_file.txt").exists());
    }

    @Test
    public void testCreateDocument_validDirectory() throws FileNotFoundException {
        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        String docId = provider.createDocument("root", "vnd.android.document/directory", "new_dir");
        assertEquals("root/new_dir", docId);
        assertTrue(new File(tempBaseDir, "new_dir").isDirectory());
    }

    @Test
    public void testCreateDocument_conflictResolution() throws IOException {
        File existing = new File(tempBaseDir, "dup.txt");
        existing.createNewFile();

        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        String docId = provider.createDocument("root", "text/plain", "dup.txt");
        assertEquals("root/dup.txt (2)", docId);
    }

    @Test(expected = FileNotFoundException.class)
    public void testCreateDocument_invalidParent_rejected() throws FileNotFoundException {
        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        provider.createDocument("root/nonexistent_dir", "text/plain", "file.txt");
    }

    // =========================================================================
    // deleteDocument validation
    // =========================================================================

    @Test(expected = FileNotFoundException.class)
    public void testDeleteDocument_baseDir_rejected() throws FileNotFoundException {
        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        provider.deleteDocument("root");
    }

    @Test
    public void testDeleteDocument_validFile() throws IOException, FileNotFoundException {
        File victim = new File(tempBaseDir, "to_delete.txt");
        victim.createNewFile();

        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        provider.deleteDocument("root/to_delete.txt");
        assertFalse(victim.exists());
    }

    @Test(expected = FileNotFoundException.class)
    public void testDeleteDocument_nonExistent_rejected() throws FileNotFoundException {
        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        provider.deleteDocument("root/ghost.txt");
    }

    // =========================================================================
    // queryChildDocuments null-safety
    // =========================================================================

    @Test
    public void testQueryChildDocuments_emptyDir_noException() throws FileNotFoundException {
        File emptyDir = new File(tempBaseDir, "empty");
        emptyDir.mkdir();

        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        // Should not throw even if listFiles returns null (e.g. for inaccessible dirs)
        android.database.Cursor cursor = provider.queryChildDocuments("root/empty", null, null);
        assertEquals(0, cursor.getCount());
    }

    @Test
    public void testQueryChildDocuments_withChildren() throws IOException, FileNotFoundException {
        File dir = new File(tempBaseDir, "parent");
        dir.mkdir();
        new File(dir, "a.txt").createNewFile();
        new File(dir, "b.txt").createNewFile();

        TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
        android.database.Cursor cursor = provider.queryChildDocuments("root/parent", null, null);
        assertEquals(2, cursor.getCount());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
