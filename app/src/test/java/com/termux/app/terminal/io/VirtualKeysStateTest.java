package com.termux.app.terminal.io;

import android.view.KeyEvent;

import org.junit.Assert;
import org.junit.Test;

/**
 * Regression tests for {@link VirtualKeysState}, the single owner of the virtual modifier-key state
 * emulated by the dedicated volume buttons (volume-down = Ctrl, volume-up = Fn).
 *
 * The headline bug these guard against: a modifier set on a volume-key {@code ACTION_DOWN} would stay
 * "stuck" if the matching {@code ACTION_UP} was never delivered (e.g. the window lost focus while the
 * key was held). {@link VirtualKeysState#reset()} must always clear that state.
 *
 * Uses only {@code KeyEvent} keycode constants (compile-time inlined), so it runs as a plain JVM
 * unit test without Robolectric, matching {@code TermuxActivityTest}.
 */
public class VirtualKeysStateTest {

    @Test
    public void volumeDownActsAsVirtualControlKey() {
        VirtualKeysState state = new VirtualKeysState();

        Assert.assertTrue("volume-down should be recognised",
            state.handleVirtualModifierKey(KeyEvent.KEYCODE_VOLUME_DOWN, true));
        Assert.assertTrue("Ctrl should be down while volume-down held", state.isVirtualControlKeyDown());
        Assert.assertFalse("Fn should be unaffected", state.isVirtualFnKeyDown());

        Assert.assertTrue(state.handleVirtualModifierKey(KeyEvent.KEYCODE_VOLUME_DOWN, false));
        Assert.assertFalse("Ctrl should be released on key up", state.isVirtualControlKeyDown());
    }

    @Test
    public void volumeUpActsAsVirtualFnKey() {
        VirtualKeysState state = new VirtualKeysState();

        Assert.assertTrue("volume-up should be recognised",
            state.handleVirtualModifierKey(KeyEvent.KEYCODE_VOLUME_UP, true));
        Assert.assertTrue("Fn should be down while volume-up held", state.isVirtualFnKeyDown());
        Assert.assertFalse("Ctrl should be unaffected", state.isVirtualControlKeyDown());

        Assert.assertTrue(state.handleVirtualModifierKey(KeyEvent.KEYCODE_VOLUME_UP, false));
        Assert.assertFalse("Fn should be released on key up", state.isVirtualFnKeyDown());
    }

    @Test
    public void unrecognisedKeyCodeIsIgnoredAndDoesNotChangeState() {
        VirtualKeysState state = new VirtualKeysState();

        Assert.assertFalse("a normal key must not be consumed as a virtual modifier",
            state.handleVirtualModifierKey(KeyEvent.KEYCODE_A, true));
        Assert.assertFalse(state.isVirtualControlKeyDown());
        Assert.assertFalse(state.isVirtualFnKeyDown());
    }

    /**
     * The core regression: a Ctrl and Fn left "down" because their ACTION_UP was never delivered
     * (window focus lost while held) must be fully cleared by {@link VirtualKeysState#reset()}, which
     * is invoked from {@code TermuxActivity.onWindowFocusChanged}/{@code onStop}.
     */
    @Test
    public void resetClearsStuckModifiers() {
        VirtualKeysState state = new VirtualKeysState();
        state.handleVirtualModifierKey(KeyEvent.KEYCODE_VOLUME_DOWN, true); // stuck Ctrl
        state.handleVirtualModifierKey(KeyEvent.KEYCODE_VOLUME_UP, true);   // stuck Fn
        Assert.assertTrue(state.isVirtualControlKeyDown());
        Assert.assertTrue(state.isVirtualFnKeyDown());

        state.reset();

        Assert.assertFalse("Ctrl must not stay stuck after reset", state.isVirtualControlKeyDown());
        Assert.assertFalse("Fn must not stay stuck after reset", state.isVirtualFnKeyDown());
    }

    /**
     * Fn is force-released mid-session when it triggers an action that hands input back to the
     * terminal view, e.g. toggling the terminal toolbar with {@code Fn+k} (termux/termux-app#1420).
     */
    @Test
    public void setVirtualFnKeyDownReleasesFn() {
        VirtualKeysState state = new VirtualKeysState();
        state.handleVirtualModifierKey(KeyEvent.KEYCODE_VOLUME_UP, true);
        Assert.assertTrue(state.isVirtualFnKeyDown());

        state.setVirtualFnKeyDown(false);

        Assert.assertFalse("Fn should be released", state.isVirtualFnKeyDown());
        Assert.assertFalse("Ctrl should remain untouched", state.isVirtualControlKeyDown());
    }

}
