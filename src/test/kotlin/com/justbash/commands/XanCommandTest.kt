package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for the xan CSV toolkit command.
 */
class XanCommandTest {

    private fun ctx(
        fs: InMemoryFs = InMemoryFs(),
        cwd: String = "/home/user",
        stdin: String = "",
        env: MutableMap<String, String> = LinkedHashMap<String, String>().also {
            it["PATH"] = "/usr/bin:/bin"
            it["HOME"] = "/home/user"
        },
    ): CommandContext {
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        return CommandContext(fs = fs, cwd = cwd, env = env, stdin = stdin.toByteArray(Charsets.UTF_8))
    }

    private fun run(sub: String, args: List<String>, input: String = ""): com.justbash.ExecResult {
        return runBlocking { XanCommand.execute(listOf(sub) + args, ctx(stdin = input)) }
    }

    private val sampleCsv = "name,age,city\nAlice,30,NYC\nBob,25,LA\nCharlie,35,NYC\n"

    // ---- cat ----

    @Test
    fun `cat outputs csv as-is`() {
        val r = run("cat", emptyList(), sampleCsv)
        assertEquals(sampleCsv, r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- count ----

    @Test
    fun `count counts data rows`() {
        val r = run("count", emptyList(), sampleCsv)
        assertEquals("3\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- head ----

    @Test
    fun `head shows first N rows`() {
        val r = run("head", listOf("-n", "2"), sampleCsv)
        assertEquals("name,age,city\nAlice,30,NYC\nBob,25,LA\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- tail ----

    @Test
    fun `tail shows last N rows`() {
        val r = run("tail", listOf("-n", "2"), sampleCsv)
        assertEquals("name,age,city\nBob,25,LA\nCharlie,35,NYC\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- select ----

    @Test
    fun `select specific columns`() {
        val r = run("select", listOf("name,city"), sampleCsv)
        assertEquals("name,city\nAlice,NYC\nBob,LA\nCharlie,NYC\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- filter ----

    @Test
    fun `filter by equality`() {
        val r = run("filter", listOf("city", "==", "NYC"), sampleCsv)
        assertEquals("name,age,city\nAlice,30,NYC\nCharlie,35,NYC\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `filter by numeric comparison`() {
        val r = run("filter", listOf("age", ">", "28"), sampleCsv)
        assertEquals("name,age,city\nAlice,30,NYC\nCharlie,35,NYC\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- map ----

    @Test
    fun `map adds new column from literal`() {
        val r = run("map", listOf("country", "US"), sampleCsv)
        assertEquals("name,age,city,country\nAlice,30,NYC,US\nBob,25,LA,US\nCharlie,35,NYC,US\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `map concatenates with column ref`() {
        val r = run("map", listOf("label", "{name}+\"!\"", ), sampleCsv)
        assertEquals("name,age,city,label\nAlice,30,NYC,Alice!\nBob,25,LA,Bob!\nCharlie,35,NYC,Charlie!\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- sort ----

    @Test
    fun `sort by column numeric`() {
        val r = run("sort", listOf("-n", "age"), sampleCsv)
        assertEquals("name,age,city\nBob,25,LA\nAlice,30,NYC\nCharlie,35,NYC\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `sort reverse`() {
        val r = run("sort", listOf("-n", "-r", "age"), sampleCsv)
        assertEquals("name,age,city\nCharlie,35,NYC\nAlice,30,NYC\nBob,25,LA\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- dedup ----

    @Test
    fun `dedup removes duplicates`() {
        val input = "name,age\nAlice,30\nBob,25\nAlice,30\n"
        val r = run("dedup", emptyList(), input)
        assertEquals("name,age\nAlice,30\nBob,25\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `dedup by specific column`() {
        val input = "name,city\nAlice,NYC\nBob,NYC\nAlice,LA\n"
        val r = run("dedup", listOf("-s", "city"), input)
        assertEquals("name,city\nAlice,NYC\nAlice,LA\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- stats ----

    @Test
    fun `stats shows column statistics`() {
        val input = "age,name\n10,a\n20,b\n30,c\n,d\n"
        val r = run("stats", listOf("-s", "age"), input)
        // field,type,count,min,max,mean,stddev,nulls
        val lines = r.stdout.trim().lines()
        assertEquals(2, lines.size) // header + one data row
        assertEquals("field,type,count,min,max,mean,stddev,nulls", lines[0])
        val fields = lines[1].split(",")
        assertEquals("age", fields[0])
        assertEquals("Number", fields[1])
        assertEquals("3", fields[2])
        assertEquals("10", fields[3])
        assertEquals("30", fields[4])
        assertEquals("1", fields[7]) // one null (empty cell)
        assertEquals(0, r.exitCode)
    }

    // ---- frequency ----

    @Test
    fun `frequency builds value count table`() {
        val r = run("frequency", listOf("city"), sampleCsv)
        assertEquals("field,value,count\ncity,NYC,2\ncity,LA,1\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- agg ----

    @Test
    fun `agg groups and sums`() {
        val input = "name,city,sales\nAlice,NYC,100\nBob,LA,200\nCharlie,NYC,300\n"
        val r = run("agg", listOf("-g", "city", "-s", "sales", "sum"), input)
        assertEquals("city,sum_sales\nNYC,400\nLA,200\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- rename ----

    @Test
    fun `rename renames a column`() {
        val r = run("rename", listOf("city", "location"), sampleCsv)
        assertEquals("name,age,location\nAlice,30,NYC\nBob,25,LA\nCharlie,35,NYC\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- search ----

    @Test
    fun `search finds matching pattern`() {
        val r = run("search", listOf("NYC"), sampleCsv)
        assertEquals("name,age,city\nAlice,30,NYC\nCharlie,35,NYC\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `search inverts match`() {
        val r = run("search", listOf("-v", "NYC"), sampleCsv)
        assertEquals("name,age,city\nBob,25,LA\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- view ----

    @Test
    fun `view pretty prints table`() {
        val r = run("view", listOf("-n", "1"), sampleCsv)
        val expected = "┌───────┬─────┬──────┐\n" +
            "│ name  │ age │ city │\n" +
            "├───────┼─────┼──────┤\n" +
            "│ Alice │ 30  │ NYC  │\n" +
            "└───────┴─────┴──────┘\n"
        assertEquals(expected, r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- unknown command ----

    @Test
    fun `unknown command errors`() {
        val r = runBlocking { XanCommand.execute(listOf("bogus"), ctx()) }
        assertEquals(1, r.exitCode)
        assertEquals("xan: unknown command 'bogus'\nRun 'xan --help' for usage.\n", r.stderr)
    }

    // ---- file input ----

    @Test
    fun `reads CSV from file`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/data.csv", sampleCsv)
        val r = runBlocking { XanCommand.execute(listOf("count", "data.csv"), ctx(fs = fs)) }
        assertEquals("3\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- no-headers ----

    @Test
    fun `no headers generates synthetic headers`() {
        val input = "Alice,30,NYC\nBob,25,LA\n"
        val r = run("count", listOf("--no-headers"), input)
        assertEquals("2\n", r.stdout)
        assertEquals(0, r.exitCode)
    }
}