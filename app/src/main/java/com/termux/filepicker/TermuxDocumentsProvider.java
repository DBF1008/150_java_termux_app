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
 * All access is confined to {@link #BASE_DIR}: every document id supplied by a caller is
 * resolved through {@link TermuxDocumentPaths#resolveFileForDocId} (via {@link #getFileForDocId}),
 * which rejects ids that do not exist or whose canonical path escapes the base directory. Note
 * that {@code renameDocument}/{@code copyDocument}/{@code moveDocument} are not overridden and
 * their {@code FLAG_SUPPORTS_*} bits are not advertised, so the framework never calls them; if
 * such support is ever added it must resolve its ids through the same chokepoint.
 */
public class TermuxDocumentsProvider extends DocumentsProvider {

    private static final String ALL_MIME_TYPES = "*/*";

    private static final File BASE_DIR = TermuxConstants.TERMUX_HOME_DIR;


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
        row.add(Root.COLUMN_ROOT_ID, getDocIdForFile(BASE_DIR));
        row.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(BASE_DIR));
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
        final File[] children = parent.listFiles();
        if (children != null) {
            for (File file : children) {
                // Only expose entries whose canonical path stays within $HOME, so a symlink
                // pointing outside is hidden here exactly as it is in search.
                if (TermuxDocumentPaths.isInScope(file, BASE_DIR)) {
                    includeFile(result, null, file);
                }
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
        return true;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        final File parent = getFileForDocId(parentDocumentId);
        final String name = TermuxDocumentPaths.sanitizeDisplayName(displayName);
        File newFile = new File(parent, name);
        // Defence in depth: even with a sanitized name, never create outside $HOME.
        if (!TermuxDocumentPaths.isInScope(newFile, BASE_DIR)) {
            throw new FileNotFoundException("Cannot create document outside allowed storage");
        }
        int noConflictId = 2;
        while (newFile.exists()) {
            newFile = new File(parent, name + " (" + noConflictId++ + ")");
        }
        try {
            boolean succeeded;
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                succeeded = newFile.mkdir();
            } else {
                succeeded = newFile.createNewFile();
            }
            if (!succeeded) {
                throw new FileNotFoundException("Failed to create document with name " + newFile.getName());
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create document with name " + newFile.getName());
        }
        return getDocIdForFile(newFile);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        // Recursively remove directories (plain File#delete fails on a non-empty dir) without
        // following symlinks, so nothing outside $HOME is ever touched.
        if (!TermuxDocumentPaths.deleteRecursively(file)) {
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
        final String matchQuery = query.toLowerCase();

        // Search file names for the query without ranking, stopping once enough matches are
        // found. Other implementations might rank results or match on data other than the name.
        final LinkedList<File> pending = new LinkedList<>();
        pending.add(parent);

        final int MAX_SEARCH_RESULTS = 50;
        while (!pending.isEmpty() && result.getCount() < MAX_SEARCH_RESULTS) {
            final File file = pending.removeFirst();
            // Skip anything whose canonical path escapes $HOME (e.g. a symlink to the SD card).
            // Fails closed: entries that cannot be resolved are skipped rather than searched.
            if (!TermuxDocumentPaths.isInScope(file, BASE_DIR)) {
                continue;
            }
            if (file.isDirectory()) {
                final File[] children = file.listFiles();
                if (children != null) {
                    Collections.addAll(pending, children);
                }
            } else if (file.getName().toLowerCase().contains(matchQuery)) {
                includeFile(result, null, file);
            }
        }

        return result;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return TermuxDocumentPaths.isChildDocument(parentDocumentId, documentId);
    }

    /**
     * Get the document id given a file. This document id must be consistent across time as other
     * applications may save the ID and use it to reference documents later.
     * <p/>
     * The reverse of {@link #getFileForDocId(String)}.
     */
    private static String getDocIdForFile(File file) {
        return TermuxDocumentPaths.getDocIdForFile(file);
    }

    /**
     * Get the file for a document id, confirming it exists and stays within {@link #BASE_DIR}
     * (the reverse of {@link #getDocIdForFile(File)}). This is the single point through which
     * every caller-supplied id is validated before use.
     */
    private static File getFileForDocId(String docId) throws FileNotFoundException {
        return TermuxDocumentPaths.resolveFileForDocId(docId, BASE_DIR);
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
        final File parentFile = file.getParentFile();
        if (parentFile != null && parentFile.canWrite()) flags |= Document.FLAG_SUPPORTS_DELETE;

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
