package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.app.TermuxActivitySessionStartup.Action;

import org.junit.Test;

/**
 * Regression tests for {@link TermuxActivitySessionStartup}, the consolidated startup decision used by
 * {@link TermuxActivity#handleTermuxActivityIntent}.
 * <p/>
 * These lock down the behaviour that fixes the duplicate-session / current-session-drift bug seen when the
 * app is relaunched from the launcher "New session" shortcut after a background→foreground switch. The class
 * under test is intentionally free of Android dependencies, so this is a plain JUnit test (no Robolectric),
 * matching {@link TermuxActivityTest}.
 */
public class TermuxActivitySessionStartupTest {

    private static final boolean[] BOTH = {false, true};

    /** Mirrors how {@link TermuxActivity#handleTermuxActivityIntent} calls the decision. */
    private static Action decide(boolean hasSessions, boolean activityIsVisible,
                                 boolean treatNewSessionIntentAsStale, boolean intentRequestsNewSession) {
        return TermuxActivitySessionStartup.determineStartupAction(
            hasSessions, activityIsVisible, treatNewSessionIntentAsStale, intentRequestsNewSession);
    }

    // ---- No sessions: bootstrap when visible, finish when not (independent of the other flags) ----

    @Test
    public void noSessions_visible_bootstraps() {
        for (boolean stale : BOTH)
            for (boolean newSession : BOTH)
                assertEquals(Action.BOOTSTRAP_AND_CREATE_SESSION, decide(false, true, stale, newSession));
    }

    @Test
    public void noSessions_notVisible_finishes() {
        for (boolean stale : BOTH)
            for (boolean newSession : BOTH)
                assertEquals(Action.FINISH_ACTIVITY, decide(false, false, stale, newSession));
    }

    // ---- Existing sessions: create only on a fresh (non-stale) new-session request, otherwise restore ----

    @Test
    public void existingSessions_freshNewSessionRequest_createsNewSession() {
        // e.g. the user taps the "New session" shortcut and it is not a stale re-delivery.
        assertEquals(Action.CREATE_NEW_SESSION, decide(true, true, false, true));
    }

    @Test
    public void existingSessions_staleNewSessionRequest_restores_noDuplicate() {
        // Regression: the launch intent is re-delivered after an activity recreate (dark-mode toggle,
        // styling reload or process death). It must restore, not add another session on every relaunch.
        assertEquals(Action.RESTORE_SESSION, decide(true, true, true, true));
    }

    @Test
    public void existingSessions_noNewSessionRequest_restores() {
        // Normal foreground return (launcher icon / task switch), no new-session intent.
        assertEquals(Action.RESTORE_SESSION, decide(true, true, false, false));
        // Visibility is irrelevant once sessions exist and no new session is requested.
        assertEquals(Action.RESTORE_SESSION, decide(true, false, false, false));
    }

    /** All 16 input combinations locked to the documented contract, to catch accidental branch reordering. */
    @Test
    public void exhaustiveTruthTable() {
        for (boolean hasSessions : BOTH)
            for (boolean visible : BOTH)
                for (boolean stale : BOTH)
                    for (boolean newSession : BOTH) {
                        Action expected;
                        if (!hasSessions) {
                            expected = visible ? Action.BOOTSTRAP_AND_CREATE_SESSION : Action.FINISH_ACTIVITY;
                        } else if (newSession && !stale) {
                            expected = Action.CREATE_NEW_SESSION;
                        } else {
                            expected = Action.RESTORE_SESSION;
                        }
                        assertEquals(
                            "hasSessions=" + hasSessions + " visible=" + visible
                                + " stale=" + stale + " newSession=" + newSession,
                            expected, decide(hasSessions, visible, stale, newSession));
                    }
    }

    // ---- treatActivityAsVisible: a freshly delivered (warm) intent implies visible ----

    @Test
    public void treatActivityAsVisible_initialLaunchIntent_usesRealVisibility() {
        assertTrue(TermuxActivitySessionStartup.treatActivityAsVisible(true, true));
        assertFalse(TermuxActivitySessionStartup.treatActivityAsVisible(false, true));
    }

    @Test
    public void treatActivityAsVisible_warmIntent_alwaysVisible() {
        // onNewIntent() runs before onStart(), so mIsVisible is still false even though the activity is being
        // brought to the foreground; a warm intent must therefore be treated as visible.
        assertTrue(TermuxActivitySessionStartup.treatActivityAsVisible(false, false));
        assertTrue(TermuxActivitySessionStartup.treatActivityAsVisible(true, false));
    }

    // ---- Funnel flag-derivation scenarios (reproduce how handleTermuxActivityIntent combines the flags) ----

    @Test
    public void warmShortcutTap_emptyList_bootstraps_notFinish() {
        // At onNewIntent() time mIsVisible == false. Feeding that raw into the decision would FINISH the
        // activity on an empty session list instead of creating the requested session; treatActivityAsVisible
        // prevents that.
        boolean isInitialLaunchIntent = false; // delivered via onNewIntent()
        boolean activityIsVisible = TermuxActivitySessionStartup.treatActivityAsVisible(false, isInitialLaunchIntent);
        boolean treatNewSessionIntentAsStale = isInitialLaunchIntent && /* mIsActivityRecreated */ true;

        assertEquals(Action.BOOTSTRAP_AND_CREATE_SESSION,
            decide(/*hasSessions*/ false, activityIsVisible, treatNewSessionIntentAsStale, /*newSession*/ true));
    }

    @Test
    public void warmShortcutTap_existingSessions_createsNewSession_evenWhenRecreatedFlagStuck() {
        // A config-change recreate can leave mIsActivityRecreated stuck true. A genuine warm shortcut tap
        // (onNewIntent, isInitialLaunchIntent == false) must still create a session.
        boolean isInitialLaunchIntent = false;
        boolean mIsActivityRecreated = true;
        boolean treatNewSessionIntentAsStale = isInitialLaunchIntent && mIsActivityRecreated; // false
        boolean activityIsVisible = TermuxActivitySessionStartup.treatActivityAsVisible(false, isInitialLaunchIntent);

        assertEquals(Action.CREATE_NEW_SESSION,
            decide(/*hasSessions*/ true, activityIsVisible, treatNewSessionIntentAsStale, /*newSession*/ true));
    }

    @Test
    public void coldRelaunchAfterKill_existingSessions_restores_noDuplicate() {
        // Cold path: process/activity killed, relaunched via the sticky ACTION_RUN intent. savedInstanceState
        // present -> mIsActivityRecreated true, handled as the initial launch intent -> treated as stale.
        boolean isInitialLaunchIntent = true;
        boolean mIsActivityRecreated = true;
        boolean treatNewSessionIntentAsStale = isInitialLaunchIntent && mIsActivityRecreated; // true
        boolean activityIsVisible = TermuxActivitySessionStartup.treatActivityAsVisible(true, isInitialLaunchIntent);

        assertEquals(Action.RESTORE_SESSION,
            decide(/*hasSessions*/ true, activityIsVisible, treatNewSessionIntentAsStale, /*newSession*/ true));
    }

}
