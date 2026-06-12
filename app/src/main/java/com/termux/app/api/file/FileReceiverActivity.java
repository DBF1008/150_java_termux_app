package com.termux.app.api.file;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Patterns;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.data.IntentUtils;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Activity that receives files, text, and URLs shared from other apps.
 * <p>
 * All incoming content is handled through a single unified pipeline:
 * <ol>
 *   <li><b>Extract</b> – read content into a {@code byte[]} and resolve a default file name.</li>
 *   <li><b>Prompt</b> – show the user a dialog to confirm/edit the file name.</li>
 *   <li><b>Save</b> – write the buffered bytes to {@code ~/downloads/}.</li>
 *   <li><b>Act</b> – optionally launch the editor or open the downloads directory.</li>
 * </ol>
 * URL shares are handled separately via {@code $HOME/bin/termux-url-opener}.
 */
public class FileReceiverActivity extends AppCompatActivity {

    static final String TERMUX_RECEIVEDIR = TermuxConstants.TERMUX_FILES_DIR_PATH + "/home/downloads";
    static final String EDITOR_PROGRAM = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-file-editor";
    static final String URL_OPENER_PROGRAM = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-url-opener";

    private static final String API_TAG = TermuxConstants.TERMUX_APP_NAME + "FileReceiver";
    private static final String LOG_TAG = "FileReceiverActivity";

    // ──────────────────────────────────────────────────────────────────────
    // URL detection (package-private for testing)
    // ──────────────────────────────────────────────────────────────────────

    static boolean isSharedTextAnUrl(String sharedText) {
        if (sharedText == null || sharedText.isEmpty()) return false;

        return Patterns.WEB_URL.matcher(sharedText).matches()
            || Pattern.matches("magnet:\\?xt=urn:btih:.*?", sharedText);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Unified pipeline: onResume → extract → prompt → save → act
    // ──────────────────────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();

        final Intent intent = getIntent();
        Logger.logVerbose(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));

        // Step 1: Extract content.
        ExtractedContent content;
        try {
            content = extractContent(intent);
        } catch (Exception e) {
            showErrorAndFinish("Unable to read shared content:\n\n" + e.getMessage());
            Logger.logStackTraceWithMessage(LOG_TAG, "extractContent() failed", e);
            return;
        }

        if (content == null) {
            // Either no usable content, or already handled (URL).
            if (!isFinishing()) {
                showErrorAndFinish("No content to save.");
            }
            return;
        }

        if (content.data == null) {
            showErrorAndFinish("Unable to read shared content.");
            return;
        }

        // Step 2: Resolve a safe, non-conflicting initial file name.
        File receiveDir = getReceiveDir();
        String initialName = FileReceiverLogic.resolveSafeFileName(content.defaultName, receiveDir);

        // Step 3: Show dialog.
        promptNameAndSave(content.data, initialName, receiveDir);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Content extraction — replaces old handleContentUri / text branch / file branch
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Extract shared content from the intent into a byte array and resolve a default
     * file name using the unified fallback chain in
     * {@link FileReceiverLogic#resolveDefaultName}.
     *
     * @return The extracted content, or {@code null} if the intent was a URL (already handled).
     * @throws IOException If reading the content fails.
     */
    @Nullable
    private ExtractedContent extractContent(Intent intent) throws IOException {
        final String action = intent.getAction();
        final String type = intent.getType();
        final String scheme = intent.getScheme();

        final String sharedTitle = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_TITLE, null);
        final String sharedSubject = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_SUBJECT, null);

        // ── PATH A: ACTION_SEND with stream URI ──────────────────────────
        if (Intent.ACTION_SEND.equals(action) && type != null) {
            final Uri sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (sharedUri != null) {
                String displayName = queryDisplayName(sharedUri);
                String uriBasename = UriUtils.getUriFileBasename(sharedUri, true);
                String rawName = FileReceiverLogic.resolveDefaultName(
                    displayName, sharedTitle, sharedSubject, uriBasename, false);
                byte[] data = readStream(getContentResolver().openInputStream(sharedUri));
                return new ExtractedContent(data, rawName);
            }

            // ── PATH B: ACTION_SEND with text ────────────────────────────
            final String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null) {
                if (isSharedTextAnUrl(sharedText)) {
                    handleUrlAndFinish(sharedText);
                    return null; // Already handled.
                }

                boolean isPlainText = "text/plain".equals(type);
                String rawName = FileReceiverLogic.resolveDefaultName(
                    null, sharedTitle, sharedSubject, null, isPlainText);
                byte[] data = sharedText.getBytes(StandardCharsets.UTF_8);
                return new ExtractedContent(data, rawName);
            }

            // ACTION_SEND with neither stream nor text.
            return null;
        }

        // ── PATH C: ACTION_VIEW (or other) ───────────────────────────────
        Uri dataUri = intent.getData();
        if (dataUri == null) return null;

        if (UriScheme.SCHEME_CONTENT.equals(scheme)) {
            String displayName = queryDisplayName(dataUri);
            String uriBasename = UriUtils.getUriFileBasename(dataUri, true);
            String rawName = FileReceiverLogic.resolveDefaultName(
                displayName, sharedTitle, sharedSubject, uriBasename, false);
            byte[] data = readStream(getContentResolver().openInputStream(dataUri));
            return new ExtractedContent(data, rawName);

        } else if (UriScheme.SCHEME_FILE.equals(scheme)) {
            Logger.logVerbose(LOG_TAG, "uri: \"" + dataUri + "\", path: \""
                + dataUri.getPath() + "\", fragment: \"" + dataUri.getFragment() + "\"");

            String path = UriUtils.getUriFilePathWithFragment(dataUri);
            if (DataUtils.isNullOrEmpty(path)) return null;

            File file = new File(path);
            String rawName = FileReceiverLogic.resolveDefaultName(
                file.getName(), null, null, null, false);
            byte[] data;
            try (FileInputStream fis = new FileInputStream(file)) {
                data = readStream(fis);
            }
            return new ExtractedContent(data, rawName);
        }

        return null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Dialog + save
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Show the file name input dialog. On user confirmation the buffered data is written
     * to disk and the chosen post-save action (edit / open directory) is executed.
     * <p>
     * If saving fails, the error is shown as a Toast and the dialog stays open so the
     * user can adjust the name and retry.
     */
    private void promptNameAndSave(final byte[] data, final String initialName,
                                   final File receiveDir) {
        TextInputDialogUtils.textInput(this, R.string.title_file_received, initialName,
            // ── "Edit" button ────────────────────────────────────────────
            R.string.action_file_received_edit, text -> {
                File outFile = saveToFile(data, text, receiveDir);
                if (outFile == null) return; // Toast already shown.

                final File editorProgramFile = new File(EDITOR_PROGRAM);
                if (!editorProgramFile.isFile()) {
                    showErrorAndFinish("The following file does not exist:\n"
                        + "$HOME/bin/termux-file-editor\n\n"
                        + "Create this file as a script or a symlink - "
                        + "it will be called with the received file as only argument.");
                    return;
                }

                // Do this for the user if necessary:
                //noinspection ResultOfMethodCallIgnored
                editorProgramFile.setExecutable(true);

                final Uri scriptUri = UriUtils.getFileUri(EDITOR_PROGRAM);
                Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, scriptUri);
                executeIntent.setClass(FileReceiverActivity.this, TermuxService.class);
                executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS,
                    new String[]{outFile.getAbsolutePath()});
                startService(executeIntent);
                finish();
            },
            // ── "Open directory" button ──────────────────────────────────
            R.string.action_file_received_open_directory, text -> {
                File outFile = saveToFile(data, text, receiveDir);
                if (outFile == null) return; // Toast already shown.

                Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
                executeIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, TERMUX_RECEIVEDIR);
                executeIntent.setClass(FileReceiverActivity.this, TermuxService.class);
                startService(executeIntent);
                finish();
            },
            // ── "Cancel" button ──────────────────────────────────────────
            android.R.string.cancel, text -> finish(),
            // ── Dismiss listener ─────────────────────────────────────────
            dialog -> finish()
        );
    }

    /**
     * Write the buffered data to a file in {@code receiveDir}.
     * <p>
     * The file name goes through {@link FileReceiverLogic#resolveSafeFileName} to ensure
     * sanitization and conflict resolution before writing. If saving fails, a Toast is
     * shown and {@code null} is returned (the dialog stays open for retry).
     */
    @Nullable
    private File saveToFile(byte[] data, String userFileName, File receiveDir) {
        // Validate user input.
        if (DataUtils.isNullOrEmpty(userFileName) || userFileName.trim().isEmpty()) {
            Toast.makeText(this, R.string.error_file_name_empty, Toast.LENGTH_SHORT).show();
            return null;
        }

        // Ensure receive directory exists.
        if (!receiveDir.isDirectory() && !receiveDir.mkdirs()) {
            Toast.makeText(this,
                getString(R.string.error_file_received_failed,
                    "Cannot create directory: " + receiveDir.getAbsolutePath()),
                Toast.LENGTH_LONG).show();
            return null;
        }

        // Resolve safe name (sanitize + conflict).
        String safeName = FileReceiverLogic.resolveSafeFileName(
            userFileName.trim(), receiveDir);
        File outFile = new File(receiveDir, safeName);

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(data);
            return outFile;
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error saving file", e);
            // Clean up partial file.
            if (outFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
            Toast.makeText(this,
                getString(R.string.error_file_received_failed, e.getMessage()),
                Toast.LENGTH_LONG).show();
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // URL handling (unchanged)
    // ──────────────────────────────────────────────────────────────────────

    void handleUrlAndFinish(final String url) {
        final File urlOpenerProgramFile = new File(URL_OPENER_PROGRAM);
        if (!urlOpenerProgramFile.isFile()) {
            showErrorAndFinish("The following file does not exist:\n$HOME/bin/termux-url-opener\n\n"
                + "Create this file as a script or a symlink - "
                + "it will be called with the shared URL as the first argument.");
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

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Show a fatal error dialog that finishes the activity when dismissed.
     * Used only during the initial extraction phase (before any name dialog is shown).
     */
    private void showErrorAndFinish(String message) {
        MessageDialogUtils.showMessage(this,
            API_TAG, message,
            null, (dialog, which) -> finish(),
            null, null,
            dialog -> finish());
    }

    /** Get or create the receive directory. */
    private static File getReceiveDir() {
        return new File(TERMUX_RECEIVEDIR);
    }

    /**
     * Query {@link OpenableColumns#DISPLAY_NAME} for a content URI.
     *
     * @return The display name, or {@code null} if not available.
     */
    @Nullable
    private String queryDisplayName(@NonNull Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
            new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        }
        return null;
    }

    /**
     * Read an {@link InputStream} fully into a byte array. Closes the stream.
     *
     * @return The content as a byte array, or {@code null} if the stream was null.
     * @throws IOException If reading fails.
     */
    @Nullable
    private static byte[] readStream(@Nullable InputStream in) throws IOException {
        if (in == null) return null;
        try (InputStream stream = in) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(4096, stream.available()));
            byte[] buffer = new byte[8192];
            int readBytes;
            while ((readBytes = stream.read(buffer)) > 0) {
                baos.write(buffer, 0, readBytes);
            }
            return baos.toByteArray();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Component state management (unchanged)
    // ──────────────────────────────────────────────────────────────────────

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
                errmsg = PackageUtils.setComponentState(context, TermuxConstants.TERMUX_PACKAGE_NAME,
                    TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME,
                    state, null, false, false);
                if (errmsg != null)
                    Logger.logError(LOG_TAG, errmsg);

                state = !properties.isFileViewReceiverDisabled();
                Logger.logVerbose(LOG_TAG, "Setting " + TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME + " component state to " + state);
                errmsg = PackageUtils.setComponentState(context, TermuxConstants.TERMUX_PACKAGE_NAME,
                    TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME,
                    state, null, false, false);
                if (errmsg != null)
                    Logger.logError(LOG_TAG, errmsg);
            }
        }.start();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Data carrier
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Simple data carrier holding the buffered content bytes and the resolved default
     * file name from the extraction phase.
     */
    static final class ExtractedContent {
        final byte[] data;
        final String defaultName;

        ExtractedContent(byte[] data, String defaultName) {
            this.data = data;
            this.defaultName = defaultName;
        }
    }
}
