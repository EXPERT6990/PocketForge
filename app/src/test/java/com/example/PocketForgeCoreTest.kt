package com.example

import com.example.data.sandbox.ClipboardPatchKind
import com.example.data.sandbox.ClipboardPatchParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketForgeCoreTest {

    @Test
    fun clipboardPatchParser_extractsSearchReplaceBlock() {
        val reply = """
            Here is the fix:
            FILE: src/TokenValidator.kt
            <<<SEARCH
            return effectiveExpiry > nowEpochSec
            ===
            return effectiveExpiry <= nowEpochSec
            >>>REPLACE
        """.trimIndent()

        val proposals = ClipboardPatchParser.parse(reply)
        assertEquals(1, proposals.size)
        assertEquals("src/TokenValidator.kt", proposals[0].relativePath)
        assertEquals(ClipboardPatchKind.SEARCH_REPLACE, proposals[0].kind)
        assertEquals("return effectiveExpiry > nowEpochSec", proposals[0].searchBlock)
        assertEquals("return effectiveExpiry <= nowEpochSec", proposals[0].replaceOrContent)
    }

    @Test
    fun clipboardPatchParser_extractsFencedFileBlock() {
        val reply = """
            FILE: src/Greeting.kt
            ```kotlin
            fun greet(name: String) = "Hello, ${'$'}name"
            ```
        """.trimIndent()

        val proposals = ClipboardPatchParser.parse(reply)
        assertEquals(1, proposals.size)
        assertEquals("src/Greeting.kt", proposals[0].relativePath)
        assertEquals(ClipboardPatchKind.FULL_WRITE, proposals[0].kind)
        assertTrue(proposals[0].replaceOrContent.contains("fun greet"))
    }

    @Test
    fun clipboardPatchParser_buildsContextWrapperWithPatchInstructions() {
        val wrapped = ClipboardPatchParser.buildContextPromptWrapper(
            workspaceName = "DemoApp",
            contextTitle = "Tree",
            contextPayload = "src/Main.kt"
        )
        assertTrue(wrapped.contains("Workspace: DemoApp"))
        assertTrue(wrapped.contains("<<<SEARCH"))
        assertTrue(wrapped.contains(">>>REPLACE"))
        assertTrue(wrapped.contains("src/Main.kt"))
    }
}
