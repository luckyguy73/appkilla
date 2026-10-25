package com.github.luckyguy73.appkilla.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunningProcessParserTest {
    @Test
    fun parsesPackageAndSecondaryProcessesFromPsOutput() {
        val output = """
            USER PID PPID VSZ RSS WCHAN ADDR S NAME
            u0_a123 1234 1 123456 12345 0 0 S com.example.player
            u0_a123 1235 1 123456 12345 0 0 S com.example.player:remote
            system 10 1 0 0 0 0 S system_server
        """.trimIndent()

        assertEquals(
            setOf("com.example.player", "com.example.player:remote"),
            RunningProcessParser.parse(output)
        )
    }

    @Test
    fun returnsEmptySetForSuccessfulScanWithNoAppProcesses() {
        assertEquals(emptySet<String>(), RunningProcessParser.parse("USER PID NAME\nsystem 1 init"))
    }

    @Test
    fun returnsNullForMissingOutput() {
        assertNull(RunningProcessParser.parse(""))
        assertNull(RunningProcessParser.parse("  \n"))
    }

    @Test
    fun ignoresNonPackageProcessNames() {
        assertEquals(emptySet<String>(), RunningProcessParser.parse("USER PID NAME\nsystem 1 init"))
    }
}
