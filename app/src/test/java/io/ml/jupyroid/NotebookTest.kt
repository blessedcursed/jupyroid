package io.ml.jupyroid

import io.ml.jupyroid.model.CellType
import io.ml.jupyroid.model.Notebook
import io.ml.jupyroid.model.Output
import io.ml.jupyroid.model.appendStreamText
import io.ml.jupyroid.model.multilineJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookTest {
    private val sample = """
    {
     "cells": [
      {"cell_type": "markdown", "id": "a1", "metadata": {"tags": ["intro"]}, "source": ["# Title\n", "text"]},
      {"cell_type": "code", "execution_count": 3, "id": "b2", "metadata": {"scrolled": true},
       "outputs": [
        {"name": "stdout", "output_type": "stream", "text": ["hello\n"]},
        {"data": {"text/plain": ["42"], "image/png": "iVBORw0KGgo="}, "execution_count": 3,
         "metadata": {"image/png": {"width": 300}}, "output_type": "execute_result"},
        {"ename": "ZeroDivisionError", "evalue": "division by zero", "output_type": "error", "traceback": ["tb1", "tb2"]}
       ],
       "source": "x = 1\nprint(x)"}
     ],
     "metadata": {"kernelspec": {"name": "python3", "display_name": "Python 3", "language": "python"}, "custom": {"k": 1}},
     "nbformat": 4, "nbformat_minor": 5
    }
    """.trimIndent()

    private fun parse(s: String = sample) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun parsesCellsAndOutputs() {
        val nb = Notebook.fromJson(parse())
        assertEquals("python3", nb.kernelName)
        assertEquals(2, nb.cells.size)
        assertEquals(CellType.Markdown, nb.cells[0].type)
        assertEquals("# Title\ntext", nb.cells[0].source)
        val code = nb.cells[1]
        assertEquals("x = 1\nprint(x)", code.source)
        assertEquals(3, code.executionCount)
        assertEquals(Output.Stream("stdout", "hello\n"), code.outputs[0])
        val rich = code.outputs[1] as Output.Rich
        assertEquals("42", rich.text("text/plain"))
        assertTrue(rich.isResult)
        assertEquals(listOf("tb1", "tb2"), (code.outputs[2] as Output.Error).traceback)
    }

    @Test
    fun roundTripKeepsEverything() {
        val original = parse()
        val once = Notebook.fromJson(original).toJson()
        val twice = Notebook.fromJson(once).toJson()
        assertEquals(once, twice)
        assertEquals(original["metadata"], once["metadata"])
        val cell = once["cells"]!!.jsonArray[1].jsonObject
        assertEquals(original["cells"]!!.jsonArray[1].jsonObject["metadata"], cell["metadata"])
        assertEquals(JsonPrimitive("b2"), cell["id"])
        // Base64 images stay a single string, text is split into lines.
        val data = cell["outputs"]!!.jsonArray[1].jsonObject["data"]!!.jsonObject
        assertEquals(JsonPrimitive("iVBORw0KGgo="), data["image/png"])
        assertTrue(data["text/plain"] is JsonArray)
    }

    @Test
    fun oldNotebooksDontGetCellIds() {
        val nb = Notebook.fromJson(parse(sample.replace("\"nbformat_minor\": 5", "\"nbformat_minor\": 4")))
        val cell = nb.toJson()["cells"]!!.jsonArray[0].jsonObject
        assertTrue("id" !in cell)
    }

    @Test
    fun multilineSplitsKeepingNewlines() {
        assertEquals(listOf("a\n", "b\n", "c"), multilineJson("a\nb\nc").map { (it as JsonPrimitive).content })
        assertEquals(listOf("a\n"), multilineJson("a\n").map { (it as JsonPrimitive).content })
        assertEquals(0, multilineJson("").size)
    }

    @Test
    fun streamCarriageReturnOverwritesLine() {
        assertEquals("ab", appendStreamText("a", "b"))
        assertEquals("step 3", appendStreamText("step 1\rstep 2", "\rstep 3"))
        assertEquals("done\n50%", appendStreamText("done\n10%", "\r50%"))
        assertEquals("keep", appendStreamText("keep", "\r"))
    }
}
