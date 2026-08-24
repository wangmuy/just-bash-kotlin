package com.justbash.transform

import com.justbash.ast.*
import com.justbash.parser.parse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for the AST serializer and transform pipeline ports.
 */
class TransformTest {

    // === helpers ============================================================

    private fun word(value: String): WordNode = WordNode(mutableListOf(LiteralPart(value)))

    private fun simple(name: String, vararg args: String): SimpleCommandNode =
        SimpleCommandNode(
            assignments = mutableListOf(),
            name = word(name),
            args = args.mapTo(mutableListOf()) { word(it) },
            redirections = mutableListOf(),
        )

    private fun stmt(vararg pipelines: PipelineNode): StatementNode =
        StatementNode(mutableListOf(*pipelines), mutableListOf())

    // =========================================================================
    // SERIALIZE: SIMPLE COMMAND
    // =========================================================================

    @Test
    fun `serialize simple command`() {
        val script = ScriptNode(mutableListOf(stmt(PipelineNode(mutableListOf(simple("echo", "hello", "world"))))))
        assertEquals("echo hello world", Serializer.serialize(script))
    }

    @Test
    fun `serialize assignment before command`() {
        val cmd = SimpleCommandNode(
            assignments = mutableListOf(AssignmentNode("FOO", word("bar"), append = false)),
            name = word("cmd"),
            args = mutableListOf(),
            redirections = mutableListOf(),
        )
        val script = ScriptNode(mutableListOf(stmt(PipelineNode(mutableListOf(cmd)))))
        assertEquals("FOO=bar cmd", Serializer.serialize(script))
    }

    // =========================================================================
    // SERIALIZE: PIPELINE
    // =========================================================================

    @Test
    fun `serialize pipeline`() {
        val pipeline = PipelineNode(
            mutableListOf(simple("cat", "file"), simple("grep", "pattern")),
            pipeStderr = mutableListOf(false),
        )
        assertEquals("cat file | grep pattern", Serializer.serialize(ScriptNode(mutableListOf(stmt(pipeline)))))
    }

    @Test
    fun `serialize negated timed pipeline`() {
        val pipeline = PipelineNode(
            mutableListOf(simple("cmd")),
            negated = true,
            timed = true,
        )
        assertEquals("time ! cmd", Serializer.serialize(ScriptNode(mutableListOf(stmt(pipeline)))))
    }

    @Test
    fun `serialize pipeline with operators`() {
        val stmt = StatementNode(
            mutableListOf(
                PipelineNode(mutableListOf(simple("a"))),
                PipelineNode(mutableListOf(simple("b"))),
                PipelineNode(mutableListOf(simple("c"))),
            ),
            mutableListOf("&&", ";"),
        )
        assertEquals("a && b ; c", Serializer.serialize(ScriptNode(mutableListOf(stmt))))
    }

    // =========================================================================
    // SERIALIZE: COMPOUND COMMANDS
    // =========================================================================

    @Test
    fun `serialize if else`() {
        val ifNode = IfNode(
            clauses = mutableListOf(
                IfClause(mutableListOf(stmt(PipelineNode(mutableListOf(simple("true"))))), mutableListOf(stmt(PipelineNode(mutableListOf(simple("echo", "yes")))))),
            ),
            elseBody = mutableListOf(stmt(PipelineNode(mutableListOf(simple("echo", "no"))))),
        )
        val script = ScriptNode(mutableListOf(stmt(PipelineNode(mutableListOf(ifNode)))))
        val expected = "if true; then\necho yes\nelse\necho no\nfi"
        assertEquals(expected, Serializer.serialize(script))
    }

    @Test
    fun `serialize for loop`() {
        val forNode = ForNode(
            variable = "i",
            words = mutableListOf(word("1"), word("2")),
            body = mutableListOf(stmt(PipelineNode(mutableListOf(simple("echo", "\$i"))))),
        )
        val script = ScriptNode(mutableListOf(stmt(PipelineNode(mutableListOf(forNode)))))
        assertEquals("for i in 1 2; do\necho \$i\ndone", Serializer.serialize(script))
    }

    @Test
    fun `serialize while loop`() {
        val whileNode = WhileNode(
            condition = mutableListOf(stmt(PipelineNode(mutableListOf(simple("true"))))),
            body = mutableListOf(stmt(PipelineNode(mutableListOf(simple("echo", "loop"))))),
        )
        val script = ScriptNode(mutableListOf(stmt(PipelineNode(mutableListOf(whileNode)))))
        assertEquals("while true; do\necho loop\ndone", Serializer.serialize(script))
    }

    // =========================================================================
    // SERIALIZE: REDIRECTIONS & HEREDOC
    // =========================================================================

    @Test
    fun `serialize redirections`() {
        val cmd = SimpleCommandNode(
            assignments = mutableListOf(),
            name = word("echo"),
            args = mutableListOf(word("hi")),
            redirections = mutableListOf(
                RedirectionNode(fd = 1, operator = ">", target = word("out.txt")),
                RedirectionNode(fd = 2, operator = ">&", target = word("1")),
            ),
        )
        assertEquals("echo hi 1> out.txt 2>& 1", Serializer.serialize(ScriptNode(mutableListOf(stmt(PipelineNode(mutableListOf(cmd)))))))
    }

    @Test
    fun `serialize here-doc`() {
        val content = word("hello world")
        val heredoc = HereDocNode(
            delimiter = "EOF",
            content = content,
            stripTabs = false,
            quoted = false,
            terminated = true,
        )
        val cmd = SimpleCommandNode(
            assignments = mutableListOf(),
            name = word("cat"),
            args = mutableListOf(),
            redirections = mutableListOf(RedirectionNode(fd = 0, operator = "<<", target = heredoc)),
        )
        assertEquals("cat <<EOF\nhello worldEOF", Serializer.serialize(ScriptNode(mutableListOf(stmt(PipelineNode(mutableListOf(cmd)))))))
    }

    // =========================================================================
    // SERIALIZE: ARITHMETIC & CONDITIONAL
    // =========================================================================

    @Test
    fun `serialize arithmetic command`() {
        val expr = ArithmeticExpressionNode(
            ArithBinaryNode("+", ArithNumberNode(1), ArithNumberNode(2)),
        )
        val cmd = ArithmeticCommandNode(expr)
        assertEquals("((1 + 2))", Serializer.serialize(ScriptNode(mutableListOf(stmt(PipelineNode(mutableListOf(cmd)))))))
    }

    @Test
    fun `serialize conditional command`() {
        val expr = CondBinaryNode("==", word("\$a"), word("1"))
        val cmd = ConditionalCommandNode(expr)
        val serialized = Serializer.serialize(ScriptNode(mutableListOf(stmt(PipelineNode(mutableListOf(cmd)))))
        )
        assertEquals("[[ \$a == 1 ]]", serialized)
    }

    // =========================================================================
    // serializeWord
    // =========================================================================

    @Test
    fun `serializeWord mixed parts`() {
        val w = WordNode(
            mutableListOf(
                LiteralPart("foo"),
                SingleQuotedPart("a b"),
                DoubleQuotedPart(mutableListOf(ParameterExpansionPart("VAR", null), LiteralPart("x"))),
                EscapedPart("$"),
            ),
        )
        assertEquals("foo'a b'\"\$VARx\"\\\$", Serializer.serializeWord(w))
    }

    @Test
    fun `serializeWord tilde and glob`() {
        val w = WordNode(mutableListOf(TildeExpansionPart(null), GlobPart("*.txt")))
        assertEquals("~*.txt", Serializer.serializeWord(w))
    }

    // =========================================================================
    // CommandCollectorPlugin
    // =========================================================================

    @Test
    fun `CommandCollector collects commands from script`() {
        val script = parse("ls -la && grep foo | sort")
        val context = TransformContext(script, mutableMapOf())
        val result = CommandCollectorPlugin().transform(context)
        val commands = result.metadata!!["commands"] as List<String>
        assertTrue("ls" in commands)
        assertTrue("grep" in commands)
        assertTrue("sort" in commands)
    }

    @Test
    fun `CommandCollector deduplicates and sorts`() {
        val script = parse("echo a; echo b; ls")
        val result = CommandCollectorPlugin().transform(TransformContext(script, mutableMapOf()))
        assertEquals(listOf("echo", "ls"), result.metadata!!["commands"])
    }

    // =========================================================================
    // TeePlugin
    // =========================================================================

    @Test
    fun `TeePlugin wraps commands in pipeline`() {
        val script = parse("echo hi | grep h")
        val plugin = TeePlugin(TeePluginOptions(outputDir = "/tmp/out"))
        val result = plugin.transform(TransformContext(script, mutableMapOf()))
        val teeFiles = result.metadata!!["teeFiles"] as List<TeeFileInfo>
        assertEquals(2, teeFiles.size)
        assertTrue(teeFiles.all { it.stdoutFile.startsWith("/tmp/out/") })
    }

    // =========================================================================
    // BashTransformPipeline
    // =========================================================================

    @Test
    fun `pipeline dot transform produces output script`() {
        val pipeline = BashTransformPipeline()
        pipeline.use(CommandCollectorPlugin())
        val result = pipeline.transform("echo hello world")
        assertEquals("echo hello world", result.script)
        assertEquals(listOf("echo"), result.metadata["commands"])
    }

    // =========================================================================
    // Round-trip parse -> serialize -> parse
    // =========================================================================

    @Test
    fun `serialize then parse round-trip`() {
        val original = "a=1\nfor i in 1 2; do\n  echo \"\$i\"\ndone"
        val first = parse(original)
        val serialized = Serializer.serialize(first)
        val second = parse(serialized)
        // The re-parsed script must still serialize to the same text.
        assertEquals(serialized, Serializer.serialize(second))
    }
}
