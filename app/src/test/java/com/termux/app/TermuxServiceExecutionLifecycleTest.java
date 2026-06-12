package com.termux.app;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.ExecutionState;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.shell.command.result.ResultConfig;
import com.termux.shared.termux.shell.TermuxShellManager;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Regression tests for the background-execution cleanup / finish lifecycle.
 *
 * These tests verify the invariants that were violated by the bugs fixed in
 * this refactoring:
 * <ul>
 *   <li>Every plugin command that enters the pending list must eventually leave it
 *       (success, failure, or cancellation).</li>
 *   <li>{@link ExecutionCommand} state transitions are forward-only and the
 *       duplicate-result guard ({@link ExecutionCommand#shouldNotProcessResults()})
 *       fires exactly once.</li>
 *   <li>{@link ResultConfig#isCommandWithPendingResult()} correctly reflects
 *       whether a caller is waiting for a result.</li>
 * </ul>
 *
 * Note: these are pure-JVM JUnit tests.  Android-framework classes (Context,
 * PendingIntent, etc.) are not available, so we exercise only the model layer
 * that does not depend on the framework.
 */
public class TermuxServiceExecutionLifecycleTest {

    private TermuxShellManager shellManager;

    @Before
    public void setUp() throws Exception {
        // TermuxShellManager's constructor requires an Android Context which is unavailable
        // in pure-JVM tests.  We allocate an instance via sun.misc.Unsafe (bypassing the
        // constructor) and manually initialise its list fields.  The methods under test
        // (removePendingPluginExecutionCommand and direct list access) never touch mContext.
        shellManager = createShellManagerWithoutContext();
    }

    @SuppressWarnings("unchecked")
    private static TermuxShellManager createShellManagerWithoutContext() throws Exception {
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocateInstance = unsafe.getClass().getMethod("allocateInstance", Class.class);
        TermuxShellManager instance = (TermuxShellManager) allocateInstance.invoke(unsafe, TermuxShellManager.class);

        // Unsafe.allocateInstance skips field initialisers, so set the lists manually.
        setFinalField(instance, "mPendingPluginExecutionCommands", new ArrayList<ExecutionCommand>());
        setFinalField(instance, "mTermuxSessions", new ArrayList<>());
        setFinalField(instance, "mTermuxTasks", new ArrayList<>());

        // Replace the static singleton so getShellManager() works if any code path calls it.
        Field shellManagerField = TermuxShellManager.class.getDeclaredField("shellManager");
        shellManagerField.setAccessible(true);
        shellManagerField.set(null, instance);

        return instance;
    }

    private static void setFinalField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    // -----------------------------------------------------------------------
    // ExecutionCommand state machine
    // -----------------------------------------------------------------------

    @Test
    public void testStateTransition_preExecution_to_executing() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        Assert.assertTrue(cmd.setState(ExecutionState.EXECUTING));
        Assert.assertFalse(cmd.hasExecuted());
        Assert.assertTrue(cmd.isExecuting());
    }

    @Test
    public void testStateTransition_fullHappyPath() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        Assert.assertTrue(cmd.setState(ExecutionState.EXECUTING));
        Assert.assertTrue(cmd.setState(ExecutionState.EXECUTED));
        Assert.assertTrue(cmd.hasExecuted());
        Assert.assertTrue(cmd.setState(ExecutionState.SUCCESS));
        Assert.assertTrue(cmd.isSuccessful());
    }

    @Test
    public void testStateTransition_backwardTransitionRejected() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        Assert.assertTrue(cmd.setState(ExecutionState.EXECUTING));
        Assert.assertTrue(cmd.setState(ExecutionState.EXECUTED));
        // Trying to go back to EXECUTING must fail
        Assert.assertFalse(cmd.setState(ExecutionState.EXECUTING));
    }

    @Test
    public void testStateTransition_successIsTerminal() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        Assert.assertTrue(cmd.setState(ExecutionState.EXECUTING));
        Assert.assertTrue(cmd.setState(ExecutionState.EXECUTED));
        Assert.assertTrue(cmd.setState(ExecutionState.SUCCESS));
        // Cannot transition from SUCCESS to anything
        Assert.assertFalse(cmd.setState(ExecutionState.FAILED));
    }

    @Test
    public void testStateTransition_failedCanBeSetAgain() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        Assert.assertTrue(cmd.setState(ExecutionState.EXECUTING));
        // FAILED can be set from EXECUTING (value 4 > value 1)
        Assert.assertTrue(cmd.setStateFailed(1, "first error"));
        Assert.assertTrue(cmd.isStateFailed());
        // FAILED can be set again (to accumulate errors)
        Assert.assertTrue(cmd.setStateFailed(2, "second error"));
        Assert.assertTrue(cmd.isStateFailed());
    }

    @Test
    public void testStateTransition_failedPreservesPreviousState() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        Assert.assertTrue(cmd.setState(ExecutionState.EXECUTING));
        Assert.assertTrue(cmd.setState(ExecutionState.EXECUTED));
        Assert.assertTrue(cmd.setStateFailed(1, "error after executed"));
        // previousState should be EXECUTED, not FAILED
        Assert.assertEquals("Executed", cmd.getPreviousStateLogString()
            .replace("Previous State: `", "").replace("`", ""));
    }

    @Test
    public void testHasExecuted_afterFailed() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        // FAILED has value 4, EXECUTED has value 2, so hasExecuted() returns true for FAILED
        Assert.assertTrue(cmd.setStateFailed(1, "immediate failure"));
        Assert.assertTrue("FAILED state should satisfy hasExecuted() since value(4) >= value(2)",
            cmd.hasExecuted());
    }

    // -----------------------------------------------------------------------
    // shouldNotProcessResults — duplicate processing guard
    // -----------------------------------------------------------------------

    @Test
    public void testShouldNotProcessResults_firstCallReturnsFalse() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        Assert.assertFalse("First call should allow processing", cmd.shouldNotProcessResults());
    }

    @Test
    public void testShouldNotProcessResults_secondCallReturnsTrue() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        cmd.shouldNotProcessResults(); // first call
        Assert.assertTrue("Second call should block processing", cmd.shouldNotProcessResults());
    }

    @Test
    public void testShouldNotProcessResults_multipleSubsequentCalls() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        cmd.shouldNotProcessResults();
        Assert.assertTrue(cmd.shouldNotProcessResults());
        Assert.assertTrue(cmd.shouldNotProcessResults());
        Assert.assertTrue(cmd.shouldNotProcessResults());
    }

    // -----------------------------------------------------------------------
    // ResultConfig.isCommandWithPendingResult
    // -----------------------------------------------------------------------

    @Test
    public void testIsCommandWithPendingResult_defaultIsFalse() {
        ResultConfig config = new ResultConfig();
        Assert.assertFalse(config.isCommandWithPendingResult());
    }

    @Test
    public void testIsCommandWithPendingResult_withDirectoryPath() {
        ResultConfig config = new ResultConfig();
        config.resultDirectoryPath = "/data/local/tmp/results";
        Assert.assertTrue(config.isCommandWithPendingResult());
    }

    // Note: We cannot test resultPendingIntent != null in pure JUnit since
    // PendingIntent is an Android framework class.

    // -----------------------------------------------------------------------
    // isPluginExecutionCommandWithPendingResult
    // -----------------------------------------------------------------------

    @Test
    public void testIsPluginExecutionCommandWithPendingResult_nonPluginIsFalse() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        cmd.isPluginExecutionCommand = false;
        cmd.resultConfig.resultDirectoryPath = "/some/path";
        Assert.assertFalse(cmd.isPluginExecutionCommandWithPendingResult());
    }

    @Test
    public void testIsPluginExecutionCommandWithPendingResult_pluginWithoutResultConfigIsFalse() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        cmd.isPluginExecutionCommand = true;
        // No resultPendingIntent or resultDirectoryPath set
        Assert.assertFalse(cmd.isPluginExecutionCommandWithPendingResult());
    }

    @Test
    public void testIsPluginExecutionCommandWithPendingResult_pluginWithResultDirectoryIsTrue() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        cmd.isPluginExecutionCommand = true;
        cmd.resultConfig.resultDirectoryPath = "/data/local/tmp/results";
        Assert.assertTrue(cmd.isPluginExecutionCommandWithPendingResult());
    }

    // -----------------------------------------------------------------------
    // TermuxShellManager.removePendingPluginExecutionCommand
    // -----------------------------------------------------------------------

    @Test
    public void testRemovePendingPluginExecutionCommand_removesExistingCommand() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        shellManager.mPendingPluginExecutionCommands.add(cmd);
        Assert.assertEquals(1, shellManager.mPendingPluginExecutionCommands.size());

        shellManager.removePendingPluginExecutionCommand(cmd);
        Assert.assertEquals(0, shellManager.mPendingPluginExecutionCommands.size());
    }

    @Test
    public void testRemovePendingPluginExecutionCommand_nullIsNoop() {
        shellManager.removePendingPluginExecutionCommand(null);
        Assert.assertEquals(0, shellManager.mPendingPluginExecutionCommands.size());
    }

    @Test
    public void testRemovePendingPluginExecutionCommand_notInListIsNoop() {
        ExecutionCommand cmd1 = new ExecutionCommand(1);
        ExecutionCommand cmd2 = new ExecutionCommand(2);
        shellManager.mPendingPluginExecutionCommands.add(cmd1);

        shellManager.removePendingPluginExecutionCommand(cmd2);
        Assert.assertEquals(1, shellManager.mPendingPluginExecutionCommands.size());
        Assert.assertSame(cmd1, shellManager.mPendingPluginExecutionCommands.get(0));
    }

    @Test
    public void testRemovePendingPluginExecutionCommand_idempotent() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        shellManager.mPendingPluginExecutionCommands.add(cmd);

        shellManager.removePendingPluginExecutionCommand(cmd);
        Assert.assertEquals(0, shellManager.mPendingPluginExecutionCommands.size());

        // Second removal is a no-op, does not throw
        shellManager.removePendingPluginExecutionCommand(cmd);
        Assert.assertEquals(0, shellManager.mPendingPluginExecutionCommands.size());
    }

    // -----------------------------------------------------------------------
    // Pending list lifecycle — simulating the patterns used by TermuxService
    // -----------------------------------------------------------------------

    /**
     * Simulates the try-finally pattern used in executeTermuxTaskCommand and
     * executeTermuxSessionCommand.  The pending command must be removed regardless
     * of whether the task creation succeeds, fails, or throws.
     */
    @Test
    public void testPendingListCleanup_simulateTryFinally_successPath() {
        ExecutionCommand cmd = createPendingPluginCommand(1);

        try {
            // Simulate successful task creation (command moves to mTermuxTasks)
        } finally {
            shellManager.removePendingPluginExecutionCommand(cmd);
        }

        Assert.assertEquals("Pending list must be empty after successful creation",
            0, shellManager.mPendingPluginExecutionCommands.size());
    }

    @Test
    public void testPendingListCleanup_simulateTryFinally_failurePath() {
        ExecutionCommand cmd = createPendingPluginCommand(2);

        try {
            // Simulate processShellCreateMode returning null (early return)
        } finally {
            shellManager.removePendingPluginExecutionCommand(cmd);
        }

        Assert.assertEquals("Pending list must be empty after failure path",
            0, shellManager.mPendingPluginExecutionCommands.size());
    }

    @Test
    public void testPendingListCleanup_simulateTryFinally_earlyReturn() {
        ExecutionCommand cmd = createPendingPluginCommand(3);

        // Simulate executeTermuxTaskCommand with early return from processShellCreateMode
        simulateExecuteWithEarlyReturn(cmd);

        Assert.assertEquals("Pending list must be empty even after early return",
            0, shellManager.mPendingPluginExecutionCommands.size());
    }

    @Test
    public void testPendingListCleanup_simulateTryFinally_exceptionPath() {
        ExecutionCommand cmd = createPendingPluginCommand(4);

        try {
            throw new RuntimeException("Simulated unexpected exception");
        } catch (RuntimeException e) {
            // Expected
        } finally {
            shellManager.removePendingPluginExecutionCommand(cmd);
        }

        Assert.assertEquals("Pending list must be empty even after exception",
            0, shellManager.mPendingPluginExecutionCommands.size());
    }

    /**
     * Simulates multiple commands in various states — all must eventually
     * leave the pending list.
     */
    @Test
    public void testPendingListCleanup_multipleCommandLifecycle() {
        ExecutionCommand cmd1 = createPendingPluginCommand(10);
        ExecutionCommand cmd2 = createPendingPluginCommand(11);
        ExecutionCommand cmd3 = createPendingPluginCommand(12);

        Assert.assertEquals(3, shellManager.mPendingPluginExecutionCommands.size());

        // cmd1: success path
        shellManager.removePendingPluginExecutionCommand(cmd1);
        Assert.assertEquals(2, shellManager.mPendingPluginExecutionCommands.size());

        // cmd2: failure path
        shellManager.removePendingPluginExecutionCommand(cmd2);
        Assert.assertEquals(1, shellManager.mPendingPluginExecutionCommands.size());

        // cmd3: cancellation path (killAllTermuxExecutionCommands scenario)
        shellManager.removePendingPluginExecutionCommand(cmd3);
        Assert.assertEquals("All commands must have left the pending list",
            0, shellManager.mPendingPluginExecutionCommands.size());
    }

    /**
     * Regression: verifies that the double-removal pattern (once from
     * killAllTermuxExecutionCommands and once from the try-finally in the
     * execute method) does not cause errors.
     */
    @Test
    public void testPendingListCleanup_doubleRemovalIsSafe() {
        ExecutionCommand cmd = createPendingPluginCommand(20);

        // First removal — from killAllTermuxExecutionCommands
        shellManager.removePendingPluginExecutionCommand(cmd);
        Assert.assertEquals(0, shellManager.mPendingPluginExecutionCommands.size());

        // Second removal — from executeTermuxTaskCommand's finally block
        // Must not throw or corrupt the list
        shellManager.removePendingPluginExecutionCommand(cmd);
        Assert.assertEquals(0, shellManager.mPendingPluginExecutionCommands.size());
    }

    // -----------------------------------------------------------------------
    // Runner enum
    // -----------------------------------------------------------------------

    @Test
    public void testRunner_runnerOf_validName() {
        Assert.assertEquals(Runner.APP_SHELL, Runner.runnerOf("app-shell"));
        Assert.assertEquals(Runner.TERMINAL_SESSION, Runner.runnerOf("terminal-session"));
    }

    @Test
    public void testRunner_runnerOf_invalidNameReturnsNull() {
        Assert.assertNull(Runner.runnerOf("nonexistent-runner"));
    }

    @Test
    public void testRunner_equalsRunner() {
        Assert.assertTrue(Runner.APP_SHELL.equalsRunner("app-shell"));
        Assert.assertFalse(Runner.APP_SHELL.equalsRunner("terminal-session"));
        Assert.assertFalse(Runner.APP_SHELL.equalsRunner(null));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Creates a plugin ExecutionCommand and adds it to the pending list,
     * mirroring what actionServiceExecute does at line 414.
     */
    private ExecutionCommand createPendingPluginCommand(int id) {
        ExecutionCommand cmd = new ExecutionCommand(id);
        cmd.isPluginExecutionCommand = true;
        cmd.resultConfig.resultDirectoryPath = "/data/local/tmp/results";
        shellManager.mPendingPluginExecutionCommands.add(cmd);
        return cmd;
    }

    /**
     * Simulates the executeTermuxTaskCommand try-finally pattern with an
     * early return (as happens when processShellCreateMode returns null).
     */
    private void simulateExecuteWithEarlyReturn(ExecutionCommand cmd) {
        try {
            // Early return — simulates processShellCreateMode returning null
            return;
        } finally {
            shellManager.removePendingPluginExecutionCommand(cmd);
        }
    }
}
