package com.termux.app;

/**
 * Pure, Android-free decision logic for what {@link TermuxActivity} should do with terminal sessions
 * when it (re)connects to the {@link TermuxService} or receives a new intent.
 * <p/>
 * This is the single source of truth for the "create a new session vs restore the current one" decision
 * that used to live inline (and inconsistently) in {@link TermuxActivity#onServiceConnected} and racing
 * with {@code TermuxTerminalSessionActivityClient.onStart()}. Keeping it here, free of any Android
 * dependencies, lets the startup behaviour be unit-tested deterministically without a device or Robolectric.
 * <p/>
 * Background: {@link TermuxActivity} is {@code launchMode="singleTask"} and the launcher "New session"
 * shortcut delivers an {@code Intent.ACTION_RUN}. If the same launch intent is re-delivered after the
 * activity is recreated (e.g. system dark-mode toggle, styling reload or process death), naively acting on
 * it again would re-add a session every time. The {@code treatNewSessionIntentAsStale} flag captures that
 * "this new-session intent may just be a stale re-delivery, do not act on it" condition.
 */
public final class TermuxActivitySessionStartup {

    private TermuxActivitySessionStartup() {}

    /** The action {@link TermuxActivity} should take for its terminal sessions on startup. */
    public enum Action {
        /** No sessions exist yet and the activity is in the foreground: install the bootstrap (if needed) and add the first session. */
        BOOTSTRAP_AND_CREATE_SESSION,
        /** No sessions exist and the activity is not in the foreground: there is nothing to show, so finish it. */
        FINISH_ACTIVITY,
        /** The user explicitly asked for a new session (e.g. the "New session" shortcut) and it is not a stale re-delivery: add one. */
        CREATE_NEW_SESSION,
        /** Restore the previously current session (or the last running one if that is gone). */
        RESTORE_SESSION
    }

    /**
     * Decide what to do with terminal sessions on startup.
     *
     * @param hasSessions                whether the service currently has at least one session.
     * @param activityIsVisible          whether the activity is (or is about to become) visible/foreground.
     * @param treatNewSessionIntentAsStale whether a new-session intent should be ignored because it may be a
     *                                     stale re-delivery of the original launch intent after a recreate.
     * @param intentRequestsNewSession   whether the intent being handled is a request to create a new session.
     */
    public static Action determineStartupAction(boolean hasSessions,
                                                boolean activityIsVisible,
                                                boolean treatNewSessionIntentAsStale,
                                                boolean intentRequestsNewSession) {
        if (!hasSessions) {
            return activityIsVisible ? Action.BOOTSTRAP_AND_CREATE_SESSION : Action.FINISH_ACTIVITY;
        }

        if (intentRequestsNewSession && !treatNewSessionIntentAsStale) {
            return Action.CREATE_NEW_SESSION;
        }

        return Action.RESTORE_SESSION;
    }

    /**
     * Whether the activity should be treated as visible when handling an intent.
     * <p/>
     * {@code Activity.onNewIntent()} is only delivered when the system is bringing the activity to the
     * foreground, but it runs before {@code onStart()}, so the activity's own {@code mIsVisible} flag is
     * still {@code false} at that point. Treating a freshly delivered (non-initial-launch) intent as visible
     * avoids wrongly finishing the activity when a "New session" shortcut is tapped while the session list
     * happens to be empty.
     *
     * @param isVisible             the activity's current {@code mIsVisible} value.
     * @param isInitialLaunchIntent whether this is the initial launch intent handled at service-connect time
     *                              (as opposed to a fresh intent delivered via {@code onNewIntent()}).
     */
    public static boolean treatActivityAsVisible(boolean isVisible, boolean isInitialLaunchIntent) {
        return isVisible || !isInitialLaunchIntent;
    }

}
