package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If the bootstrap installed marker file exists inside $PREFIX, assume installation is
 * complete and skip. If $PREFIX exists without the marker, treat it as a corrupt/partial
 * install and reinstall.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from a broken installation.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is extracted into $STAGING_PREFIX:
 * <p/>
 * (5.1) If the zip entry is SYMLINKS.txt, parse it and remember all symlinks to set up.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions
 * if necessary.
 * <p/>
 * (6) Validate that the staging directory contains expected content (bin/, lib/).
 * <p/>
 * (7) If an existing $PREFIX exists, rename it to $PREFIX-backup (deferred deletion).
 * <p/>
 * (8) Rename $STAGING_PREFIX to $PREFIX. If rename fails, attempt copy+delete fallback.
 * If that also fails and a backup exists, rollback by restoring the backup.
 * <p/>
 * (9) Delete the backup directory.
 * <p/>
 * (10) Validate and fix $PREFIX permissions, write the bootstrap installed marker file,
 * and recreate the environment file.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Path suffix for the backup of an existing prefix directory during installation. */
    private static final String PREFIX_BACKUP_SUFFIX = "-backup";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // Check if installation is needed using the marker-based detection
        if (!isBootstrapInstallationNeeded()) {
            whenDone.run();
            return;
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;
                    boolean hadExistingPrefix = FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true);

                    // ── Step 1: Clean stale staging directory ──
                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // ── Step 2: Create fresh staging directory ──
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // ── Step 3: Extract zip to staging and create symlinks ──
                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");
                    final byte[] zipBytes = loadZipBytes();
                    extractBootstrapZip(zipBytes, TERMUX_STAGING_PREFIX_DIR_PATH);

                    // ── Step 4: Validate staging completeness ──
                    if (!validateStagingDirectory(TERMUX_STAGING_PREFIX_DIR_PATH)) {
                        throw new RuntimeException("Staging directory validation failed after extraction: " +
                            "required directories (bin/, lib/) are missing");
                    }

                    // ═══ POINT OF NO RETURN ═══
                    // From here, we modify the prefix directory.
                    // If anything fails, we attempt rollback from backup.

                    // ── Step 5: Backup existing prefix (or delete if no prefix) ──
                    String backupPath = null;
                    if (hadExistingPrefix) {
                        backupPath = TERMUX_PREFIX_DIR_PATH + PREFIX_BACKUP_SUFFIX;
                        // Delete any stale backup first
                        FileUtils.deleteFile("old prefix backup", backupPath, true);

                        if (!TERMUX_PREFIX_DIR.renameTo(new File(backupPath))) {
                            // Can't backup → fall back to delete
                            Logger.logWarn(LOG_TAG, "Failed to rename prefix to backup, deleting instead.");
                            error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                            if (error != null) {
                                showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                return;
                            }
                            backupPath = null;
                        }
                    } else {
                        // No existing prefix, just ensure nothing is at the path
                        error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        if (error != null) {
                            showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                            return;
                        }
                    }

                    // ── Step 6: Move staging → prefix ──
                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        // renameTo failed → try copy+delete fallback
                        Logger.logWarn(LOG_TAG, "renameTo() failed, attempting copy+delete fallback.");
                        error = FileUtils.moveDirectoryFile("termux prefix staging",
                            TERMUX_STAGING_PREFIX_DIR_PATH, TERMUX_PREFIX_DIR_PATH, false);
                        if (error != null) {
                            // CRITICAL: Rollback from backup if available
                            rollbackFromBackup(backupPath);
                            showBootstrapErrorDialog(activity, whenDone,
                                "Moving staging to prefix directory failed.\n" + Error.getErrorMarkdownString(error));
                            return;
                        }
                    }

                    // ── Step 7: Cleanup backup and stale staging ──
                    if (backupPath != null) {
                        Error backupDeleteError = FileUtils.deleteFile("prefix backup", backupPath, true);
                        if (backupDeleteError != null) {
                            // Non-fatal: log warning but continue
                            Logger.logWarn(LOG_TAG, "Failed to delete prefix backup: " + backupDeleteError.getMessage());
                        }
                    }

                    // Defensive cleanup: staging should be gone after rename, but ensure it
                    FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);

                    // ── Step 8: Validate and fix prefix permissions ──
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(false, true);
                    if (error != null) {
                        Logger.logWarn(LOG_TAG, "Post-install prefix permission validation failed: " + error.getMessage());
                        // Attempt to fix
                        FileUtils.setMissingFilePermissions("termux prefix directory",
                            TERMUX_PREFIX_DIR_PATH, FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS);
                    }

                    // ── Step 9: Write bootstrap installed marker file ──
                    error = FileUtils.createRegularFile("bootstrap installed marker",
                        TermuxConstants.TERMUX_BOOTSTRAP_INSTALLED_MARKER_FILE_PATH);
                    if (error != null) {
                        Logger.logWarn(LOG_TAG, "Failed to write bootstrap marker: " + error.getMessage());
                        // Non-fatal but log it; next launch will detect missing marker and reinstall
                    }

                    // ── Step 10: Write environment file ──
                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    /**
     * Determines if bootstrap installation is needed based on the marker file and prefix state.
     *
     * @return {@code true} if installation should proceed, {@code false} if already correctly installed.
     */
    private static boolean isBootstrapInstallationNeeded() {
        // Case 1: Marker file exists → fully installed, skip
        if (FileUtils.regularFileExists(TermuxConstants.TERMUX_BOOTSTRAP_INSTALLED_MARKER_FILE_PATH, false)) {
            Logger.logInfo(LOG_TAG, "Bootstrap installed marker file found at \""
                + TermuxConstants.TERMUX_BOOTSTRAP_INSTALLED_MARKER_FILE_PATH + "\". Installation not needed.");
            return false;
        }

        // Case 2: Prefix directory exists but no marker → corrupt/partial install from previous run
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH
                    + "\" exists but is empty and no marker file found. Will reinstall.");
            } else {
                Logger.logWarn(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH
                    + "\" exists with content but without marker file. Assuming corrupt install. Will reinstall.");
            }
            return true;
        }

        // Case 3: Something else exists at prefix path (e.g., a regular file)
        if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH
                + "\" does not exist but another file exists at its destination. Will reinstall.");
            return true;
        }

        // Case 4: Prefix doesn't exist at all → fresh install
        Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH
            + "\" does not exist. Will install.");
        return true;
    }

    /**
     * Validates that the staging directory contains expected content after extraction.
     * Checks for key directories that MUST exist in a valid bootstrap.
     *
     * @param stagingDirPath The path to the staging directory.
     * @return {@code true} if the staging directory appears complete.
     */
    static boolean validateStagingDirectory(String stagingDirPath) {
        String stagingBin = stagingDirPath + "/bin";
        String stagingLib = stagingDirPath + "/lib";

        if (!FileUtils.directoryFileExists(stagingBin, false)) {
            Logger.logError(LOG_TAG, "Staging validation failed: bin/ directory missing at \"" + stagingBin + "\"");
            return false;
        }
        if (!FileUtils.directoryFileExists(stagingLib, false)) {
            Logger.logError(LOG_TAG, "Staging validation failed: lib/ directory missing at \"" + stagingLib + "\"");
            return false;
        }
        Logger.logInfo(LOG_TAG, "Staging directory validation passed.");
        return true;
    }

    /**
     * Attempts to restore prefix from backup after a failed staging→prefix move.
     *
     * @param backupPath The path to the backup directory, or {@code null} if no backup exists.
     */
    private static void rollbackFromBackup(String backupPath) {
        if (backupPath == null) {
            Logger.logWarn(LOG_TAG, "No backup available for rollback. Prefix directory is missing.");
            return;
        }

        File backupDir = new File(backupPath);
        if (!backupDir.exists()) {
            Logger.logWarn(LOG_TAG, "Backup directory does not exist at \"" + backupPath + "\" for rollback.");
            return;
        }

        Logger.logInfo(LOG_TAG, "Attempting rollback: restoring prefix from backup at \"" + backupPath + "\".");

        // Try rename back
        if (backupDir.renameTo(TERMUX_PREFIX_DIR)) {
            Logger.logInfo(LOG_TAG, "Rollback successful: prefix restored from backup.");
            return;
        }

        // Fallback: copy+move
        Error error = FileUtils.moveDirectoryFile("prefix rollback", backupPath, TERMUX_PREFIX_DIR_PATH, false);
        if (error != null) {
            Logger.logError(LOG_TAG, "CRITICAL: Rollback failed! User has no working prefix. " + error.getMessage());
        } else {
            Logger.logInfo(LOG_TAG, "Rollback successful: prefix restored via copy from backup.");
        }
    }

    /**
     * Extracts bootstrap zip bytes to the given directory and creates symlinks.
     * Package-private for testing.
     *
     * @param zipBytes The raw zip bytes.
     * @param targetDirPath The target directory path to extract into.
     * @throws Exception on any extraction or symlink failure.
     */
    static void extractBootstrapZip(byte[] zipBytes, String targetDirPath) throws Exception {
        final byte[] buffer = new byte[8096];
        final List<Pair<String, String>> symlinks = new ArrayList<>(50);

        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInput.getNextEntry()) != null) {
                if (zipEntry.getName().equals("SYMLINKS.txt")) {
                    BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                    String line;
                    while ((line = symlinksReader.readLine()) != null) {
                        String[] parts = line.split("←");
                        if (parts.length != 2)
                            throw new RuntimeException("Malformed symlink line: " + line);
                        String oldPath = parts[0];
                        String newPath = targetDirPath + "/" + parts[1];
                        symlinks.add(Pair.create(oldPath, newPath));

                        Error error = ensureDirectoryExists(new File(newPath).getParentFile());
                        if (error != null)
                            throw new RuntimeException("Failed to create parent directory for symlink: " + error.getMessage());
                    }
                } else {
                    String zipEntryName = zipEntry.getName();
                    File targetFile = new File(targetDirPath, zipEntryName);
                    boolean isDirectory = zipEntry.isDirectory();

                    Error error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                    if (error != null)
                        throw new RuntimeException("Failed to create directory during extraction: " + error.getMessage());

                    if (!isDirectory) {
                        try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                            int readBytes;
                            while ((readBytes = zipInput.read(buffer)) != -1)
                                outStream.write(buffer, 0, readBytes);
                        }
                        if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                            zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                            //noinspection OctalInteger
                            Os.chmod(targetFile.getAbsolutePath(), 0700);
                        }
                    }
                }
            }
        }

        if (symlinks.isEmpty())
            throw new RuntimeException("No SYMLINKS.txt encountered in bootstrap zip");

        for (Pair<String, String> symlink : symlinks) {
            Os.symlink(symlink.first, symlink.second);
        }
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        // Clean ALL three directories to ensure a completely fresh retry
                        FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        FileUtils.deleteFile("termux prefix backup directory", TERMUX_PREFIX_DIR_PATH + PREFIX_BACKUP_SUFFIX, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
