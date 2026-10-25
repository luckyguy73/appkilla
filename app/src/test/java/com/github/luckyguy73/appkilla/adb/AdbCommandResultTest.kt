package com.github.luckyguy73.appkilla.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbCommandResultTest {
    @Test
    fun acceptsNormalAndroidPackageNames() {
        assertTrue(AdbCommandResult.isValidPackageName("com.example.app"))
        assertTrue(AdbCommandResult.isValidPackageName("org.example_app.player"))
    }

    @Test
    fun rejectsShellSyntaxAndMalformedPackageNames() {
        assertFalse(AdbCommandResult.isValidPackageName(""))
        assertFalse(AdbCommandResult.isValidPackageName("com.example.app; reboot"))
        assertFalse(AdbCommandResult.isValidPackageName("com.example.app\$(id)"))
        assertFalse(AdbCommandResult.isValidPackageName("com"))
        assertFalse(AdbCommandResult.isValidPackageName("com..example"))
    }

    @Test
    fun commandCapturesForceStopExitStatus() {
        val command = AdbCommandResult.forceStopCommand("com.example.app")
        assertNotNull(command)
        assertTrue(command!!.startsWith("am force-stop com.example.app;"))
        assertTrue(command.contains(AdbCommandResult.EXIT_STATUS_MARKER))
        assertTrue(command.contains("status=\$?"))
    }

    @Test
    fun parsesZeroAndNonZeroExitStatuses() {
        assertEquals(0, AdbCommandResult.parseExitCode("output\n" + AdbCommandResult.EXIT_STATUS_MARKER + "0\n"))
        assertEquals(1, AdbCommandResult.parseExitCode("${AdbCommandResult.EXIT_STATUS_MARKER}1"))
        assertEquals(127, AdbCommandResult.parseExitCode("${AdbCommandResult.EXIT_STATUS_MARKER}127"))
    }

    @Test
    fun rejectsMissingOrMalformedExitStatus() {
        assertNull(AdbCommandResult.parseExitCode("force-stop returned"))
        assertNull(AdbCommandResult.parseExitCode("${AdbCommandResult.EXIT_STATUS_MARKER}not-a-number"))
        assertNull(AdbCommandResult.parseExitCode("${AdbCommandResult.EXIT_STATUS_MARKER}999"))
    }
}
