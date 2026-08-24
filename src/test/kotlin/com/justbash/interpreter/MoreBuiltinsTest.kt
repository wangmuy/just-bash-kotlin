package com.justbash.interpreter

import com.justbash.Command
import com.justbash.fs.FsInit
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MoreBuiltinsTest {

    private lateinit var fs: InMemoryFs
    private lateinit var interpreter: Interpreter
    private lateinit var ctx: InterpreterContext

    @BeforeEach
    fun setup() {
        fs = InMemoryFs()
        FsInit.initCommonDirectories(fs, useDefaultLayout = true)
        fs.writeFile("/home/user/file1.txt", "")
        fs.writeFile("/home/user/file2.log", "")
        fs.mkdir("/home/user/dirA")
        fs.mkdir("/home/user/dirB")
        val commands = HashMap<String, Command>()
        commands["echo"] = object : Command {
            override val name = "echo"
            override suspend fun execute(args: List<String>, ctx: com.justbash.CommandContext) =
                com.justbash.ExecResult(args.joinToString(" "), "", 0)
        }
        interpreter = Interpreter(fs, commands, InterpreterState().apply {
            env["HOME"] = "/home/user"
            env["VAR_ONE"] = "1"
            env["VAR_TWO"] = "2"
            env["other"] = "3"
            cwd = "/home/user"
        })
        ctx = interpreter.ctx
    }

    // ─── shopt ───────────────────────────────────────────────────────────────

    @Test
    fun `shopt lists all options with on off status`() {
        val out = MoreBuiltins.shopt(ctx, emptyList()).stdout
        assertTrue(out.contains("globskipdots\t\ton"))
        assertTrue(out.contains("extglob\t\toff"))
        assertTrue(out.contains("xpg_echo\t\toff"))
        // all 11 options present
        for (opt in MoreBuiltins.SHOPT_OPTIONS) assertTrue(out.contains(opt), "missing $opt")
    }

    @Test
    fun `shopt -s enables an option`() {
        val result = MoreBuiltins.shopt(ctx, listOf("-s", "extglob"))
        assertEquals(0, result.exitCode)
        assertTrue(ctx.state.shoptOptions.extglob)
    }

    @Test
    fun `shopt -u disables an option`() {
        assertTrue(ctx.state.shoptOptions.globskipdots)
        val result = MoreBuiltins.shopt(ctx, listOf("-u", "globskipdots"))
        assertEquals(0, result.exitCode)
        assertFalse(ctx.state.shoptOptions.globskipdots)
    }

    @Test
    fun `shopt -p prints in settable format`() {
        val out = MoreBuiltins.shopt(ctx, listOf("-p", "globskipdots")).stdout
        assertEquals("shopt -s globskipdots\n", out)
    }

    @Test
    fun `shopt -q returns exit code 0 if set and 1 if unset`() {
        assertEquals(0, MoreBuiltins.shopt(ctx, listOf("-q", "globskipdots")).exitCode)
        assertEquals(1, MoreBuiltins.shopt(ctx, listOf("-q", "extglob")).exitCode)
        // quiet mode produces no output
        assertEquals("", MoreBuiltins.shopt(ctx, listOf("-q", "globskipdots")).stdout)
    }

    @Test
    fun `shopt invalid option name reports error`() {
        val result = MoreBuiltins.shopt(ctx, listOf("nonexistent_opt"))
        assertEquals(1, result.exitCode)
        assertEquals("shopt: nonexistent_opt: invalid shell option name\n", result.stderr)
    }

    @Test
    fun `shopt -o maps to set options`() {
        val result = MoreBuiltins.shopt(ctx, listOf("-s", "-o", "pipefail"))
        assertEquals(0, result.exitCode)
        assertTrue(ctx.state.options.pipefail)
        val out = MoreBuiltins.shopt(ctx, listOf("-o", "-p", "pipefail")).stdout
        assertEquals("set -o pipefail\n", out)
    }

    // ─── dirs ────────────────────────────────────────────────────────────────

    @Test
    fun `dirs prints current directory when stack is empty`() {
        val out = MoreBuiltins.dirs(ctx, emptyList()).stdout
        assertEquals("~\n", out)
    }

    @Test
    fun `dirs prints the directory stack`() {
        ctx.state.directoryStack = mutableListOf("/tmp", "/var")
        val out = MoreBuiltins.dirs(ctx, emptyList()).stdout
        assertEquals("~ /tmp /var\n", out)
    }

    @Test
    fun `dirs -v prints stack with index numbers`() {
        ctx.state.directoryStack = mutableListOf("/tmp", "/var")
        val out = MoreBuiltins.dirs(ctx, listOf("-v")).stdout
        assertEquals(" 0  ~\n 1  /tmp\n 2  /var\n", out)
    }

    @Test
    fun `dirs -p prints one entry per line`() {
        ctx.state.directoryStack = mutableListOf("/tmp", "/var")
        val out = MoreBuiltins.dirs(ctx, listOf("-p")).stdout
        assertEquals("~\n/tmp\n/var\n", out)
    }

    @Test
    fun `dirs -c clears the stack`() {
        ctx.state.directoryStack = mutableListOf("/tmp")
        val result = MoreBuiltins.dirs(ctx, listOf("-c"))
        assertEquals(0, result.exitCode)
        assertTrue(ctx.state.directoryStack!!.isEmpty())
    }

    @Test
    fun `dirs +N displays the Nth entry`() {
        ctx.state.directoryStack = mutableListOf("/tmp", "/var")
        assertEquals("/tmp\n", MoreBuiltins.dirs(ctx, listOf("+1")).stdout)
    }

    @Test
    fun `dirs -N displays the Nth entry from the end`() {
        ctx.state.directoryStack = mutableListOf("/tmp", "/var")
        assertEquals("/var\n", MoreBuiltins.dirs(ctx, listOf("-1")).stdout)
    }

    @Test
    fun `dirs -l shows expanded home instead of tilde`() {
        val out = MoreBuiltins.dirs(ctx, listOf("-l")).stdout
        assertEquals("/home/user\n", out)
    }

    // ─── complete ────────────────────────────────────────────────────────────

    @Test
    fun `complete -W sets a word list completion`() {
        val result = MoreBuiltins.complete(ctx, listOf("-W", "foo bar baz", "mycmd"))
        assertEquals(0, result.exitCode)
        assertEquals("foo bar baz", ctx.state.completions["mycmd"])
    }

    @Test
    fun `complete lists all completions`() {
        ctx.state.completions["mycmd"] = "foo bar baz"
        ctx.state.completions["othercmd"] = "x y"
        val out = MoreBuiltins.complete(ctx, emptyList()).stdout
        assertTrue(out.contains("complete -W foo bar baz mycmd"))
        assertTrue(out.contains("complete -W x y othercmd"))
    }

    @Test
    fun `complete -p prints completions`() {
        ctx.state.completions["mycmd"] = "foo bar"
        val out = MoreBuiltins.complete(ctx, listOf("-p")).stdout
        assertEquals("complete -W foo bar mycmd\n", out)
    }

    @Test
    fun `complete -r cmd removes one completion`() {
        ctx.state.completions["mycmd"] = "foo"
        ctx.state.completions["other"] = "bar"
        MoreBuiltins.complete(ctx, listOf("-r", "mycmd"))
        assertFalse(ctx.state.completions.containsKey("mycmd"))
        assertTrue(ctx.state.completions.containsKey("other"))
    }

    @Test
    fun `complete -r removes all completions`() {
        ctx.state.completions["mycmd"] = "foo"
        ctx.state.completions["other"] = "bar"
        MoreBuiltins.complete(ctx, listOf("-r"))
        assertTrue(ctx.state.completions.isEmpty())
    }

    // ─── compgen ─────────────────────────────────────────────────────────────

    @Test
    fun `compgen -v lists variable names filtering by prefix`() {
        val out = MoreBuiltins.compgen(ctx, listOf("-v", "VAR")).stdout
        assertEquals("VAR_ONE\nVAR_TWO\n", out)
    }

    @Test
    fun `compgen -A keyword lists shell keywords matching prefix`() {
        val out = MoreBuiltins.compgen(ctx, listOf("-A", "keyword", "wh")).stdout
        assertEquals("while\n", out)
    }

    @Test
    fun `compgen -A builtin lists builtin names matching prefix`() {
        val out = MoreBuiltins.compgen(ctx, listOf("-A", "builtin", "ec")).stdout
        assertEquals("echo\n", out)
    }

    @Test
    fun `compgen -A shopt lists shopt option names matching prefix`() {
        val out = MoreBuiltins.compgen(ctx, listOf("-A", "shopt", "glob")).stdout
        assertTrue(out.contains("globasciiranges"))
        assertTrue(out.contains("globstar"))
    }

    @Test
    fun `compgen -A function lists function names`() {
        ctx.state.functions["myfunc"] = com.justbash.ast.FunctionDefNode("myfunc", com.justbash.ast.IfNode())
        val out = MoreBuiltins.compgen(ctx, listOf("-A", "function", "my")).stdout
        assertEquals("myfunc\n", out)
    }

    @Test
    fun `compgen -W filters a wordlist by prefix`() {
        val out = MoreBuiltins.compgen(ctx, listOf("-W", "apple banana apricot", "ap")).stdout
        assertEquals("apple\napricot\n", out)
    }

    @Test
    fun `compgen -f lists files matching prefix`() {
        val out = MoreBuiltins.compgen(ctx, listOf("-f", "file")).stdout
        assertEquals("file1.txt\nfile2.log\n", out)
    }

    @Test
    fun `compgen -d lists directories only`() {
        val out = MoreBuiltins.compgen(ctx, listOf("-d")).stdout
        assertEquals("dirA\ndirB\n", out)
    }

    @Test
    fun `compgen -e lists exported variables`() {
        ctx.state.exportedVars = linkedSetOf("VAR_ONE")
        val out = MoreBuiltins.compgen(ctx, listOf("-e")).stdout
        assertEquals("VAR_ONE\n", out)
    }

    @Test
    fun `compgen -P and -S wrap each completion`() {
        val out = MoreBuiltins.compgen(ctx, listOf("-W", "one two", "-P", "<", "-S", ">")).stdout
        assertEquals("<one>\n<two>\n", out)
    }

    @Test
    fun `compgen empty result with prefix returns exit 1`() {
        val result = MoreBuiltins.compgen(ctx, listOf("-v", "ZZZ_NOMATCH"))
        assertEquals(1, result.exitCode)
    }

    // ─── compopt ─────────────────────────────────────────────────────────────

    @Test
    fun `compopt modifies completion options for a command`() {
        val result = MoreBuiltins.compopt(ctx, listOf("-o", "nospace", "mycmd"))
        assertEquals(0, result.exitCode)
        assertEquals(setOf("nospace"), ctx.state.completionOptions["mycmd"])
    }

    @Test
    fun `compopt +o disables an option`() {
        ctx.state.completionOptions["mycmd"] = linkedSetOf("nospace", "filenames")
        MoreBuiltins.compopt(ctx, listOf("+o", "nospace", "mycmd"))
        assertEquals(setOf("filenames"), ctx.state.completionOptions["mycmd"])
    }

    @Test
    fun `compopt invalid option returns error`() {
        val result = MoreBuiltins.compopt(ctx, listOf("-o", "badopt", "mycmd"))
        assertEquals(2, result.exitCode)
        assertEquals("compopt: badopt: invalid option name\n", result.stderr)
    }

    @Test
    fun `compopt without command returns not in completion function error`() {
        val result = MoreBuiltins.compopt(ctx, listOf("-o", "nospace"))
        assertEquals(1, result.exitCode)
        assertEquals("compopt: not currently executing completion function\n", result.stderr)
    }
}