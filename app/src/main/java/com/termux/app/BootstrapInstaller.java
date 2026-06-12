package com.termux.app;

import com.termux.shared.errors.Error;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Android-independent orchestration of the Termux bootstrap installation.
 * <p/>
 * It performs the whole install as a single, retry-safe pipeline:
 * <ol>
 *     <li>Clean up any leftover {@code $STAGING_PREFIX} and any leftover at {@code $PREFIX}
 *     (of any file type, including dangling symlinks), so a retry always starts from a clean state.</li>
 *     <li>Create only the {@code $STAGING_PREFIX} directory.</li>
 *     <li>Extract the bootstrap zip into {@code $STAGING_PREFIX}, collecting the symlinks listed in
 *     {@code SYMLINKS.txt} and setting execute permissions on the relevant entries.</li>
 *     <li>Recreate the collected symlinks (idempotently).</li>
 *     <li>Atomically move {@code $STAGING_PREFIX} onto {@code $PREFIX}.</li>
 *     <li>Validate (and repair missing) {@code $PREFIX} permissions.</li>
 * </ol>
 * <p/>
 * Core invariant relied upon by {@link TermuxInstaller}'s startup fast-path: {@code $PREFIX} is
 * <em>only</em> ever created by the final atomic move of a fully-populated staging directory. A
 * failed or interrupted install therefore never leaves a non-empty-but-incomplete {@code $PREFIX}
 * behind, which is exactly what previously caused a "stuck half-install" on re-entry.
 * <p/>
 * All filesystem side effects that depend on {@code android.system.Os} (delete-any-type, directory
 * creation with permissions, symlink creation, chmod, atomic rename and permission validation) go
 * through {@link FsOps} so this orchestration can be unit-tested against a real temporary directory
 * with a {@code java.nio}-backed fake, while production delegates to
 * {@code com.termux.shared.file.FileUtils} / {@code android.system.Os}.
 */
final class BootstrapInstaller {

    /** Name of the zip entry that lists the symlinks to recreate, one {@code target←link} per line. */
    static final String SYMLINKS_FILE_NAME = "SYMLINKS.txt";

    /** Separator between the symlink target and the link path in {@link #SYMLINKS_FILE_NAME}. */
    private static final String SYMLINK_SEPARATOR = "←";

    private BootstrapInstaller() {}

    /** Filesystem operations that touch {@code android.system.Os}; injected so the pipeline is testable. */
    interface FsOps {
        /** Delete the file at {@code path} of any type, ignoring non-existence. */
        Error deleteAnyType(String label, String path);

        /** Create the directory at {@code path} (with the app working-directory permissions). */
        Error createDirectory(String label, String path);

        /** Create a symlink at {@code dest} pointing to {@code target}, overwriting an existing symlink. */
        Error createSymlink(String label, String target, String dest);

        /** Set execute permission on the file at {@code path}. */
        Error setExecutable(String path);

        /** Atomically move {@code fromPath} to {@code toPath}. Must NOT fall back to a non-atomic copy. */
        Error atomicMove(String fromPath, String toPath);

        /** Validate, and repair if missing, the permissions of the {@code $PREFIX} directory. */
        Error validatePrefixPermissions(String prefixPath);
    }

    /**
     * Run the full bootstrap install pipeline.
     *
     * @param zipBytes    The bootstrap zip contents.
     * @param stagingPath The {@code $STAGING_PREFIX} path.
     * @param prefixPath  The {@code $PREFIX} path.
     * @param fs          The filesystem operations to use.
     * @return The first {@link Error} encountered, or {@code null} on success.
     */
    static Error install(byte[] zipBytes, String stagingPath, String prefixPath, FsOps fs) {
        Error error;

        // (1) Single, consistent cleanup of BOTH a leftover staging directory and any leftover at
        // $PREFIX (any file type, including a dangling symlink). This is the one rollback/cleanup
        // routine shared by the first install and every retry.
        error = fs.deleteAnyType("termux prefix staging directory", stagingPath);
        if (error != null) return error;
        error = fs.deleteAnyType("termux prefix directory", prefixPath);
        if (error != null) return error;

        // (2) Create ONLY the staging directory. $PREFIX is intentionally not created here; it is
        // born solely from the atomic move in step (5), preserving the no-broken-$PREFIX invariant.
        error = fs.createDirectory("termux prefix staging directory", stagingPath);
        if (error != null) return error;

        // (3) Extract the zip into staging, collecting symlinks from SYMLINKS.txt.
        List<String[]> symlinks = new ArrayList<>(50);
        error = extractZip(zipBytes, stagingPath, symlinks, fs);
        if (error != null) return error;

        if (symlinks.isEmpty())
            return new Error("No " + SYMLINKS_FILE_NAME + " encountered");

        // (4) Recreate the symlinks. createSymlink is idempotent/recoverable so a partial prior
        // state at a destination does not abort the install.
        for (String[] symlink : symlinks) {
            error = fs.createSymlink("bootstrap symlink", symlink[0], symlink[1]);
            if (error != null) return error;
        }

        // (5) Atomically move staging onto $PREFIX. There is deliberately no copy fallback: a failed
        // move leaves staging intact and $PREFIX absent, never a partially populated $PREFIX.
        error = fs.atomicMove(stagingPath, prefixPath);
        if (error != null) return error;

        // (6) Guarantee consistent permissions on the moved-in $PREFIX.
        return fs.validatePrefixPermissions(prefixPath);
    }

    private static Error extractZip(byte[] zipBytes, String stagingPath, List<String[]> symlinks, FsOps fs) {
        final byte[] buffer = new byte[8096];
        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInput.getNextEntry()) != null) {
                Error error;
                if (zipEntry.getName().equals(SYMLINKS_FILE_NAME)) {
                    error = readSymlinksEntry(zipInput, stagingPath, symlinks);
                } else {
                    error = extractFileEntry(zipInput, zipEntry, stagingPath, buffer, fs);
                }
                if (error != null) return error;
            }
        } catch (IOException e) {
            return new Error("Failed to extract bootstrap zip to \"" + stagingPath + "\": " + e.getMessage());
        }
        return null;
    }

    private static Error readSymlinksEntry(InputStream zipInput, String stagingPath, List<String[]> symlinks) throws IOException {
        // Intentionally not closed: the reader wraps the shared ZipInputStream.
        BufferedReader reader = new BufferedReader(new InputStreamReader(zipInput, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split(SYMLINK_SEPARATOR);
            if (parts.length != 2)
                return new Error("Malformed symlink line: " + line);
            String target = parts[0];
            String dest = stagingPath + "/" + parts[1];
            symlinks.add(new String[]{target, dest});
        }
        return null;
    }

    private static Error extractFileEntry(ZipInputStream zipInput, ZipEntry zipEntry, String stagingPath, byte[] buffer, FsOps fs) throws IOException {
        String zipEntryName = zipEntry.getName();
        File targetFile = new File(stagingPath, zipEntryName);
        boolean isDirectory = zipEntry.isDirectory();

        // Entry parent directories are created without forcing permissions, matching the original
        // extraction behaviour (only the specific executable entries below get an explicit chmod).
        Error error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
        if (error != null) return error;

        if (!isDirectory) {
            try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                int readBytes;
                while ((readBytes = zipInput.read(buffer)) != -1)
                    outStream.write(buffer, 0, readBytes);
            }
            if (isExecutableEntry(zipEntryName)) {
                error = fs.setExecutable(targetFile.getAbsolutePath());
                if (error != null) return error;
            }
        }
        return null;
    }

    /** Whether a bootstrap zip entry must be made executable, mirroring the upstream entry-name checks. */
    private static boolean isExecutableEntry(String zipEntryName) {
        return zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
            zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods");
    }

    /** Create a directory (and parents) without altering permissions; java.io only so it is host-portable. */
    private static Error ensureDirectoryExists(File directory) {
        if (directory == null || directory.isDirectory())
            return null;
        if (!directory.mkdirs() && !directory.isDirectory())
            return new Error("Failed to create directory \"" + directory.getAbsolutePath() + "\"");
        return null;
    }
}
