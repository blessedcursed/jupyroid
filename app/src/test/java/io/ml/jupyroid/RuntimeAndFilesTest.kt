package io.ml.jupyroid

import io.ml.jupyroid.output.Csv
import io.ml.jupyroid.runtime.DockerRegistry
import io.ml.jupyroid.workspace.FileKind
import io.ml.jupyroid.workspace.FileSession
import io.ml.jupyroid.workspace.TerminalTab
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAndFilesTest {
    @Test
    fun csvQuotesAndNewlines() {
        val t = Csv.parse("a,b,c\n1,\"x, y\",\"he said \"\"hi\"\"\"\n2,\"multi\nline\",3\n")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "x, y", "he said \"hi\""), listOf("2", "multi\nline", "3")), t.rows)
        assertFalse(t.truncated)
    }

    @Test
    fun csvSniffsDelimiterAndCrlf() {
        val t = Csv.parse("x;y\r\n1;2\r\n")
        assertEquals(';', t.delimiter)
        assertEquals(listOf(listOf("x", "y"), listOf("1", "2")), t.rows)
        assertEquals('\t', Csv.sniffDelimiter("a\tb\tc\n"))
        // Delimiters inside quotes don't count.
        assertEquals(',', Csv.sniffDelimiter("\"a;b;c\",d\n"))
    }

    @Test
    fun csvTruncates() {
        val t = Csv.parse((1..10).joinToString("\n") { "$it" }, maxRows = 3)
        assertEquals(3, t.rows.size)
        assertTrue(t.truncated)
        assertEquals(2, Csv.parse("a\nb", maxRows = 2).rows.size)
        assertFalse(Csv.parse("a\nb\n", maxRows = 2).truncated)
    }

    @Test
    fun picksArm64V8FromManifestList() {
        val index = Json.parseToJsonElement(
            """
            {"manifests": [
              {"digest": "sha256:amd", "platform": {"architecture": "amd64", "os": "linux"}},
              {"digest": "sha256:v7", "platform": {"architecture": "arm", "os": "linux", "variant": "v7"}},
              {"digest": "sha256:att", "platform": {"architecture": "unknown", "os": "unknown"}},
              {"digest": "sha256:arm64", "platform": {"architecture": "arm64", "os": "linux", "variant": "v8"}}
            ]}
            """,
        ).jsonObject
        assertEquals("sha256:arm64", DockerRegistry.selectPlatform(index, "arm64"))
        assertEquals("sha256:amd", DockerRegistry.selectPlatform(index, "amd64"))
        assertNull(DockerRegistry.selectPlatform(index, "riscv64"))
    }

    @Test
    fun parsesLayers() {
        val manifest = Json.parseToJsonElement(
            """{"layers": [{"digest": "sha256:aa", "size": 28137179, "mediaType": "x"}]}""",
        ).jsonObject
        assertEquals(listOf(io.ml.jupyroid.runtime.Layer("sha256:aa", 28137179)), DockerRegistry.parseLayers(manifest))
    }

    @Test
    fun fileKinds() {
        assertEquals(FileKind.Python, FileSession.kindOf("train.py"))
        assertEquals(FileKind.Markdown, FileSession.kindOf("README.md"))
        assertEquals(FileKind.Csv, FileSession.kindOf("data.TSV"))
        assertEquals(FileKind.Text, FileSession.kindOf("notes.txt"))
        assertTrue(FileSession.isTextFile("Makefile"))
        assertTrue(FileSession.isTextFile(".bashrc"))
        assertTrue(FileSession.isTextFile("config.yaml"))
        assertFalse(FileSession.isTextFile("photo.png"))
        assertFalse(FileSession.isTextFile("model.pt"))
    }

    @Test
    fun shellQuoting() {
        assertEquals("'/root/my file.py'", TerminalTab.shellQuote("/root/my file.py"))
        assertEquals("'it'\\''s.py'", TerminalTab.shellQuote("it's.py"))
    }
}
