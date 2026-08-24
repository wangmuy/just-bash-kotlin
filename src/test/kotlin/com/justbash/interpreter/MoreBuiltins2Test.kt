package com.justbash.interpreter

import com.justbash.ExecutionLimits
import com.justbash.fs.FsInit
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MoreBuiltins2Test {

    private lateinit var fs: InMemoryFs
    private lateinit var state: InterpreterState
    private lateinit var ctx: InterpreterContext

    @BeforeEach
    fun setup() {
        fs = InMemoryFs()
        FsInit.initCommonDirectories(fs, useDefaultLayout = true)
        // Place a couple of files so `hash` can resolve them via PATH.
        fs.writeFile("/bin/tool", "#!/bin/bash\n")
        fs.writeFile("/usr/local/bin/script", "#!/bin/bash\n")
        state = InterpreterState()
        state.env["PATH"] = "/usr/local/bin:/bin"
        ctx = InterpreterContext(
            state = state,
            fs = fs,
            commands = LinkedHashMap(),
            limits = ExecutionLimits(),
            wordExpander = ExpansionPlaceholder(state.env),
        )
    }

    // ---------------------------------------------------------------------
    // getopts
    // ---------------------------------------------------------------------

    @Test
    fun `getopts parses options with arguments`() {
        state.env["#"] = "3"
        state.env["1"] = "-a"
        state.env["2"] = "42"
        state.env["3"] = "file.txt"
        state.env["OPTIND"] = "1"

        val r1 = MoreBuiltins2.getopts(ctx, listOf("a:", "opt"))
        assertEquals(0, r1.exitCode)
        assertEquals("a", state.env["opt"])
        assertEquals("42", state.env["OPTARG"])
        assertEquals("3", state.env["OPTIND"])

        val r2 = MoreBuiltins2.getopts(ctx, listOf("a:", "opt"))
        assertEquals(1, r2.exitCode)
        assertEquals("?", state.env["opt"])
    }

    @Test
    fun `getopts handles combined options`() {
        state.env["#"] = "1"
        state.env["1"] = "-abc"
        state.env["OPTIND"] = "1"

        var result = MoreBuiltins2.getopts(ctx, listOf("abc", "opt"))
        assertEquals(0, result.exitCode)
        assertEquals("a", state.env["opt"])
        assertEquals("1", state.env["OPTIND"])

        result = MoreBuiltins2.getopts(ctx, listOf("abc", "opt"))
        assertEquals(0, result.exitCode)
        assertEquals("b", state.env["opt"])
        assertEquals("1", state.env["OPTIND"])

        result = MoreBuiltins2.getopts(ctx, listOf("abc", "opt"))
        assertEquals(0, result.exitCode)
        assertEquals("c", state.env["opt"])
        assertEquals("2", state.env["OPTIND"])
    }

    @Test
    fun `getopts reports missing required argument in non-silent mode`() {
        state.env["#"] = "1"
        state.env["1"] = "-a"
        state.env["OPTIND"] = "1"

        val result = MoreBuiltins2.getopts(ctx, listOf("a:", "opt"))
        assertEquals(0, result.exitCode)
        assertEquals("bash: option requires an argument -- a\n", result.stderr)
        assertEquals("?", state.env["opt"])
    }

    @Test
    fun `getopts silent mode reports via colon and OPTARG`() {
        state.env["#"] = "1"
        state.env["1"] = "-a"
        state.env["OPTIND"] = "1"

        val result = MoreBuiltins2.getopts(ctx, listOf(":a:", "opt"))
        assertEquals(0, result.exitCode)
        assertEquals("", result.stderr)
        assertEquals(":", state.env["opt"])
        assertEquals("a", state.env["OPTARG"])
    }

    @Test
    fun `getopts treats double dash as end of options`() {
        state.env["#"] = "2"
        state.env["1"] = "--"
        state.env["2"] = "x"
        state.env["OPTIND"] = "1"

        val result = MoreBuiltins2.getopts(ctx, listOf("x", "opt"))
        assertEquals(1, result.exitCode)
        assertEquals("?", state.env["opt"])
        assertEquals("2", state.env["OPTIND"])
    }

    // ---------------------------------------------------------------------
    // hash
    // ---------------------------------------------------------------------

    @Test
    fun `hash lists empty table`() {
        val result = MoreBuiltins2.hash(ctx, emptyList())
        assertEquals(0, result.exitCode)
        assertEquals("hash: hash table empty\n", result.stdout)
    }

    @Test
    fun `hash adds command to cache via PATH`() {
        val result = MoreBuiltins2.hash(ctx, listOf("tool"))
        assertEquals(0, result.exitCode)
        assertEquals("/bin/tool", ctx.state.hashTable!!["tool"])
    }

    @Test
    fun `hash dash r clears cache`() {
        ctx.state.hashTable = LinkedHashMap()
        ctx.state.hashTable!!["tool"] = "/bin/tool"
        val result = MoreBuiltins2.hash(ctx, listOf("-r"))
        assertEquals(0, result.exitCode)
        assertEquals(true, ctx.state.hashTable!!.isEmpty())
    }

    @Test
    fun `hash dash t prints remembered location`() {
        ctx.state.hashTable = LinkedHashMap()
        ctx.state.hashTable!!["tool"] = "/bin/tool"
        val result = MoreBuiltins2.hash(ctx, listOf("-t", "tool"))
        assertEquals(0, result.exitCode)
        assertEquals("/bin/tool\n", result.stdout)
    }

    @Test
    fun `hash dash d forgets cached location`() {
        ctx.state.hashTable = LinkedHashMap()
        ctx.state.hashTable!!["tool"] = "/bin/tool"
        val result = MoreBuiltins2.hash(ctx, listOf("-d", "tool"))
        assertEquals(0, result.exitCode)
        assertEquals(true, ctx.state.hashTable!!.isEmpty())
    }

    @Test
    fun `hash dash p uses specific path`() {
        val result = MoreBuiltins2.hash(ctx, listOf("-p", "/custom/tool", "tool"))
        assertEquals(0, result.exitCode)
        assertEquals("/custom/tool", ctx.state.hashTable!!["tool"])
    }

    @Test
    fun `hash dash l lists in reusable format`() {
        ctx.state.hashTable = LinkedHashMap()
        ctx.state.hashTable!!["tool"] = "/bin/tool"
        val result = MoreBuiltins2.hash(ctx, listOf("-l"))
        assertEquals(0, result.exitCode)
        assertEquals("builtin hash -p '/bin/tool' 'tool'\n", result.stdout)
    }

    // ---------------------------------------------------------------------
    // mapfile
    // ---------------------------------------------------------------------

    @Test
    fun `mapfile reads lines into default array`() {
        val result = MoreBuiltins2.mapfile(ctx, emptyList(), "one\ntwo\nthree\n")
        assertEquals(0, result.exitCode)
        val arr = ctx.state.arrays!!["MAPFILE"]!!
        assertEquals("one\n", arr.elements["0"])
        assertEquals("two\n", arr.elements["1"])
        assertEquals("three\n", arr.elements["2"])
    }

    @Test
    fun `mapfile strips trailing delimiter with dash t`() {
        val result = MoreBuiltins2.mapfile(ctx, listOf("-t"), "one\ntwo\n")
        assertEquals(0, result.exitCode)
        val arr = ctx.state.arrays!!["MAPFILE"]!!
        assertEquals("one", arr.elements["0"])
        assertEquals("two", arr.elements["1"])
    }

    @Test
    fun `mapfile honors max count`() {
        val result = MoreBuiltins2.mapfile(ctx, listOf("-n", "2"), "a\nb\nc\nd\n")
        assertEquals(0, result.exitCode)
        val arr = ctx.state.arrays!!["MAPFILE"]!!
        assertEquals(2, arr.elements.size)
        assertEquals("a\n", arr.elements["0"])
        assertEquals("b\n", arr.elements["1"])
    }

    @Test
    fun `mapfile honors skip count`() {
        val result = MoreBuiltins2.mapfile(ctx, listOf("-s", "1"), "a\nb\nc\n")
        assertEquals(0, result.exitCode)
        val arr = ctx.state.arrays!!["MAPFILE"]!!
        assertEquals("b\n", arr.elements["0"])
        assertEquals("c\n", arr.elements["1"])
    }

    @Test
    fun `mapfile uses custom array name`() {
        val result = MoreBuiltins2.mapfile(ctx, listOf("myarr"), "x\ny\n")
        assertEquals(0, result.exitCode)
        val arr = ctx.state.arrays!!["myarr"]!!
        assertEquals("x\n", arr.elements["0"])
        assertEquals("y\n", arr.elements["1"])
    }

    @Test
    fun `mapfile uses custom delimiter`() {
        val result = MoreBuiltins2.mapfile(ctx, listOf("-d", ",", "-t"), "a,b,c,")
        assertEquals(0, result.exitCode)
        val arr = ctx.state.arrays!!["MAPFILE"]!!
        assertEquals("a", arr.elements["0"])
        assertEquals("b", arr.elements["1"])
        assertEquals("c", arr.elements["2"])
    }

    @Test
    fun `mapfile rejects invalid array name`() {
        val result = MoreBuiltins2.mapfile(ctx, listOf("bad-name"), "a\n")
        assertEquals(1, result.exitCode)
        assertEquals("mapfile: bad-name: not a valid identifier\n", result.stderr)
    }
}
