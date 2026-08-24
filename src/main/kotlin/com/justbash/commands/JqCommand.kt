package com.justbash.commands

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import net.thisptr.jackson.jq.BuiltinFunctionLoader
import net.thisptr.jackson.jq.JsonQuery
import net.thisptr.jackson.jq.Scope
import net.thisptr.jackson.jq.Versions

/**
 * jq command backed by jackson-jq (net.thisptr:jackson-jq), a pure-Java
 * jq implementation for Jackson JSON Processor.
 *
 * Port of just-bash `jq/jq.ts`. Supports:
 *   jq `-r`, `-c`, `-n`, `-s`, `-R`, FILTER, FILE...
 */
object JqCommand : Command {
    override val name = "jq"

    /** Shared root scope; loaded once but creates a child scope per execution. */
    private val rootScope: Scope by lazy {
        val scope = Scope.newEmptyScope()
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, scope)
        scope
    }

    private val mapper = ObjectMapper()

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "jq", "command-line JSON processor",
                "jq [OPTIONS] FILTER [FILE...]",
                listOf(
                    "-r, --raw-output     output raw strings, not JSON",
                    "-c, --compact-output compact instead of pretty-printed",
                    "-n, --null-input     use null as the single input value",
                    "-s, --slurp          read entire input into array",
                    "-R, --raw-input      read each line as string",
                    "    --help           display this help and exit",
                ),
            )
        }

        var raw = false
        var compact = false
        var nullInput = false
        var slurp = false
        var rawInput = false
        var filterSet = false
        var filter = "."
        var parseOptions = true

        val files = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--" -> { parseOptions = false; i++; continue }
                parseOptions && arg == "--help" -> { /* handled above */ i++; continue }
                parseOptions && arg == "-r" || arg == "--raw-output" -> { raw = true; i++; continue }
                parseOptions && arg == "-c" || arg == "--compact-output" -> { compact = true; i++; continue }
                parseOptions && arg == "-n" || arg == "--null-input" -> { nullInput = true; i++; continue }
                parseOptions && arg == "-s" || arg == "--slurp" -> { slurp = true; i++; continue }
                parseOptions && arg == "-R" || arg == "--raw-input" -> { rawInput = true; i++; continue }
                parseOptions && arg == "-S" || arg == "--sort-keys" -> { i++; continue }
                parseOptions && arg == "-C" || arg == "--color" || arg == "-M" || arg == "--monochrome" -> { i++; continue }
                parseOptions && arg == "--arg" -> { i += 3; continue } // skip --arg NAME VALUE
                parseOptions && arg == "--argjson" -> { i += 3; continue }
                parseOptions && arg == "--rawfile" -> { i += 3; continue }
                parseOptions && arg == "--slurpfile" -> { i += 3; continue }
                !filterSet -> { filter = arg; filterSet = true; i++; continue }
                else -> { files.add(arg); i++; continue }
            }
        }

        try {
            // Read input
            val inputText: String
            if (nullInput) {
                inputText = "null"
            } else if (files.isEmpty() || (files.size == 1 && files[0] == "-")) {
                inputText = ctx.stdin.toString(Charsets.UTF_8)
            } else {
                val sb = StringBuilder()
                for (file in files) {
                    val path = ctx.fs.resolvePath(ctx.cwd, file)
                    try {
                        sb.append(ctx.fs.readFile(path))
                    } catch (_: Exception) {
                        return ExecResult(stderr = "jq: error: Could not open $file\n", exitCode = 2)
                    }
                }
                inputText = sb.toString()
            }

            val input = if (inputText.isBlank()) "null" else inputText

            // Parse input into one or more JsonNodes
            val inputs = ArrayList<JsonNode>()
            if (rawInput) {
                // Treat each line as a raw string
                for (line in input.split("\n")) {
                    inputs.add(com.fasterxml.jackson.databind.node.TextNode.valueOf(line))
                }
            } else if (slurp) {
                // Parse the entire input as a JSON array, or wrap in array
                val node = try {
                    mapper.readTree(input)
                } catch (_: Exception) {
                    // If not valid JSON, wrap raw text lines
                    val arr = ArrayNode(mapper.nodeFactory)
                    for (line in input.split("\n").filter { it.isNotBlank() }) {
                        try {
                            arr.add(mapper.readTree(line))
                        } catch (_: Exception) {
                            arr.add(com.fasterxml.jackson.databind.node.TextNode.valueOf(line))
                        }
                    }
                    arr
                }
                inputs.add(node)
            } else {
                // Try line-by-line JSON parsing
                val lines = input.split("\n").filter { it.isNotBlank() }
                if (lines.isEmpty()) {
                    inputs.add(com.fasterxml.jackson.databind.node.NullNode.getInstance())
                } else {
                    for (line in lines) {
                        try {
                            inputs.add(mapper.readTree(line))
                        } catch (_: Exception) {
                            // Treat as raw text
                            inputs.add(com.fasterxml.jackson.databind.node.TextNode.valueOf(line))
                        }
                    }
                }
            }

            val q = JsonQuery.compile(filter, Versions.JQ_1_6)
            val sb = StringBuilder()

            var first = true
            for (inputNode in inputs) {
                val childScope = Scope.newChildScope(rootScope)
                val results = ArrayList<JsonNode>()
                q.apply(childScope, inputNode) { results.add(it) }

                for (node in results) {
                    if (!first && !compact && !raw) sb.append("\n")
                    val output = when {
                        raw && node.isTextual -> node.asText()
                        raw && node.isNull -> ""
                        raw -> node.asText()
                        compact -> mapper.writeValueAsString(node)
                        else -> mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
                    }
                    if (raw) {
                        sb.append(output)
                        sb.append("\n")
                    } else {
                        if (!first) sb.append("\n")
                        sb.append(output)
                    }
                    first = false
                }
            }

            // Add trailing newline (unless raw output)
            if (!raw && sb.isNotEmpty()) sb.append("\n")

            return ExecResult(stdout = sb.toString(), stderr = "", exitCode = 0)
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            return ExecResult(stderr = "jq: error: $msg\n", exitCode = 2)
        }
    }
}