package com.justbash.transform

import com.justbash.ast.*
import com.justbash.fs.PathUtils
import java.time.Instant

/**
 * Transform plugins.
 *
 * Port of just-bash `src/transform/plugins/command-collector.ts` and
 * `src/transform/plugins/tee-plugin.ts`.
 */

// ===========================================================================
// CommandCollectorPlugin
// ===========================================================================

/** Metadata produced by [CommandCollectorPlugin]. */
data class CommandCollectorMetadata(val commands: List<String>)

/**
 * Walks an AST and collects all command names, exposed under the `commands`
 * metadata key (sorted, de-duplicated).
 */
class CommandCollectorPlugin : TransformPlugin {
    override val name = "command-collector"

    override val transform: (TransformContext) -> TransformResult = { context ->
        val commands = sortedSetOf<String>()
        walkScript(context.ast, commands)
        TransformResult(
            ast = context.ast,
            metadata = mutableMapOf("commands" to commands.toList()),
        )
    }

    private fun walkScript(node: ScriptNode, commands: MutableSet<String>) {
        node.statements.forEach { walkStatement(it, commands) }
    }

    private fun walkStatement(node: StatementNode, commands: MutableSet<String>) {
        node.pipelines.forEach { walkPipeline(it, commands) }
    }

    private fun walkPipeline(node: PipelineNode, commands: MutableSet<String>) {
        node.commands.forEach { walkCommand(it, commands) }
    }

    private fun walkCommand(node: CommandNode, commands: MutableSet<String>) {
        when (node) {
            is SimpleCommandNode -> {
                node.name?.let {
                    extractName(it)?.let { n -> commands.add(n) }
                    walkWordParts(it.parts, commands)
                }
                node.args.forEach { walkWordParts(it.parts, commands) }
                node.assignments.forEach { assign ->
                    assign.value?.let { walkWordParts(it.parts, commands) }
                    assign.array?.forEach { walkWordParts(it.parts, commands) }
                }
            }
            is IfNode -> {
                node.clauses.forEach { clause ->
                    clause.condition.forEach { walkStatement(it, commands) }
                    clause.body.forEach { walkStatement(it, commands) }
                }
                node.elseBody?.forEach { walkStatement(it, commands) }
            }
            is ForNode -> {
                node.words?.forEach { walkWordParts(it.parts, commands) }
                node.body.forEach { walkStatement(it, commands) }
            }
            is CStyleForNode -> {
                node.body.forEach { walkStatement(it, commands) }
            }
            is WhileNode -> {
                node.condition.forEach { walkStatement(it, commands) }
                node.body.forEach { walkStatement(it, commands) }
            }
            is UntilNode -> {
                node.condition.forEach { walkStatement(it, commands) }
                node.body.forEach { walkStatement(it, commands) }
            }
            is CaseNode -> {
                walkWordParts(node.word.parts, commands)
                node.items.forEach { item ->
                    item.body.forEach { walkStatement(it, commands) }
                }
            }
            is SubshellNode -> node.body.forEach { walkStatement(it, commands) }
            is GroupNode -> node.body.forEach { walkStatement(it, commands) }
            is FunctionDefNode -> walkCommand(node.body, commands)
            is ArithmeticCommandNode, is ConditionalCommandNode -> Unit
            else -> Unit
        }
    }

    private fun walkWordParts(parts: List<WordPart>, commands: MutableSet<String>) {
        parts.forEach { part ->
            when (part) {
                is CommandSubstitutionPart -> walkScript(part.body, commands)
                is ProcessSubstitutionPart -> walkScript(part.body, commands)
                is DoubleQuotedPart -> walkWordParts(part.parts, commands)
                is ParameterExpansionPart -> part.operation?.let { walkParameterOp(it, commands) }
                else -> Unit
            }
        }
    }

    private fun walkParameterOp(op: ParameterOperation, commands: MutableSet<String>) {
        when (op) {
            is DefaultValueOp -> walkWordParts(op.word.parts, commands)
            is AssignDefaultOp -> walkWordParts(op.word.parts, commands)
            is UseAlternativeOp -> walkWordParts(op.word.parts, commands)
            is ErrorIfUnsetOp -> op.word?.let { walkWordParts(it.parts, commands) }
            is PatternRemovalOp -> walkWordParts(op.pattern.parts, commands)
            is PatternReplacementOp -> {
                walkWordParts(op.pattern.parts, commands)
                op.replacement?.let { walkWordParts(it.parts, commands) }
            }
            is CaseModificationOp -> op.pattern?.let { walkWordParts(it.parts, commands) }
            is IndirectionOp -> op.innerOp?.let { walkParameterOp(it, commands) }
            else -> Unit
        }
    }

    private fun extractName(word: WordNode): String? {
        if (word.parts.size == 1 && word.parts[0] is LiteralPart) {
            return (word.parts[0] as LiteralPart).value
        }
        return null
    }
}

// ===========================================================================
// TeePlugin
// ===========================================================================

/** Options for [TeePlugin]. */
data class TeePluginOptions(
    val outputDir: String,
    val targetCommandPattern: Regex? = null,
    val timestamp: Instant? = null,
)

/** Metadata describing a tee-wrapped command's output file. */
data class TeeFileInfo(
    val commandIndex: Int,
    val commandName: String,
    val command: String,
    val stdoutFile: String,
)

/** Metadata produced by [TeePlugin]. */
data class TeePluginMetadata(val teeFiles: List<TeeFileInfo>)

/**
 * Wraps targeted simple commands inside multi-command pipelines with `tee`,
 * redirecting their stdout (and stderr when piped with `|&`) to per-command
 * output files, while restoring the original pipeline status via a
 * `__just_bash_tee_restore` builtin.
 */
class TeePlugin(private val options: TeePluginOptions) : TransformPlugin {
    override val name = "tee"

    private var counter = 0

    override val transform: (TransformContext) -> TransformResult = { context ->
        val teeFiles = mutableListOf<TeeFileInfo>()
        val timestamp = options.timestamp ?: Instant.now()
        val ast = transformScript(context.ast, teeFiles, timestamp)
        TransformResult(
            ast = ast,
            metadata = mutableMapOf("teeFiles" to teeFiles.toList()),
        )
    }

    private fun formatTimestamp(instant: Instant): String =
        instant.toString().replace(":", "-")

    private fun generateStdoutPath(
        index: Int,
        commandName: String,
        timestamp: Instant,
    ): String {
        val ts = formatTimestamp(timestamp)
        val idx = index.toString().padStart(3, '0')
        PathUtils.validatePath(options.outputDir, "create tee output")
        if (!options.outputDir.startsWith("/") ||
            options.outputDir.split("/").contains("..")
        ) {
            throw IllegalArgumentException("tee output directory must be an absolute safe path")
        }
        val dir = PathUtils.normalizePath(options.outputDir)
        val encodedCommandName = encodeCommandName(commandName)
        val candidate = PathUtils.normalizePath("$dir/$ts-$idx-$encodedCommandName.stdout.txt")
        if (dir != "/" && !candidate.startsWith("$dir/")) {
            throw IllegalArgumentException("tee output path escapes configured output directory")
        }
        return candidate
    }

    private fun encodeCommandName(commandName: String): String {
        if (EncodingSafeName.matches(commandName)) {
            return commandName
        }

        // FNV-1a gives unsafe or very long names a deterministic
        // collision-resistant suffix.
        var hash = 0x811c9dc5L
        for (element in commandName) {
            hash = hash xor element.code.toLong()
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        val slug = commandName
            .replace(UNSAFE_CHARS, "_")
            .replace(LEADING_TRAILING_UNDERSCORES, "")
            .take(32)
            .ifEmpty { "command" }
        val hex = hash.toString(16).padStart(8, '0')
        return "$slug-$hex"
    }

    private val EncodingSafeName = Regex("""^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$""")
    private val UNSAFE_CHARS = Regex("""[^A-Za-z0-9_-]""")
    private val LEADING_TRAILING_UNDERSCORES = Regex("""^_+|_+$""")

    private fun transformScript(
        node: ScriptNode,
        teeFiles: MutableList<TeeFileInfo>,
        timestamp: Instant,
    ): ScriptNode {
        return ScriptNode(
            node.statements.mapTo(mutableListOf()) { transformStatement(it, teeFiles, timestamp) },
        )
    }

    private fun transformStatement(
        node: StatementNode,
        teeFiles: MutableList<TeeFileInfo>,
        timestamp: Instant,
    ): StatementNode {
        val newPipelines = mutableListOf<PipelineNode>()
        val newOperators = mutableListOf<String>()

        for (i in node.pipelines.indices) {
            val pipeline = node.pipelines[i]
            if (i > 0) {
                newOperators.add(node.operators[i - 1])
            }

            val result = transformPipeline(pipeline, teeFiles, timestamp)
            newPipelines.add(result.pipeline)

            if (result.origCmdNewIndices != null) {
                newOperators.add(";")
                newPipelines.add(makePipestatusRestore(result.origCmdNewIndices, result.negated))
            }
        }

        return StatementNode(
            pipelines = newPipelines,
            operators = newOperators,
            background = node.background,
            deferredError = node.deferredError,
            sourceText = node.sourceText,
        )
    }

    private data class PipelineTransform(
        val pipeline: PipelineNode,
        val origCmdNewIndices: IntArray?,
        val negated: Boolean,
    )

    private fun transformPipeline(
        node: PipelineNode,
        teeFiles: MutableList<TeeFileInfo>,
        timestamp: Instant,
    ): PipelineTransform {
        // Only wrap commands in existing pipelines (2+ commands).
        if (node.commands.size <= 1) {
            return PipelineTransform(node, null, false)
        }

        val newCommands = mutableListOf<CommandNode>()
        val newPipeStderr = mutableListOf<Boolean>()
        val origCmdNewIndices = mutableListOf<Int>()
        var anyWrapped = false

        for (i in node.commands.indices) {
            val cmd = node.commands[i]
            val isLast = i == node.commands.size - 1

            val isTarget =
                cmd is SimpleCommandNode && cmd.name != null && shouldTarget(cmd)
            if (!isTarget) {
                origCmdNewIndices.add(newCommands.size)
                newCommands.add(cmd)
                if (!isLast) {
                    newPipeStderr.add(node.pipeStderr?.getOrNull(i) ?: false)
                }
                continue
            }

            val simpleCmd = cmd as SimpleCommandNode
            val commandName = getCommandName(simpleCmd.name) ?: "unknown"
            val idx = counter++
            val stdoutFile = generateStdoutPath(idx, commandName, timestamp)
            val teeCmd = makeTeeCommand(stdoutFile)

            val command = serializeCommand(simpleCmd)
            teeFiles.add(TeeFileInfo(idx, commandName, command, stdoutFile))

            origCmdNewIndices.add(newCommands.size)
            newCommands.add(cmd)
            newPipeStderr.add(node.pipeStderr?.getOrNull(i) ?: false)
            newCommands.add(teeCmd)
            if (!isLast) {
                newPipeStderr.add(false)
            }
            anyWrapped = true
        }

        if (!anyWrapped) {
            return PipelineTransform(node, null, false)
        }

        return PipelineTransform(
            pipeline = PipelineNode(
                commands = newCommands,
                negated = false,
                timed = node.timed,
                timePosix = node.timePosix,
                pipeStderr = if (newPipeStderr.isNotEmpty()) newPipeStderr else null,
            ),
            origCmdNewIndices = origCmdNewIndices.toIntArray(),
            negated = node.negated,
        )
    }

    private fun makePipestatusRestore(indices: IntArray, negated: Boolean): PipelineNode {
        val args = mutableListOf<WordNode>(
            word("__just_bash_tee_restore"),
        )
        indices.forEach { index ->
            args.add(
                WordNode(
                    mutableListOf(
                        ParameterExpansionPart(parameter = "PIPESTATUS[$index]", operation = null),
                    ),
                ),
            )
        }
        val cmd = SimpleCommandNode(
            assignments = mutableListOf(),
            name = word("builtin"),
            args = args,
            redirections = mutableListOf(),
        )
        return PipelineNode(commands = mutableListOf(cmd), negated = negated)
    }

    private fun word(value: String): WordNode =
        WordNode(mutableListOf(LiteralPart(value)))

    private fun shouldTarget(cmd: SimpleCommandNode): Boolean {
        val pattern = options.targetCommandPattern ?: return true
        val name = getCommandName(cmd.name)
        return name != null && pattern.containsMatchIn(name)
    }

    private fun getCommandName(word: WordNode?): String? {
        if (word == null) return null
        if (word.parts.size == 1 && word.parts[0] is LiteralPart) {
            return (word.parts[0] as LiteralPart).value
        }
        return null
    }

    private fun serializeCommand(cmd: SimpleCommandNode): String {
        val parts = mutableListOf<String>()
        cmd.name?.let { parts.add(Serializer.serializeWord(it)) }
        cmd.args.forEach { parts.add(Serializer.serializeWord(it)) }
        return parts.joinToString(" ")
    }

    private fun makeTeeCommand(outputFile: String): SimpleCommandNode {
        return SimpleCommandNode(
            assignments = mutableListOf(),
            name = word("tee"),
            args = mutableListOf(word(outputFile)),
            redirections = mutableListOf(),
        )
    }
}
