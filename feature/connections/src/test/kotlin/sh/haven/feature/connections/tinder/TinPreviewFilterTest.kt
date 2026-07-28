package sh.haven.feature.connections.tinder

import org.junit.Assert.assertEquals
import org.junit.Test

class TinPreviewFilterTest {

    @Test
    fun testEmptyOrNull() {
        assertEquals(emptyList<String>(), meaningfulTail(null))
        assertEquals(emptyList<String>(), meaningfulTail(""))
        assertEquals(emptyList<String>(), meaningfulTail("   \n\n  "))
    }

    @Test
    fun testBoxDrawingsAndBorders() {
        val snapshot = """
            │ running server on port 8080 │
            ───────────────────────────────
            ▟
            ▏ ▕ · … |
        """.trimIndent()
        // Box drawing lines should be skipped
        assertEquals(listOf("running server on port 8080"), meaningfulTail(snapshot))
    }

    @Test
    fun testChromeRemoval() {
        val snapshot = """
            bypass permissions to proceed
            esc to cancel execution
            ctrl+c to interrupt
            actual output from process
        """.trimIndent()
        // Chrome statements should be skipped
        assertEquals(listOf("actual output from process"), meaningfulTail(snapshot))
    }

    @Test
    fun testPromptRemoval() {
        val snapshot = """
            user@host:~$ 
            $ 
            actual command output
            # 
        """.trimIndent()
        // Prompts under 60 chars ending with prompt symbols should be skipped
        assertEquals(listOf("actual command output"), meaningfulTail(snapshot))
    }

    @Test
    fun testMaxLinesLimit() {
        val snapshot = """
            line 1
            line 2
            line 3
            line 4
            line 5
            line 6
        """.trimIndent()
        // Should only return the last 4 lines in correct chronological order
        assertEquals(listOf("line 3", "line 4", "line 5", "line 6"), meaningfulTail(snapshot, 4))
        assertEquals(listOf("line 5", "line 6"), meaningfulTail(snapshot, 2))
    }

    @Test
    fun testSurvivalLineTrimming() {
        val snapshot = """
            ❯ node server.js
            > npm run start
            │   indented actual output
        """.trimIndent()
        // Prefixes like ❯, >, │, spaces and tabs should be trimmed from output
        assertEquals(
            listOf("node server.js", "npm run start", "indented actual output"),
            meaningfulTail(snapshot)
        )
    }
}
