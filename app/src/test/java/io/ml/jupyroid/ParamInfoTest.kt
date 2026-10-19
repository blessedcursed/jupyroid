package io.ml.jupyroid

import io.ml.jupyroid.editor.CallContexts
import io.ml.jupyroid.editor.Signature
import io.ml.jupyroid.editor.SignatureParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParamInfoTest {
    /** `|` marks the cursor. */
    private fun ctx(s: String) = CallContexts.find(s.replace("|", ""), s.indexOf('|'))

    @Test
    fun findsInnermostCall() {
        val c = ctx("df = pd.read_csv(\"a.csv\", |")!!
        assertEquals("pd.read_csv", c.callee)
        assertEquals(1, c.argIndex)
        assertNull(c.keyword)
        assertEquals("f", ctx("g(1, f(x, |), 2)")!!.callee)
        assertEquals(1, ctx("g(1, f(x, y)|")!!.argIndex)
    }

    @Test
    fun detectsKeywordArguments() {
        assertEquals("sep", ctx("read_csv(path, sep=|")!!.keyword)
        assertEquals("sep", ctx("read_csv(path, sep = ';'|")!!.keyword)
        assertNull(ctx("f(a == |")!!.keyword) // comparison, not a keyword arg
    }

    @Test
    fun looksThroughListsAndIgnoresStringsAndComments() {
        val c = ctx("np.array([1, 2, |")!!
        assertEquals("np.array", c.callee)
        assertEquals(0, c.argIndex)
        assertEquals(1, ctx("f(\"a, (b\", |")!!.argIndex)
        assertEquals("f", ctx("f(\"inside |")!!.callee) // typing a string argument
        assertEquals(1, ctx("f(x, 'a, b|")!!.argIndex) // commas inside it don't count
        assertNull(ctx("x = \"f(|"))
        assertNull(ctx("f(x)  # call(|"))
        assertEquals("f", ctx("# g(\nf(|")!!.callee)
    }

    @Test
    fun notACall() {
        assertNull(ctx("x = (1 + |"))
        assertNull(ctx("if (a|"))
        assertNull(ctx("def f(a, |"))
        assertNull(ctx("class A(Base|"))
        assertNull(ctx("print(1)|"))
        assertEquals("f", ctx("undef_f(|")?.callee?.let { "f" })
    }

    @Test
    fun parsesIpythonInspectOutput() {
        val esc = "\u001B"
        val text = "$esc[0;31mSignature:$esc[0m\n" +
            "pd.read_csv(\n" +
            "    filepath_or_buffer: 'FilePath | ReadCsvBuffer[bytes]',\n" +
            "    *,\n" +
            "    sep: 'str | None' = <no_default>,\n" +
            "    usecols: 'list[str] | None' = None,\n" +
            ") -> 'DataFrame | TextFileReader'\n" +
            "$esc[0;31mDocstring:$esc[0m\nRead a comma-separated values (csv) file.\n"
        val sig = SignatureParser.parse(text, "pd.read_csv")!!
        assertEquals("read_csv", sig.name)
        assertEquals(
            listOf("filepath_or_buffer: 'FilePath | ReadCsvBuffer[bytes]'", "*", "sep: 'str | None' = <no_default>", "usecols: 'list[str] | None' = None"),
            sig.params,
        )
        assertEquals("'DataFrame | TextFileReader'", sig.returns)
    }

    @Test
    fun parsesInitSignatureAndDocstringFallback() {
        val cls = SignatureParser.parse("Init signature: Counter(iterable=None, /, **kwds)\nDocstring: Dict subclass", "Counter")!!
        assertEquals(listOf("iterable=None", "/", "**kwds"), cls.params)
        val builtin = SignatureParser.parse("Docstring:\nlen(obj, /)\n\nReturn the number of items.\nType: builtin", "len")!!
        assertEquals(listOf("obj", "/"), builtin.params)
        assertNull(SignatureParser.parse("Type: int\nString form: 3", "x"))
        // Defaults containing commas and parens stay in one parameter.
        assertEquals(listOf("a=(1, 2)", "b='x,y'"), SignatureParser.parseCall("f(a=(1, 2), b='x,y')")!!.params)
    }

    @Test
    fun activeParameter() {
        val sig = Signature("f", listOf("a", "b=1", "/", "c", "*args", "key=None", "**kw"), null)
        fun active(s: String) = sig.activeParam(ctx(s)!!)
        assertEquals(0, active("f(|"))
        assertEquals(1, active("f(x, |"))
        assertEquals(3, active("f(x, y, z|"))
        assertEquals(4, active("f(1, 2, 3, 4, 5|")) // *args
        assertEquals(5, active("f(x, key=|"))
        assertEquals(6, active("f(x, other=|")) // unknown keyword → **kw
        val kwOnly = Signature("g", listOf("a", "*", "b"), null)
        assertNull(kwOnly.activeParam(ctx("g(1, 2|")!!))
        assertEquals(2, kwOnly.activeParam(ctx("g(1, b=|")!!))
    }

    @Test
    fun compactParams() {
        assertEquals("sep=<no_default>", Signature.compact("sep: 'str | None | lib.NoDefault' = <no_default>"))
        assertEquals("header='infer'", Signature.compact("header: \"int | Literal['a=b']\" = 'infer'"))
        assertEquals("filepath_or_buffer", Signature.compact("filepath_or_buffer: 'FilePath'"))
        assertEquals("**kwds", Signature.compact("**kwds"))
        assertEquals("*", Signature.compact("*"))
        assertEquals("f=lambda x: x", Signature.compact("f=lambda x: x"))
    }
}
