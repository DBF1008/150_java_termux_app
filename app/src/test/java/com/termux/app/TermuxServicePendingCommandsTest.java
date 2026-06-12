package com.termux.app;

import com.termux.shared.errors.Errno;
import com.termux.shared.shell.command.ExecutionCommand;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Regression tests for the consolidated finish/cleanup logic of plugin execution commands in
 * {@link TermuxService}.
 *
 * These lock the invariants that previously regressed: when the service is stopped (success, failure
 * or a manual stop), every still-pending command that expects a result is finalized <b>exactly
 * once</b>, and the pending list is always left empty so nothing leaks in the singleton
 * {@link com.termux.shared.termux.shell.TermuxShellManager} or gets re-processed (sending the caller
 * a second, contradictory result) on a later stop.
 */
@RunWith(RobolectricTestRunner.class)
public class TermuxServicePendingCommandsTest {

    /** A command "expects a result" when it is a plugin command and has either a result pending
     * intent or a result directory. Using a result directory path keeps the test free of a real
     * {@link android.app.PendingIntent}. */
    private static ExecutionCommand newCommand(boolean isPluginExecutionCommand, boolean expectsResult) {
        ExecutionCommand executionCommand = new ExecutionCommand(1);
        executionCommand.isPluginExecutionCommand = isPluginExecutionCommand;
        if (expectsResult)
            executionCommand.resultConfig.resultDirectoryPath = "/data/data/com.termux/files/home/result";
        return executionCommand;
    }

    @Test
    public void cancelClearsListAndFinalizesPendingResultCommandsExactlyOnce() {
        ExecutionCommand withResult1 = newCommand(true, true);
        ExecutionCommand withResult2 = newCommand(true, true);
        ExecutionCommand pluginNoResult = newCommand(true, false);
        ExecutionCommand nonPlugin = newCommand(false, false);

        List<ExecutionCommand> pending = new ArrayList<>();
        pending.add(withResult1);
        pending.add(withResult2);
        pending.add(pluginNoResult);
        pending.add(nonPlugin);

        List<ExecutionCommand> finalized = new ArrayList<>();
        TermuxService.cancelAndClearPendingPluginExecutionCommands(pending, "cancelled", finalized::add);

        // The list is fully drained so nothing lingers for the next service incarnation.
        Assert.assertTrue("pending list should be empty after cancellation", pending.isEmpty());

        // Only the commands that expect a result back are finalized; the others are dropped silently.
        Assert.assertEquals(2, finalized.size());
        Assert.assertTrue(finalized.contains(withResult1));
        Assert.assertTrue(finalized.contains(withResult2));
        Assert.assertFalse(finalized.contains(pluginNoResult));
        Assert.assertFalse(finalized.contains(nonPlugin));

        // Finalized commands are left in the cancelled failed state.
        Assert.assertTrue(withResult1.isStateFailed());
        Assert.assertEquals(Errno.ERRNO_CANCELLED.getCode(), withResult1.resultData.getErrCode());
        Assert.assertTrue(withResult2.isStateFailed());
    }

    @Test
    public void secondCancelDoesNotReFinalize() {
        ExecutionCommand command = newCommand(true, true);

        List<ExecutionCommand> pending = new ArrayList<>();
        pending.add(command);

        List<ExecutionCommand> finalized = new ArrayList<>();

        // First stop (e.g. a manual ACTION_STOP_SERVICE): the command is finalized once.
        TermuxService.cancelAndClearPendingPluginExecutionCommands(pending, "cancelled", finalized::add);
        Assert.assertEquals(1, finalized.size());
        Assert.assertTrue(pending.isEmpty());

        // A later cleanup pass over the same command (e.g. onDestroy after the manual stop) must NOT
        // send a second result to the caller.
        pending.add(command);
        TermuxService.cancelAndClearPendingPluginExecutionCommands(pending, "cancelled", finalized::add);
        Assert.assertEquals("command must not be finalized twice", 1, finalized.size());
        Assert.assertTrue(pending.isEmpty());
    }
}
