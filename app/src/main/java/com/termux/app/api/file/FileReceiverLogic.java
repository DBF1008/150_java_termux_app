package com.termux.app.api.file;

import androidx.annotation.Nullable;

import com.termux.shared.data.DataUtils;
import com.termux.shared.file.FileUtils;

import java.io.File;

/**
 * Pure logic utilities for the file receiver pipeline. No Android framework dependencies
 * (except {@link DataUtils} and {@link FileUtils} which are themselves pure).
 * <p>
 * All methods are static and stateless, making them safe to call from any context and
 * trivial to unit-test with plain JUnit.
 */
public final class FileReceiverLogic {

    /** Fallback name used when no metadata is available from the sharing intent. */
    public static final String DEFAULT_FILE_NAME = "shared-file";

    private FileReceiverLogic() { /* no instances */ }

    // ──────────────────────────────────────────────────────────────────────
    // 1. Unified filename resolution
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Resolve the default file name from the various metadata sources available
     * on an incoming share intent.
     * <p>
     * Priority chain (first non-blank wins):
     * <pre>
     *   displayName  →  extraTitle  →  extraSubject  →  uriBasename  →  DEFAULT_FILE_NAME
     * </pre>
     *
     * @param displayName  {@code OpenableColumns.DISPLAY_NAME} from ContentResolver (may be null).
     * @param extraTitle   {@code Intent.EXTRA_TITLE} (may be null).
     * @param extraSubject {@code Intent.EXTRA_SUBJECT} (may be null).
     * @param uriBasename  Basename extracted from the content/file URI (may be null).
     * @param isPlainText  {@code true} when the shared content is plain text; if the resolved
     *                     name has no extension, {@code .txt} will be appended.
     * @return A non-null, non-empty file name.
     */
    public static String resolveDefaultName(@Nullable String displayName,
                                            @Nullable String extraTitle,
                                            @Nullable String extraSubject,
                                            @Nullable String uriBasename,
                                            boolean isPlainText) {
        String name = firstNonBlank(displayName, extraTitle, extraSubject, uriBasename);

        if (DataUtils.isNullOrEmpty(name)) {
            name = DEFAULT_FILE_NAME;
        }

        // For plain text shares, ensure a .txt extension is present.
        if (isPlainText && !name.contains(".")) {
            name = name + ".txt";
        }

        return name;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2. Conflict resolution
    // ──────────────────────────────────────────────────────────────────────

    /**
     * If a file named {@code desiredName} already exists in {@code dir}, return a new
     * non-conflicting name by appending {@code " (N)"} before the extension.
     * <p>
     * Pattern: {@code "report.pdf"} → {@code "report (2).pdf"} → {@code "report (3).pdf"}.
     * <p>
     * Mirrors the logic in {@code TermuxDocumentsProvider#createDocument}.
     *
     * @param desiredName The requested file name.
     * @param dir         The target directory to check for existing files.
     * @return A file name that does not conflict with any existing file in {@code dir}.
     */
    public static String resolveConflict(String desiredName, File dir) {
        if (desiredName == null || dir == null) return desiredName;

        File candidate = new File(dir, desiredName);
        if (!candidate.exists()) return desiredName;

        String baseName = getFileBasenameWithoutExtension(desiredName);
        String extension = getExtensionWithDot(desiredName); // ".pdf" or ""

        int counter = 2;
        while (true) {
            String newName = baseName + " (" + counter + ")" + extension;
            if (!new File(dir, newName).exists()) return newName;
            counter++;
            if (counter > 9999) {
                // Safety valve – practically unreachable.
                throw new IllegalStateException("Too many duplicate files for: " + desiredName);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3. Safe file name = sanitize + conflict
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Produce a safe, non-conflicting file name for writing into {@code targetDir}:
     * <ol>
     *   <li>Sanitize unsafe characters (path separators, shell metacharacters, whitespace).</li>
     *   <li>Resolve naming conflicts against existing files.</li>
     * </ol>
     *
     * @param rawName   The user-supplied or resolved file name.
     * @param targetDir The directory where the file will be written.
     * @return A sanitized, non-conflicting file name.
     */
    public static String resolveSafeFileName(String rawName, File targetDir) {
        if (rawName == null) return DEFAULT_FILE_NAME;

        // Step 1: Sanitize – replace unsafe chars and whitespace with underscore.
        String sanitized = FileUtils.sanitizeFileName(rawName, true, false);

        // Guard against degenerate result (e.g. input was all special chars).
        if (DataUtils.isNullOrEmpty(sanitized)) {
            sanitized = DEFAULT_FILE_NAME;
        }

        // Step 2: Resolve conflicts.
        if (targetDir != null) {
            sanitized = resolveConflict(sanitized, targetDir);
        }

        return sanitized;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Return the first non-null, non-blank string from the candidates, or {@code null}
     * if all are null/blank.
     */
    @Nullable
    static String firstNonBlank(@Nullable String... candidates) {
        if (candidates == null) return null;
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) return c.trim();
        }
        return null;
    }

    /**
     * Get the part of {@code fileName} before the last dot. If there is no dot, returns
     * the full name.
     * <p>
     * Examples: {@code "report.pdf" → "report"}, {@code "archive.tar.gz" → "archive.tar"},
     * {@code "Makefile" → "Makefile"}.
     */
    static String getFileBasenameWithoutExtension(String fileName) {
        if (fileName == null) return null;
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot <= 0) ? fileName : fileName.substring(0, lastDot);
    }

    /**
     * Get the extension of {@code fileName} including the leading dot.
     * Returns {@code ""} if there is no extension.
     * <p>
     * Examples: {@code "report.pdf" → ".pdf"}, {@code "Makefile" → ""}.
     * <p>
     * Leading-dot files like {@code ".gitignore"} are treated as having no extension
     * (the dot is part of the name, not an extension separator).
     */
    static String getExtensionWithDot(String fileName) {
        if (fileName == null) return "";
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot <= 0) ? "" : fileName.substring(lastDot);
    }
}
