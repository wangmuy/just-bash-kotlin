package com.justbash.interpreter

import com.justbash.BashExecResult
import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import com.justbash.ast.*
import com.justbash.fs.FsInit
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

class InterpreterTest {

    private lateinit var fs: InMemoryFs
    private lateinit var interpreter: Interpreter

    @BeforeEach
    fun setup() {
        fs = InMemoryFs()
        FsInit.initCommonDirectories(fs, useDefaultLayout = true)
        val commands = HashMap<String, Command>()
        commands["echo"] = object : Command {
            override val name = "echo"
            override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
                return ExecResult(args.joinToString(" "), "", 0)
            }
        }
        interpreter = Interpreter(fs, commands)
    }

    private fun exec(script: String): BashExecResult = runBlocking { interpreter.exec(script) }

    @Test
    fun `executes a simple echo command`() {
        val result = exec("echo hello world")
        assertEquals("hello world", result.stdout)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `variable assignment stores in env`() {
        val result = exec("x=42")
        assertEquals(0, result.exitCode)
        assertEquals("42", interpreter.ctx.state.env["x"])
    }

    @Test
    fun `empty script returns success`() {
        val result = exec("")
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `command not found returns 127`() {
        val result = exec("nonexistent_command_xyz")
        assertEquals(127, result.exitCode)
        assertEquals("bash: nonexistent_command_xyz: command not found\n", result.stderr)
    }

    @Test
    fun `redirect to file writes content`() {
        val result = exec("echo hi > /tmp/out.txt")
        assertEquals(0, result.exitCode)
        assertEquals("hi", fs.readFile("/tmp/out.txt"))
    }

    @Test
    fun `exit code propagates through script`() {
        val result = exec("exit 7")
        assertEquals(7, result.exitCode)
    }

    @Test
    fun `test builtin -z`() {
        val result = exec("test -z \"\"")
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `executeScript with a script AST`() {
        val script = ScriptNode(mutableListOf())
        val result = runBlocking { interpreter.executeScript(script) }
        assertEquals(0, result.exitCode)
        assertEquals("", result.stdout)
    }

    @Test
    fun `if then else via AST executes true branch`() {
        // Build an if statement AST: if true; then echo yes; fi
        val trueCmd = SimpleCommandNode().apply { name = word("true") }
        val condPipeline = PipelineNode(mutableListOf(trueCmd))
        val condStmt = StatementNode(mutableListOf(condPipeline))
        val echoCmd = SimpleCommandNode().apply { name = word("echo"); args.add(word("yes")) }
        val bodyPipeline = PipelineNode(mutableListOf(echoCmd))
        val bodyStmt = StatementNode(mutableListOf(bodyPipeline))
        val ifNode = IfNode(mutableListOf(IfClause(mutableListOf(condStmt), mutableListOf(bodyStmt))))
        val script = ScriptNode(mutableListOf(StatementNode(mutableListOf(PipelineNode(mutableListOf(ifNode))))))
        val result = runBlocking { interpreter.executeScript(script) }
        assertEquals(0, result.exitCode)
        assertEquals("yes", result.stdout)
    }

    @Test
    fun `for loop iterates over words via AST`() {
        // Build: for x in 1 2 3; do echo hi; done  -> assigns x each iteration
        val echoCmd = SimpleCommandNode().apply { name = word("echo"); args.add(word("hi")) }
        val bodyStmt = StatementNode(mutableListOf(PipelineNode(mutableListOf(echoCmd))))
        val forNode = ForNode("x", mutableListOf(word("1"), word("2"), word("3")), mutableListOf(bodyStmt))
        val script = ScriptNode(mutableListOf(StatementNode(mutableListOf(PipelineNode(mutableListOf(forNode))))))
        val result = runBlocking { interpreter.executeScript(script) }
        assertEquals(0, result.exitCode)
        assertEquals("hihihi", result.stdout)
        assertEquals("3", interpreter.ctx.state.env["x"])
    }

    private fun word(text: String): WordNode = WordNode(LiteralPart(text))
}
