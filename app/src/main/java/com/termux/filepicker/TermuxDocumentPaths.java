package com.termux.filepicker;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Path and document-identifier logic for {@link TermuxDocumentsProvider}, deliberately kept
 * free of any Android (and {@code com.termux.shared}) dependencies so the security-sensitive
 * scope checks can be exercised by plain JVM unit tests. The base directory is injected as a
 * {@link File} rather than read from constants for the same reason.
 * <p/>
 * The provider exposes a single directory subtree (Termux {@code $HOME}) through the Storage
 * Access Framework. Document ids are absolute filesystem paths, and this class is the single
 * chokepoint that both maps between files and ids and guarantees that a resolved file actually
 * lives inside the allowed base directory before any operation touches it.
 * <p/>
 * Containment is always decided on <em>canonical</em> paths so a symlink whose target escapes
 * the base directory is rejected. On Android {@code /data/data/<pkg>} is itself a symlink (to
 * {@code /data/user/0/<pkg>}), so canonical and absolute paths differ for every file under
 * {@code $HOME}; hence both sides are canonicalized before comparison, and symlink detection is
 * done at the final path component only (see {@link #isSymlink(File)}). {@code java.nio.file}
 * is intentionally not used here because the app supports API levels below 26.
 */
final class TermuxDocumentPaths {

    private TermuxDocumentPaths() {}

    /**
     * The document id for a file: its absolute path. Ids must stay stable over time because
     * other apps persist them via the Storage Access Framework, so this scheme must not change
     * and every id handed back to the framework must be produced here. In particular a
     * canonical path must never be used as an id: on Android it would live under a different
     * prefix ({@code /data/user/0}) and break the framework's string-prefix id matching.
     */
    static String getDocIdForFile(File file) {
        return file.getAbsolutePath();
    }

    /**
     * Whether {@code file}'s canonical location is the base directory itself or somewhere
     * beneath it. Both paths are canonicalized so a symlink pointing outside the base is
     * excluded. Fails closed: any I/O error while resolving a path is treated as "not in
     * scope" rather than granting access.
     */
    static boolean isInScope(File file, File baseDir) {
        try {
            final String base = baseDir.getCanonicalPath();
            final String target = file.getCanonicalPath();
            return target.equals(base) || target.startsWith(base + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Resolve a document id to a file, guaranteeing it exists and lives inside {@code baseDir}.
     * This is the single entry point every provider operation must use to turn an
     * externally-supplied id into a {@link File}; the returned file keeps its absolute path
     * (containment is decided on canonical paths internally, but no canonical path is exposed).
     *
     * @throws FileNotFoundException if the id does not exist or resolves outside the base dir
     */
    static File resolveFileForDocId(String docId, File baseDir) throws FileNotFoundException {
        final File file = new File(docId);
        if (!file.exists() || !isInScope(file, baseDir)) {
            throw new FileNotFoundException("Document " + docId + " not found within allowed storage");
        }
        return file;
    }

    /**
     * Whether {@code docId} is the same as, or a descendant of, {@code parentDocId}. The
     * comparison uses a trailing separator so a sibling such as {@code /a/bc} is not mistaken
     * for a child of {@code /a/b}. Both arguments are the absolute-path id strings produced by
     * {@link #getDocIdForFile(File)}.
     */
    static boolean isChildDocument(String parentDocId, String docId) {
        return docId.equals(parentDocId) || docId.startsWith(parentDocId + File.separator);
    }

    /**
     * Validate and normalize a display name supplied for a new document, rejecting anything
     * that could escape the parent directory or denote a path rather than a single entry.
     *
     * @return the trimmed name
     * @throws FileNotFoundException if the name is empty, {@code "."}/{@code ".."}, or contains
     *                               a path separator or NUL character
     */
    static String sanitizeDisplayName(String displayName) throws FileNotFoundException {
        final String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0) {
            throw new FileNotFoundException("Invalid document name: " + displayName);
        }
        return name;
    }

    /**
     * Whether {@code file} is itself a symbolic link, as opposed to a real file or directory
     * that merely sits beneath a symlinked ancestor (which is the case for everything under
     * {@code $HOME} on Android). Only the final path component is inspected: canonicalizing the
     * parent and re-appending the name must reproduce the file's own canonical path. Fails
     * safe: if the link status cannot be determined it is reported as a link, so callers never
     * recurse through it. {@code java.nio.file.Files} is intentionally avoided (min API < 26).
     */
    static boolean isSymlink(File file) {
        try {
            final File parent = file.getAbsoluteFile().getParentFile();
            if (parent == null) return false;
            final File expected = new File(parent.getCanonicalFile(), file.getName());
            return !file.getCanonicalFile().equals(expected);
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Recursively delete a file or directory, returning whether it was fully removed.
     * Directories are emptied depth-first before removal so non-empty directories delete
     * cleanly. Symbolic links are unlinked but never followed: {@link File#delete()} removes
     * the link itself rather than its target, so a link pointing outside the deleted tree (for
     * example outside {@code $HOME}) can never cause data outside that tree to be destroyed.
     */
    static boolean deleteRecursively(File file) {
        if (file.isDirectory() && !isSymlink(file)) {
            final File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }
}
