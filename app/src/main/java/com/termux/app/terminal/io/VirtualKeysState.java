package com.termux.app.terminal.io;

import android.view.KeyEvent;

/**
 * Single owner of the "virtual" modifier-key state that the dedicated volume buttons emulate for
 * the soft keyboard and other non-full keyboards in Termux:
 *
 * <ul>
 *     <li>{@link KeyEvent#KEYCODE_VOLUME_DOWN} acts as a virtual <b>Ctrl</b> key.</li>
 *     <li>{@link KeyEvent#KEYCODE_VOLUME_UP} acts as a virtual <b>Fn</b> key.</li>
 * </ul>
 *
 * The state is toggled on key down/up events. Because these are momentary "while held" modifiers,
 * the matching {@code ACTION_UP} must arrive to release them. If the activity window loses focus
 * while a volume key is held (notification shade, dialog, app switch, recents, etc.), Android does
 * not deliver that {@code ACTION_UP} and the modifier would otherwise stay stuck when the user
 * returns. Callers must therefore invoke {@link #reset()} on foreground/background transitions
 * (see {@code TermuxActivity.onWindowFocusChanged}) so the state can never leak across them.
 *
 * Keeping this as a small framework-light holder gives a single place to own and reset the state,
 * so it can no longer drift out of sync with the extra-keys toolbar, and makes it unit testable.
 */
public class VirtualKeysState {

    private boolean mVirtualControlKeyDown;
    private boolean mVirtualFnKeyDown;

    /**
     * Update the virtual modifier state for a dedicated volume button.
     *
     * @param keyCode The key code of the event.
     * @param down    {@code true} for {@code ACTION_DOWN}, {@code false} for {@code ACTION_UP}.
     * @return {@code true} if {@code keyCode} is a recognised virtual modifier key (and the state
     *         was updated), so that the caller can consume the event; {@code false} otherwise.
     */
    public boolean handleVirtualModifierKey(int keyCode, boolean down) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            mVirtualControlKeyDown = down;
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mVirtualFnKeyDown = down;
            return true;
        }
        return false;
    }

    /** Whether the virtual Ctrl key (volume down) is currently held. */
    public boolean isVirtualControlKeyDown() {
        return mVirtualControlKeyDown;
    }

    /** Whether the virtual Fn key (volume up) is currently held. */
    public boolean isVirtualFnKeyDown() {
        return mVirtualFnKeyDown;
    }

    /**
     * Force the virtual Fn key state. Used to release Fn immediately mid-session after it has
     * triggered an action that hands input back to the terminal view, e.g. toggling the terminal
     * toolbar with {@code Fn+k}. See termux/termux-app#1420.
     */
    public void setVirtualFnKeyDown(boolean down) {
        mVirtualFnKeyDown = down;
    }

    /**
     * Clear all virtual modifier state. Must be called on activity foreground/background
     * transitions so a modifier held during a focus change cannot remain stuck on return.
     */
    public void reset() {
        mVirtualControlKeyDown = false;
        mVirtualFnKeyDown = false;
    }

}
