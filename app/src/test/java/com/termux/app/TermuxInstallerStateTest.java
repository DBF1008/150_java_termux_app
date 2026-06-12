package com.termux.app;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the bootstrap installation state machine logic in {@link TermuxInstaller}.
 *
 * Since the production code uses Android-specific APIs ({@code android.system.Os},
 * {@code FileUtils.regularFileExists()}, etc.) that are unavailable in JVM unit tests,
 * these tests replicate the decision logic using plain {@link java.io.File} API and
 * verify the correctness of the state transitions.
 *
 * The tested scenarios cover:
 * - Fresh install (no prefix)
 * - Empty prefix without marker (triggers reinstall)
 * - Non-empty prefix without marker (corrupt install, triggers reinstall)
 * - Prefix with marker (correctly installed, skip)
 * - Staging directory validation (bin/, lib/ presence)
 * - Retry cleanup contract (staging + prefix + backup all cleaned)
 */
public class TermuxInstallerStateTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File prefixDir;
    private File stagingDir;
    private File markerFile;
    private File backupDir;

    @Before
    public void setUp() throws IOException {
        prefixDir = tempFolder.newFolder("usr");
        stagingDir = new File(tempFolder.getRoot(), "usr-staging");
        markerFile = new File(prefixDir, ".bootstrap-installed");
        backupDir = new File(tempFolder.getRoot(), "usr-backup");
    }

    // ─── Installation Needed Detection Tests ───

    /**
     * Replicates {@code TermuxInstaller.isBootstrapInstallationNeeded()} using plain java.io.File.
     * Returns true if installation should proceed.
     */
    private boolean isBootstrapInstallationNeeded(File prefix, File marker) {
        // Case 1: Marker file exists → fully installed, skip
        if (marker.isFile()) {
            return false;
        }

        // Case 2: Prefix directory exists but no marker → corrupt/partial install
        if (prefix.isDirectory()) {
            return true; // reinstall regardless of empty or not
        }

        // Case 3: Something else at prefix path or nothing at all → install
        return true;
    }

    @Test
    public void testInstallNeeded_noPrefix_returnsTrue() {
        // Delete the prefix that @Before created
        assertTrue("Setup: prefix should exist initially", prefixDir.delete());
        assertFalse("Setup: prefix should be deleted", prefixDir.exists());

        assertTrue("Should need install when prefix missing",
            isBootstrapInstallationNeeded(prefixDir, markerFile));
    }

    @Test
    public void testInstallNeeded_emptyPrefixNoMarker_returnsTrue() {
        // Prefix exists but empty, no marker
        assertTrue("Prefix should exist", prefixDir.isDirectory());
        assertFalse("Marker should not exist", markerFile.exists());

        assertTrue("Should need install when prefix empty without marker",
            isBootstrapInstallationNeeded(prefixDir, markerFile));
    }

    @Test
    public void testInstallNeeded_nonEmptyPrefixNoMarker_returnsTrue() throws IOException {
        // Prefix has content but no marker → corrupt install
        File binDir = new File(prefixDir, "bin");
        assertTrue("Should create bin dir", binDir.mkdirs());
        assertTrue("Should create a file", new File(binDir, "bash").createNewFile());
        assertFalse("Marker should not exist", markerFile.exists());

        assertTrue("Should need install when prefix has content but no marker",
            isBootstrapInstallationNeeded(prefixDir, markerFile));
    }

    @Test
    public void testInstallNeeded_prefixWithMarker_returnsFalse() throws IOException {
        // Prefix with marker → fully installed
        assertTrue("Should create marker", markerFile.createNewFile());

        assertFalse("Should NOT need install when marker exists",
            isBootstrapInstallationNeeded(prefixDir, markerFile));
    }

    @Test
    public void testInstallNeeded_nonEmptyPrefixWithMarker_returnsFalse() throws IOException {
        // Prefix with content AND marker → fully installed
        File binDir = new File(prefixDir, "bin");
        assertTrue("Should create bin dir", binDir.mkdirs());
        assertTrue("Should create a file", new File(binDir, "bash").createNewFile());
        assertTrue("Should create marker", markerFile.createNewFile());

        assertFalse("Should NOT need install when marker exists with content",
            isBootstrapInstallationNeeded(prefixDir, markerFile));
    }

    @Test
    public void testInstallNeeded_markerInDeletedPrefix_returnsTrue() throws IOException {
        // If prefix is deleted, marker inside it is also gone
        assertTrue("Should create marker", markerFile.createNewFile());
        // Delete marker first (it's inside prefix)
        assertTrue("Should delete marker", markerFile.delete());
        // Now delete prefix
        assertTrue("Should delete prefix", prefixDir.delete());

        assertTrue("Should need install when both prefix and marker are gone",
            isBootstrapInstallationNeeded(prefixDir, markerFile));
    }

    // ─── Staging Validation Tests ───

    /**
     * Replicates {@code TermuxInstaller.validateStagingDirectory()} using plain java.io.File.
     */
    private boolean validateStagingDirectory(File stagingDir) {
        File binDir = new File(stagingDir, "bin");
        File libDir = new File(stagingDir, "lib");
        return binDir.isDirectory() && libDir.isDirectory();
    }

    @Test
    public void testValidateStaging_missingDir_returnsFalse() {
        // Staging doesn't exist at all
        assertFalse("Should fail when staging dir doesn't exist",
            validateStagingDirectory(stagingDir));
    }

    @Test
    public void testValidateStaging_missingBin_returnsFalse() throws IOException {
        assertTrue("Should create staging dir", stagingDir.mkdirs());
        assertTrue("Should create lib dir", new File(stagingDir, "lib").mkdirs());
        // No bin/ directory

        assertFalse("Should fail when bin/ missing",
            validateStagingDirectory(stagingDir));
    }

    @Test
    public void testValidateStaging_missingLib_returnsFalse() throws IOException {
        assertTrue("Should create staging dir", stagingDir.mkdirs());
        assertTrue("Should create bin dir", new File(stagingDir, "bin").mkdirs());
        // No lib/ directory

        assertFalse("Should fail when lib/ missing",
            validateStagingDirectory(stagingDir));
    }

    @Test
    public void testValidateStaging_complete_returnsTrue() throws IOException {
        assertTrue("Should create staging dir", stagingDir.mkdirs());
        assertTrue("Should create bin dir", new File(stagingDir, "bin").mkdirs());
        assertTrue("Should create lib dir", new File(stagingDir, "lib").mkdirs());

        assertTrue("Should pass when bin/ and lib/ exist",
            validateStagingDirectory(stagingDir));
    }

    @Test
    public void testValidateStaging_extraDirsStillValid() throws IOException {
        assertTrue("Should create staging dir", stagingDir.mkdirs());
        assertTrue("Should create bin dir", new File(stagingDir, "bin").mkdirs());
        assertTrue("Should create lib dir", new File(stagingDir, "lib").mkdirs());
        assertTrue("Should create etc dir", new File(stagingDir, "etc").mkdirs());
        assertTrue("Should create share dir", new File(stagingDir, "share").mkdirs());

        assertTrue("Should pass with extra directories",
            validateStagingDirectory(stagingDir));
    }

    // ─── Cleanup Path Tests ───

    @Test
    public void testRetryCleanup_removesAllThreeDirectories() throws IOException {
        // Simulate state after failed install: staging, prefix, and backup all exist
        assertTrue("Should create staging dir", stagingDir.mkdirs());
        new File(stagingDir, "partial-file").createNewFile();
        assertTrue("Prefix should exist from setUp", prefixDir.isDirectory());
        assertTrue("Should create backup dir", backupDir.mkdirs());
        new File(backupDir, "old-file").createNewFile();

        // Verify all three exist
        assertTrue("Staging should exist", stagingDir.exists());
        assertTrue("Prefix should exist", prefixDir.exists());
        assertTrue("Backup should exist", backupDir.exists());

        // Perform the same cleanup as the retry button
        deleteRecursive(stagingDir);
        deleteRecursive(prefixDir);
        deleteRecursive(backupDir);

        // Verify all three are gone
        assertFalse("Staging should be deleted", stagingDir.exists());
        assertFalse("Prefix should be deleted", prefixDir.exists());
        assertFalse("Backup should be deleted", backupDir.exists());
    }

    @Test
    public void testRetryCleanup_handlesNonExistentDirs() {
        // None of them exist - cleanup should not throw
        assertFalse("Staging should not exist", stagingDir.exists());
        // Delete prefix manually
        prefixDir.delete();
        assertFalse("Backup should not exist", backupDir.exists());

        // Should not throw
        deleteRecursive(stagingDir);
        deleteRecursive(prefixDir);
        deleteRecursive(backupDir);
    }

    // ─── Backup and Rollback Tests ───

    @Test
    public void testBackupRename_prefixMovedToBackup() throws IOException {
        // Simulate: prefix exists with content, rename to backup
        File binDir = new File(prefixDir, "bin");
        assertTrue("Should create bin dir", binDir.mkdirs());
        assertTrue("Should create file", new File(binDir, "login").createNewFile());

        // Rename prefix → backup
        assertTrue("Should rename prefix to backup", prefixDir.renameTo(backupDir));

        assertFalse("Prefix should no longer exist", prefixDir.exists());
        assertTrue("Backup should exist", backupDir.isDirectory());
        assertTrue("Backup should contain bin/", new File(backupDir, "bin").isDirectory());
        assertTrue("Backup should contain bin/login", new File(backupDir, "bin/login").isFile());
    }

    @Test
    public void testRollback_backupRestoredToPrefix() throws IOException {
        // Setup: backup exists, prefix doesn't
        assertTrue("Should delete prefix", prefixDir.delete());
        assertTrue("Should create backup dir", backupDir.mkdirs());
        assertTrue("Should create bin in backup", new File(backupDir, "bin").mkdirs());
        assertTrue("Should create file in backup", new File(backupDir, "bin/login").createNewFile());

        // Rollback: rename backup → prefix
        assertTrue("Should rename backup to prefix", backupDir.renameTo(prefixDir));

        assertTrue("Prefix should exist after rollback", prefixDir.isDirectory());
        assertFalse("Backup should not exist after rollback", backupDir.exists());
        assertTrue("Prefix should contain bin/login",
            new File(prefixDir, "bin/login").isFile());
    }

    @Test
    public void testStagingToPrefix_renameWorks() throws IOException {
        // Setup: staging has content, prefix path is clear
        assertTrue("Should create staging dir", stagingDir.mkdirs());
        assertTrue("Should create bin dir", new File(stagingDir, "bin").mkdirs());
        assertTrue("Should delete prefix", prefixDir.delete());

        // Move staging → prefix
        assertTrue("Should rename staging to prefix", stagingDir.renameTo(prefixDir));

        assertTrue("Prefix should exist", prefixDir.isDirectory());
        assertFalse("Staging should not exist", stagingDir.exists());
        assertTrue("Prefix should have bin/", new File(prefixDir, "bin").isDirectory());
    }

    // ─── Marker File Tests ───

    @Test
    public void testMarkerFile_insidePrefix_deletedWithPrefix() throws IOException {
        // Create marker inside prefix
        assertTrue("Should create marker", markerFile.createNewFile());
        assertTrue("Marker should exist", markerFile.isFile());

        // Delete prefix recursively
        deleteRecursive(prefixDir);

        assertFalse("Prefix should not exist", prefixDir.exists());
        assertFalse("Marker should not exist after prefix deletion", markerFile.exists());
    }

    @Test
    public void testMarkerFile_creationAfterInstall() throws IOException {
        // Simulate post-install: prefix exists, write marker
        assertTrue("Prefix should exist", prefixDir.isDirectory());
        assertFalse("Marker should not exist yet", markerFile.exists());

        // Write marker
        assertTrue("Should create marker", markerFile.createNewFile());

        assertTrue("Marker should exist", markerFile.isFile());
        assertTrue("Should be detected as installed",
            !isBootstrapInstallationNeeded(prefixDir, markerFile));
    }

    // ─── Helper methods ───

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
