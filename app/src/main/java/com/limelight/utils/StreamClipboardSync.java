package com.limelight.utils;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Clipboard sync between the device and the host during a stream: sends the local clipboard when
 * the stream gains focus and fetches the host clipboard when it loses focus (smart sync), plus the
 * manual send/get actions of the stream menu. Network calls run on their own worker threads.
 */
public final class StreamClipboardSync {

    /** Marks clips that Delos itself put on (or already read from) the clipboard. */
    static final String CLIPBOARD_IDENTIFIER = "DelosStreaming";

    /** What the sync needs from the streaming activity. */
    public interface Host {
        /** The host's HTTP client, or null when it is not available. */
        @Nullable NvHTTP httpConn();

        boolean isConnected();

        /** Shows a short toast; called from worker threads, so it must hop to the UI thread. */
        void showToast(String text, ToastKind kind);
    }

    private final Context context;
    private final ClipboardManager clipboardManager;
    private final PreferenceConfiguration prefConfig;
    private final Host host;
    private boolean clipboardSyncRunning = false;

    public StreamClipboardSync(Context context, PreferenceConfiguration prefConfig, Host host) {
        this.context = context;
        this.clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        this.prefConfig = prefConfig;
        this.host = host;
    }

    public boolean handleFocusChange(boolean hasFocus) {
        if (host.isConnected() && prefConfig.smartClipboardSync) {
            if (hasFocus) {
                return sendClipboard(false);
            } else {
                return getClipboard(0);
            }
        }

        return false;
    }

    // Method to get clipboard content
    @Nullable
    String getClipboardContent(boolean force) {
        // Check if there is any clipboard data
        if (clipboardManager.hasPrimaryClip()) {
            ClipDescription clipDescription = clipboardManager.getPrimaryClipDescription();
            if (!force && clipDescription != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    PersistableBundle extras = clipDescription.getExtras();
                    if (extras != null && extras.getBoolean(CLIPBOARD_IDENTIFIER)) {
                        // We're getting the clipboard data we just set/read a while ago
                        return null;
                    }
                } else {
                    CharSequence clipLabel = clipDescription.getLabel();
                    if (clipLabel != null && clipLabel.equals(CLIPBOARD_IDENTIFIER)) {
                        // We're getting the clipboard data we set a while ago
                        return null;
                    }
                }
            }

            ClipData clipData = clipboardManager.getPrimaryClip();

            if (clipData != null && clipData.getItemCount() > 0) {
                // Get the first item from the clipboard data
                ClipData.Item item = clipData.getItemAt(0);

                // Mark the clip as visited
                if (clipDescription != null) {
                    ClipData clonedClip = cloneClipData(clipDescription, item);
                    clipboardManager.setPrimaryClip(clonedClip);
                }

                // Get the text data from the clipboard item
                CharSequence clipText = item.getText();
                if (clipText == null) {
                    return  null;
                }
                return clipText.toString();
            }
        }

        return null;
    }

    private static @NonNull ClipData cloneClipData(ClipDescription clipDescription, ClipData.Item item) {
        ClipDescription clonedDescription = new ClipDescription(clipDescription);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PersistableBundle extras = clipDescription.getExtras();
            if (extras == null) {
                extras = new PersistableBundle();
            }
            extras.putBoolean(CLIPBOARD_IDENTIFIER, true);
            clonedDescription.setExtras(extras);
        }

        return new ClipData(clonedDescription, item);
    }

    public boolean sendClipboard(boolean force) {
        NvHTTP httpConn = host.httpConn();
        if (httpConn == null) {
            LimeLog.warning("httpConn not ready, cannot send clipboard!");
            return false;
        }

        String clipboardText = getClipboardContent(force);
        if (clipboardText != null) {
            new Thread() {
                public void run() {
                    try {
                        if (!httpConn.sendClipboard(clipboardText)) {
                            if (prefConfig.smartClipboardSyncToast) {
                                host.showToast(context.getString(R.string.clipboard_sync_unsupported), ToastKind.WARNING);
                            }
                        } else {
                            if (prefConfig.smartClipboardSyncToast) {
                                host.showToast(context.getString(R.string.send_clipboard_success), ToastKind.SUCCESS);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        if (prefConfig.smartClipboardSyncToast) {
                            host.showToast(context.getString(R.string.send_clipboard_failed) + e.getMessage(), ToastKind.ERROR);
                        }
                    }
                }
            }.start();

            return true;
        }

        return false;
    }

    public boolean getClipboard(int delay) {
        NvHTTP httpConn = host.httpConn();
        if (httpConn == null) {
            LimeLog.warning("httpConn not ready, cannot get clipboard!");
            return false;
        }

        new Thread() {
            public void run() {
                if (clipboardSyncRunning) {
                    return;
                }

                clipboardSyncRunning = true;
                try {
                    if (delay > 0) {
                        sleep(delay);
                    }
                    String clipboardContent = httpConn.getClipboard();
                    ClipData clipData = ClipData.newPlainText(CLIPBOARD_IDENTIFIER, clipboardContent);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        ClipDescription clipDescription = clipData.getDescription();
                        PersistableBundle newExtras = new PersistableBundle();
                        newExtras.putBoolean(CLIPBOARD_IDENTIFIER, true);
                        if (prefConfig.hideClipboardContent) {
                            // We don't know if the message is sensitive or not, to be safe mark them all as sensitive.
                            newExtras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                        }
                        clipDescription.setExtras(newExtras);
                    }

                    clipboardManager.setPrimaryClip(clipData);
                    if (prefConfig.smartClipboardSyncToast) {
                        host.showToast(context.getString(R.string.get_clipboard_success), ToastKind.SUCCESS);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    if (prefConfig.smartClipboardSyncToast) {
                        host.showToast(context.getString(R.string.get_clipboard_failed) + e.getMessage(), ToastKind.ERROR);
                    }
                }
                clipboardSyncRunning = false;
            }
        }.start();

        return true;
    }
}
