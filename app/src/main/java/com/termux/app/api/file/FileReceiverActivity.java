package com.termux.app.api.file;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Patterns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.net.uri.UriScheme;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class FileReceiverActivity extends AppCompatActivity {

    static final String TERMUX_RECEIVEDIR = TermuxConstants.TERMUX_FILES_DIR_PATH + "/home/downloads";
    static final String EDITOR_PROGRAM = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-file-editor";
    static final String URL_OPENER_PROGRAM = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-url-opener";

    /** Default name used when no usable file name can be derived from the incoming intent. */
    static final String DEFAULT_RECEIVED_FILE_NAME = "shared_file";

    /** Default name used for shared plain text when no subject/title is provided. */
    static final String DEFAULT_RECEIVED_TEXT_FILE_NAME = "shared_text.txt";

    /** Maximum number of numeric suffixes tried when resolving a name conflict. */
    private static final int MAX_FILE_NAME_CONFLICT_SUFFIX = 1000;

    /**
     * If the activity should be finished when the name input dialog is dismissed. This is disabled
     * before showing an error dialog, since the act of showing the error dialog will cause the
     * name input dialog to be implicitly dismissed, and we do not want to finish the activity directly
     * when showing the error dialog.
     */
    boolean mFinishOnDismissNameDialog = true;

    /**
     * The stream of the content currently being received. It is held in a field so that it can be
     * closed on any exit path (cancel, back, error, or after a successful save) instead of being
     * leaked. It is set to {@code null} once it has been consumed (and thereby closed) by
     * {@link #saveStreamWithName(InputStream, String)}.
     */
    @Nullable
    private InputStream mPendingInputStream;

    private static final String API_TAG = TermuxConstants.TERMUX_APP_NAME + "FileReceiver";

    private static final String LOG_TAG = "FileReceiverActivity";

    static boolean isSharedTextAnUrl(String sharedText) {
        if (sharedText == null || sharedText.isEmpty()) return false;

        return Patterns.WEB_URL.matcher(sharedText).matches()
            || Pattern.matches("magnet:\\?xt=urn:btih:.*?", sharedText);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Dispatch the launching intent exactly once. Previously this ran in onResume(), which
        // re-processed the intent on every resume - stacking duplicate dialogs and re-reading an
        // already-consumed stream. This matches the onCreate dispatch used by the sibling receiver
        // activities (e.g. TextIOActivity, ReportActivity).
        handleIntent();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Guarantee the received stream is closed on every exit path (cancel, back, error or after
        // a successful save did not run). saveStreamWithName() nulls this out once it owns the stream.
        if (mPendingInputStream != null) {
            try {
                mPendingInputStream.close();
            } catch (IOException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to close pending input stream", e);
            }
            mPendingInputStream = null;
        }
    }

    private void handleIntent() {
        final Intent intent = getIntent();
        final String action = intent.getAction();
        final String type = intent.getType();
        final String scheme = intent.getScheme();

        Logger.logVerbose(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));

        final String sharedTitle = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_TITLE, null);

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            final String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            final Uri sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);

            if (sharedUri != null) {
                handleContentUri(sharedUri, sharedTitle);
            } else if (sharedText != null) {
                if (isSharedTextAnUrl(sharedText)) {
                    handleUrlAndFinish(sharedText);
                } else {
                    String subject = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_SUBJECT, null);
                    if (subject == null) subject = sharedTitle;
                    String fileName = !DataUtils.isNullOrEmpty(subject)
                        ? subject + ".txt" : DEFAULT_RECEIVED_TEXT_FILE_NAME;
                    promptNameAndSave(new ByteArrayInputStream(sharedText.getBytes(StandardCharsets.UTF_8)), fileName);
                }
            } else {
                showErrorDialogAndQuit("Send action without content - nothing to save.");
            }
        } else {
            Uri dataUri = intent.getData();

            if (dataUri == null) {
                showErrorDialogAndQuit("Data uri not passed.");
                return;
            }

            if (UriScheme.SCHEME_CONTENT.equals(scheme)) {
                handleContentUri(dataUri, sharedTitle);
            } else if (UriScheme.SCHEME_FILE.equals(scheme)) {
                Logger.logVerbose(LOG_TAG, "uri: \"" + dataUri + "\", path: \"" + dataUri.getPath() + "\", fragment: \"" + dataUri.getFragment() + "\"");

                // Get full path including fragment (anything after last "#")
                String path = UriUtils.getUriFilePathWithFragment(dataUri);
                if (DataUtils.isNullOrEmpty(path)) {
                    showErrorDialogAndQuit("File path from data uri is null, empty or invalid.");
                    return;
                }

                File file = new File(path);
                try {
                    FileInputStream in = new FileInputStream(file);
                    promptNameAndSave(in, file.getName());
                } catch (FileNotFoundException e) {
                    showErrorDialogAndQuit("Cannot open file: " + e.getMessage() + ".");
                }
            } else {
                showErrorDialogAndQuit("Unable to receive any file or URL.");
            }
        }
    }

    void showErrorDialogAndQuit(String message) {
        mFinishOnDismissNameDialog = false;
        MessageDialogUtils.showMessage(this,
            API_TAG, message,
            null, (dialog, which) -> finish(),
            null, null,
            dialog -> finish());
    }

    void handleContentUri(@NonNull final Uri uri, String subjectFromIntent) {
        try {
            Logger.logVerbose(LOG_TAG, "uri: \"" + uri + "\", path: \"" + uri.getPath() + "\", fragment: \"" + uri.getFragment() + "\"");

            String attachmentFileName = null;

            String[] projection = new String[]{OpenableColumns.DISPLAY_NAME};
            try (Cursor c = getContentResolver().query(uri, projection, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    final int fileNameColumnId = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (fileNameColumnId >= 0) attachmentFileName = c.getString(fileNameColumnId);
                }
            }

            // Use the same fallback order for every content source so the name suggested to the user
            // is consistent: display name, then the intent subject/title, then the uri basename.
            attachmentFileName = firstNonBlank(attachmentFileName, subjectFromIntent,
                UriUtils.getUriFileBasename(uri, true));

            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                showErrorDialogAndQuit("Unable to open the shared content for reading.");
                return;
            }

            promptNameAndSave(in, attachmentFileName);
        } catch (Exception e) {
            showErrorDialogAndQuit("Unable to handle shared content:\n\n" + e.getMessage());
            Logger.logStackTraceWithMessage(LOG_TAG, "handleContentUri(uri=" + uri + ") failed", e);
        }
    }

    void promptNameAndSave(final InputStream in, final String suggestedFileName) {
        if (in == null) {
            showErrorDialogAndQuit("No content stream to save.");
            return;
        }

        // Track the stream so onDestroy() can close it if the user cancels without saving.
        mPendingInputStream = in;

        // Pre-fill the dialog with a consistent, sanitized, never-empty name regardless of the source.
        String initialName = sanitizeReceivedFileName(suggestedFileName);
        if (initialName == null) initialName = DEFAULT_RECEIVED_FILE_NAME;

        TextInputDialogUtils.textInput(this, R.string.title_file_received, initialName,
            R.string.action_file_received_edit, text -> {
                File outFile = saveStreamWithName(in, text);
                if (outFile == null) return;

                final File editorProgramFile = new File(EDITOR_PROGRAM);
                if (!editorProgramFile.isFile()) {
                    showErrorDialogAndQuit("The following file does not exist:\n$HOME/bin/termux-file-editor\n\n"
                        + "Create this file as a script or a symlink - it will be called with the received file as only argument.");
                    return;
                }

                // Do this for the user if necessary:
                //noinspection ResultOfMethodCallIgnored
                editorProgramFile.setExecutable(true);

                final Uri scriptUri = UriUtils.getFileUri(EDITOR_PROGRAM);

                Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, scriptUri);
                executeIntent.setClass(FileReceiverActivity.this, TermuxService.class);
                executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{outFile.getAbsolutePath()});
                startService(executeIntent);
                finish();
            },
            R.string.action_file_received_open_directory, text -> {
                if (saveStreamWithName(in, text) == null) return;

                Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
                executeIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, TERMUX_RECEIVEDIR);
                executeIntent.setClass(FileReceiverActivity.this, TermuxService.class);
                startService(executeIntent);
                finish();
            },
            android.R.string.cancel, text -> finish(), dialog -> {
                if (mFinishOnDismissNameDialog) finish();
            });
    }

    @Nullable
    public File saveStreamWithName(InputStream in, String userProvidedName) {
        File receiveDir = new File(TERMUX_RECEIVEDIR);

        if (in == null) {
            showErrorDialogAndQuit("No content stream to save.");
            return null;
        }

        String fileName = sanitizeReceivedFileName(userProvidedName);
        if (fileName == null) {
            showErrorDialogAndQuit("File name cannot be empty or invalid.");
            return null;
        }

        if (!receiveDir.isDirectory() && !receiveDir.mkdirs()) {
            showErrorDialogAndQuit("Cannot create directory: " + receiveDir.getAbsolutePath());
            return null;
        }

        // Never silently overwrite an existing file: resolve a non-conflicting name instead.
        final File outFile = getNonConflictingFile(receiveDir, fileName);
        if (outFile == null) {
            showErrorDialogAndQuit("Could not find an available file name for: " + fileName);
            return null;
        }

        // outFile is guaranteed to stay inside receiveDir: sanitizeReceivedFileName() reduced the
        // name to a single path component (no separators, and "." / ".." are rejected), so it
        // cannot traverse out of the directory.
        if (!fileName.equals(outFile.getName())) {
            Logger.logInfo(LOG_TAG, "A file named \"" + fileName + "\" already exists, saving as \"" + outFile.getName() + "\" instead.");
        }

        // From here the stream is owned by the try-with-resources block below (which always closes
        // it, even if opening the output file fails), so clear the field to avoid a double-close
        // from onDestroy().
        mPendingInputStream = null;

        try (InputStream input = in; FileOutputStream f = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[4096];
            int readBytes;
            while ((readBytes = input.read(buffer)) > 0) {
                f.write(buffer, 0, readBytes);
            }
            return outFile;
        } catch (IOException e) {
            showErrorDialogAndQuit("Error saving file:\n\n" + e);
            Logger.logStackTraceWithMessage(LOG_TAG, "Error saving file", e);
            return null;
        }
    }

    /**
     * Returns the first of {@code candidates} that is non-null and not blank (after trimming), or
     * {@code null} if there is none.
     */
    @Nullable
    static String firstNonBlank(String... candidates) {
        if (candidates == null) return null;
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) return candidate;
        }
        return null;
    }

    /**
     * Sanitizes a name supplied by the intent or typed by the user into a safe bare file name.
     * Any directory components (and therefore any {@code "../"} traversal) are dropped and the
     * illegal file name characters handled by {@link FileUtils#sanitizeFileName} are replaced.
     *
     * @return A safe, non-empty file name, or {@code null} if {@code name} cannot yield one.
     */
    @Nullable
    static String sanitizeReceivedFileName(String name) {
        if (name == null) return null;

        // Reduce to a bare file name, dropping any directory components.
        String baseName = new File(name.trim()).getName().trim();
        if (baseName.isEmpty() || ".".equals(baseName) || "..".equals(baseName)) return null;

        String sanitized = FileUtils.sanitizeFileName(baseName, false, false);
        if (sanitized == null) return null;
        sanitized = sanitized.trim();
        if (sanitized.isEmpty() || ".".equals(sanitized) || "..".equals(sanitized)) return null;

        return sanitized;
    }

    /**
     * Resolves a file in {@code dir} that does not clash with an existing one. If {@code dir/fileName}
     * is free it is returned as-is; otherwise a numeric suffix is inserted before the extension
     * ({@code name-1.ext}, {@code name-2.ext}, ...). A leading dot is treated as part of the name
     * (so {@code .bashrc} becomes {@code .bashrc-1}, not {@code -1.bashrc}).
     *
     * @return The non-conflicting file, or {@code null} if none was found within the suffix cap.
     */
    @Nullable
    static File getNonConflictingFile(@NonNull File dir, @NonNull String fileName) {
        File file = new File(dir, fileName);
        if (!file.exists()) return file;

        String base;
        String extension;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            // A dot at index 0 is a leading dot (dotfile), not an extension separator.
            base = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex); // includes the leading "."
        } else {
            base = fileName;
            extension = "";
        }

        for (int i = 1; i <= MAX_FILE_NAME_CONFLICT_SUFFIX; i++) {
            File candidate = new File(dir, base + "-" + i + extension);
            if (!candidate.exists()) return candidate;
        }

        return null;
    }

    void handleUrlAndFinish(final String url) {
        final File urlOpenerProgramFile = new File(URL_OPENER_PROGRAM);
        if (!urlOpenerProgramFile.isFile()) {
            showErrorDialogAndQuit("The following file does not exist:\n$HOME/bin/termux-url-opener\n\n"
                + "Create this file as a script or a symlink - it will be called with the shared URL as the first argument.");
            return;
        }

        // Do this for the user if necessary:
        //noinspection ResultOfMethodCallIgnored
        urlOpenerProgramFile.setExecutable(true);

        final Uri urlOpenerProgramUri = UriUtils.getFileUri(URL_OPENER_PROGRAM);

        Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, urlOpenerProgramUri);
        executeIntent.setClass(FileReceiverActivity.this, TermuxService.class);
        executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{url});
        startService(executeIntent);
        finish();
    }

    /**
     * Update {@link TERMUX_APP#FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME} component state depending on
     * {@link TermuxPropertyConstants#KEY_DISABLE_FILE_SHARE_RECEIVER} value and
     * {@link TERMUX_APP#FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME} component state depending on
     * {@link TermuxPropertyConstants#KEY_DISABLE_FILE_VIEW_RECEIVER} value.
     */
    public static void updateFileReceiverActivityComponentsState(@NonNull Context context) {
        new Thread() {
            @Override
            public void run() {
                TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();

                String errmsg;
                boolean state;

                state = !properties.isFileShareReceiverDisabled();
                Logger.logVerbose(LOG_TAG, "Setting " + TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME + " component state to " + state);
                errmsg = PackageUtils.setComponentState(context,TermuxConstants.TERMUX_PACKAGE_NAME,
                    TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME,
                    state, null, false, false);
                if (errmsg != null)
                    Logger.logError(LOG_TAG, errmsg);

                state = !properties.isFileViewReceiverDisabled();
                Logger.logVerbose(LOG_TAG, "Setting " + TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME + " component state to " + state);
                errmsg = PackageUtils.setComponentState(context,TermuxConstants.TERMUX_PACKAGE_NAME,
                    TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME,
                    state, null, false, false);
                if (errmsg != null)
                    Logger.logError(LOG_TAG, errmsg);

            }
        }.start();
    }

}
