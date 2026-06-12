package com.termux.filepicker;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;

/**
 * A document provider for the Storage Access Framework which exposes the files in the
 * $HOME/ directory to other apps.
 * <p/>
 * Note that this replaces providing an activity matching the ACTION_GET_CONTENT intent:
 * <p/>
 * "A document provider and ACTION_GET_CONTENT should be considered mutually exclusive. If you
 * support both of them simultaneously, your app will appear twice in the system picker UI,
 * offering two different ways of accessing your stored data. This would be confusing for users."
 * - http://developer.android.com/guide/topics/providers/document-provider.html#43
 * <p/>
 * Document IDs use a relative-path scheme: the root is {@code "root"}, and child documents
 * are {@code "root/subdir/file.txt"}. All paths are validated via canonical path resolution
 * to prevent symlink or path-traversal escapes outside the $HOME directory.
 */
public class TermuxDocumentsProvider extends DocumentsProvider {

    private static final String ALL_MIME_TYPES = "*/*";

    static File BASE_DIR = TermuxConstants.TERMUX_HOME_DIR;

    /**
     * The document ID prefix representing the base directory itself.
     */
    static final String ROOT_DOC_ID = "root";

    /**
     * Cached canonical path of BASE_DIR, initialized in {@link #onCreate()}.
     * Package-private for testing.
     */
    static String BASE_DIR_CANONICAL;


    // The default columns to return information about a root if no specific
    // columns are requested in a query.
    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_AVAILABLE_BYTES
    };

    // The default columns to return information about a document if no specific
    // columns are requested in a query.
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE
    };

    @Override
    public Cursor queryRoots(String[] projection) {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        final String applicationName = getContext().getString(R.string.application_name);

        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, ROOT_DOC_ID);
        row.add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID);
        row.add(Root.COLUMN_SUMMARY, null);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_SEARCH | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_TITLE, applicationName);
        row.add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES);
        row.add(Root.COLUMN_AVAILABLE_BYTES, BASE_DIR.getFreeSpace());
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File parent = getFileForDocId(parentDocumentId);
        File[] children = parent.listFiles();
        if (children != null) {
            for (File file : children) {
                includeFile(result, null, file);
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(final String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        final int accessMode = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, accessMode);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        return new AssetFileDescriptor(pfd, 0, file.length());
    }

    @Override
    public boolean onCreate() {
        try {
            BASE_DIR_CANONICAL = BASE_DIR.getCanonicalPath();
        } catch (IOException e) {
            // Fallback to absolute path if canonical resolution fails
            BASE_DIR_CANONICAL = BASE_DIR.getAbsolutePath();
        }
        return true;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = getFileForDocId(parentDocumentId);
        File newFile = new File(parent, displayName);
        int noConflictId = 2;
        while (newFile.exists()) {
            newFile = new File(parent, displayName + " (" + noConflictId++ + ")");
        }
        // Validate that the new file path is within BASE_DIR (prevents "../" escapes in displayName)
        enforceInsideBaseDir(newFile);
        try {
            boolean succeeded;
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                succeeded = newFile.mkdir();
            } else {
                succeeded = newFile.createNewFile();
            }
            if (!succeeded) {
                throw new FileNotFoundException("Failed to create document with id " + newFile.getPath());
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create document with id " + newFile.getPath());
        }
        return getDocIdForFile(newFile);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        // Prevent deleting the base directory itself
        try {
            if (file.getCanonicalPath().equals(BASE_DIR_CANONICAL)) {
                throw new FileNotFoundException("Cannot delete the base directory");
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to resolve path for deletion: " + documentId);
        }
        if (!file.delete()) {
            throw new FileNotFoundException("Failed to delete document with id " + documentId);
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        return getMimeType(file);
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File parent = getFileForDocId(rootId);

        final LinkedList<File> pending = new LinkedList<>();
        pending.add(parent);

        final int MAX_SEARCH_RESULTS = 50;
        while (!pending.isEmpty() && result.getCount() < MAX_SEARCH_RESULTS) {
            final File file = pending.removeFirst();
            // Avoid directories outside the $HOME directory linked with symlinks (to avoid e.g. search
            // through the whole SD card).
            boolean isInsideHome;
            try {
                isInsideHome = file.getCanonicalPath().startsWith(BASE_DIR_CANONICAL + "/") ||
                    file.getCanonicalPath().equals(BASE_DIR_CANONICAL);
            } catch (IOException e) {
                // On IO error (e.g. broken symlink, permission denied), skip this entry
                isInsideHome = false;
            }
            if (isInsideHome) {
                if (file.isDirectory()) {
                    File[] children = file.listFiles();
                    if (children != null) {
                        Collections.addAll(pending, children);
                    }
                } else {
                    if (file.getName().toLowerCase().contains(query)) {
                        includeFile(result, null, file);
                    }
                }
            }
        }

        return result;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            File parentFile = getFileForDocId(parentDocumentId);
            File childFile = getFileForDocId(documentId);
            String parentCanonical = parentFile.getCanonicalPath();
            String childCanonical = childFile.getCanonicalPath();
            return childCanonical.equals(parentCanonical) ||
                childCanonical.startsWith(parentCanonical + "/");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the document id given a file. This document id must be consistent across time as other
     * applications may save the ID and use it to reference documents later.
     * <p/>
     * Document IDs are relative paths from BASE_DIR: the root is "root", and children
     * are "root/subdir/file.txt".
     * <p/>
     * The reverse of {@link #getFileForDocId(String)}.
     */
    static String getDocIdForFile(File file) {
        try {
            String fileCanonical = file.getCanonicalPath();
            if (fileCanonical.equals(BASE_DIR_CANONICAL)) {
                return ROOT_DOC_ID;
            }
            String prefix = BASE_DIR_CANONICAL + "/";
            if (fileCanonical.startsWith(prefix)) {
                return ROOT_DOC_ID + "/" + fileCanonical.substring(prefix.length());
            }
        } catch (IOException e) {
            // Fallback to absolute path based relative computation
        }

        // Fallback: use absolute path
        String fileAbsolute = file.getAbsolutePath();
        String baseAbsolute = BASE_DIR.getAbsolutePath();
        if (fileAbsolute.equals(baseAbsolute)) {
            return ROOT_DOC_ID;
        }
        String prefix = baseAbsolute + "/";
        if (fileAbsolute.startsWith(prefix)) {
            return ROOT_DOC_ID + "/" + fileAbsolute.substring(prefix.length());
        }
        // File is outside BASE_DIR — return a best-effort docId
        // (callers should validate via enforceInsideBaseDir)
        return ROOT_DOC_ID + "/" + fileAbsolute;
    }

    /**
     * Get the file given a document id (the reverse of {@link #getDocIdForFile(File)}).
     * <p/>
     * Validates that the resolved file is within BASE_DIR via canonical path resolution,
     * preventing path-traversal and symlink escapes.
     *
     * @throws FileNotFoundException if the docId is invalid, escapes BASE_DIR, or the file doesn't exist
     */
    static File getFileForDocId(String docId) throws FileNotFoundException {
        if (docId == null || docId.isEmpty()) {
            throw new FileNotFoundException("Invalid document id: null or empty");
        }

        File f;
        if (ROOT_DOC_ID.equals(docId)) {
            f = BASE_DIR;
        } else if (docId.startsWith(ROOT_DOC_ID + "/")) {
            String relativePath = docId.substring(ROOT_DOC_ID.length() + 1);
            f = new File(BASE_DIR, relativePath);
        } else if (docId.startsWith("/")) {
            // Backward compatibility: old-style absolute path docIds.
            // Validate strictly before accepting.
            f = new File(docId);
        } else {
            throw new FileNotFoundException("Invalid document id format: " + docId);
        }

        // Validate that the resolved file is within BASE_DIR
        enforceInsideBaseDir(f);

        if (!f.exists()) {
            throw new FileNotFoundException("Document not found: " + docId);
        }

        return f;
    }

    /**
     * Validate that the given file's canonical path is within BASE_DIR.
     * Prevents path-traversal attacks via "../" sequences and symlink escapes
     * that resolve outside the home directory.
     *
     * @throws FileNotFoundException if the file resolves outside BASE_DIR
     */
    static void enforceInsideBaseDir(File file) throws FileNotFoundException {
        String canonical;
        try {
            canonical = file.getCanonicalPath();
        } catch (IOException e) {
            throw new FileNotFoundException("Cannot resolve path: " + file.getPath());
        }
        if (!canonical.equals(BASE_DIR_CANONICAL) && !canonical.startsWith(BASE_DIR_CANONICAL + "/")) {
            throw new FileNotFoundException("Path escapes base directory: " + file.getPath());
        }
    }

    private static String getMimeType(File file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        } else {
            final String name = file.getName();
            final int lastDot = name.lastIndexOf('.');
            if (lastDot >= 0) {
                final String extension = name.substring(lastDot + 1).toLowerCase();
                final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (mime != null) return mime;
            }
            return "application/octet-stream";
        }
    }

    /**
     * Add a representation of a file to a cursor.
     *
     * @param result the cursor to modify
     * @param docId  the document ID representing the desired file (may be null if given file)
     * @param file   the File object representing the desired file (may be null if given docID)
     */
    private void includeFile(MatrixCursor result, String docId, File file)
        throws FileNotFoundException {
        if (docId == null) {
            // Validate that the file is within BASE_DIR before including it
            enforceInsideBaseDir(file);
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
        }

        int flags = 0;
        if (file.isDirectory()) {
            if (file.canWrite()) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else if (file.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }
        if (file.getParentFile() != null && file.getParentFile().canWrite()) flags |= Document.FLAG_SUPPORTS_DELETE;

        final String displayName = file.getName();
        final String mimeType = getMimeType(file);
        if (mimeType.startsWith("image/")) flags |= Document.FLAG_SUPPORTS_THUMBNAIL;

        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_SIZE, file.length());
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_ICON, R.mipmap.ic_launcher);
    }

}
