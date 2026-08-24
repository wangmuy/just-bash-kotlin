package com.justbash.commands

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.StringWriter

/**
 * yq - command-line YAML<->JSON converter.
 *
 * Port of the format-conversion subset of just-bash `yq/yq.ts`. Unlike a full
 * yq, this command carries no jq-style query expressions: it reads YAML or
 * JSON and re-emits it in the requested format. The default pipeline is
 * YAML in -> JSON out (pretty-printed), matching the just-bash help text which
 * documents `-o json` / `-p json` conversion as the primary use case.
 *
 * Options:
 *   -p, --input-format FMT   input format: yaml (default) or json
 *   -o, --output-format FMT  output format: json (default) or yaml
 *   -r, --raw-output         output string values without quotes (json only)
 *   -c, --compact            compact JSON output (no indentation)
 *   -M, --monochrome         accepted and ignored
 *   -I, --indent N           indentation width for YAML output (default 2)
 *       --help               display this help and exit
 *
 * Parsing: YAML is parsed by SnakeYAML into Map/List values and converted to a
 * Jackson [JsonNode] via [ObjectMapper]; JSON is parsed directly with Jackson.
 * Output: JSON is written with Jackson (respecting `-c` / `-r`); YAML is
 * written with SnakeYAML [Yaml.dump] (respecting `-I`).
 */
object YqCommand : Command {
    override val name = "yq"

    private val mapper = ObjectMapper()

    private val INDENT_PATTERN = Regex("""\d+""")

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "yq", "command-line YAML/JSON converter",
                "yq [OPTIONS] [FILE]",
                listOf(
                    "-p, --input-format=FMT   input format: yaml (default), json",
                    "-o, --output-format=FMT  output format: json (default), yaml",
                    "-r, --raw-output         output string values without quotes (json)",
                    "-c, --compact            compact JSON output",
                    "-M, --monochrome         accepted and ignored",
                    "-I, --indent=N           set YAML indent level (default: 2)",
                    "    --help               display this help and exit",
                ),
            )
        }

        var inputFormat = "yaml"
        var outputFormat = "json"
        var raw = false
        var compact = false
        var indent = 2
        var file: String? = null

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-p" || arg == "--input-format" -> {
                    val fmt = args.getOrNull(++i) ?: return missingArg("yq", arg)
                    if (!isValidFormat(fmt)) return unknownOption("yq", "$arg $fmt")
                    inputFormat = fmt
                }
                arg.startsWith("--input-format=") -> {
                    val fmt = arg.substringAfter('=')
                    if (!isValidFormat(fmt)) return unknownOption("yq", arg)
                    inputFormat = fmt
                }
                arg == "-o" || arg == "--output-format" -> {
                    val fmt = args.getOrNull(++i) ?: return missingArg("yq", arg)
                    if (!isValidFormat(fmt)) return unknownOption("yq", "$arg $fmt")
                    outputFormat = fmt
                }
                arg.startsWith("--output-format=") -> {
                    val fmt = arg.substringAfter('=')
                    if (!isValidFormat(fmt)) return unknownOption("yq", arg)
                    outputFormat = fmt
                }
                arg == "-I" || arg == "--indent" -> {
                    val value = args.getOrNull(++i) ?: return missingArg("yq", arg)
                    val parsed = parseIndent(value)
                    if (parsed == null) return invalidIndent(value)
                    indent = parsed
                }
                arg.startsWith("--indent=") -> {
                    val value = arg.substringAfter('=')
                    val parsed = parseIndent(value)
                    if (parsed == null) return invalidIndent(value)
                    indent = parsed
                }
                arg == "-r" || arg == "--raw-output" -> raw = true
                arg == "-c" || arg == "--compact" -> compact = true
                arg == "-M" || arg == "--monochrome" || arg == "--prettyPrint" -> { /* ignored */ }
                // Combined short flags like -rc
                arg.startsWith("-") && arg != "-" && !arg.startsWith("--") -> {
                    var j = 1
                    var unknown = false
                    while (j < arg.length) {
                        when (arg[j]) {
                            'r' -> raw = true
                            'c' -> compact = true
                            'M' -> { /* ignored */ }
                            else -> { unknown = true; break }
                        }
                        j++
                    }
                    if (unknown) return unknownOption("yq", "-${arg[j]}")
                }
                arg == "-" -> file = null
                else -> file = arg
            }
            i++
        }

        // Read input (file or stdin).
        val inputText: String = try {
            if (file == null || file == "-") {
                ctx.stdin.toString(Charsets.UTF_8)
            } else {
                val path = ctx.fs.resolvePath(ctx.cwd, file)
                try {
                    ctx.fs.readFile(path)
                } catch (_: Exception) {
                    return ExecResult(stderr = "yq: $file: No such file or directory\n", exitCode = 2)
                }
            }
        } catch (_: Exception) {
            return ExecResult(stderr = "yq: failed to read input\n", exitCode = 2)
        }

        return try {
            // Parse into an in-memory JsonNode.
            val node = when (inputFormat) {
                "yaml" -> parseYaml(inputText)
                "json" -> parseJson(inputText)
                else -> return ExecResult(stderr = "yq: Invalid input format: $inputFormat\n", exitCode = 2)
            }

            // Re-emit in the requested format.
            val output = when (outputFormat) {
                "yaml" -> toYaml(node, indent)
                "json" -> toJson(node, compact, raw)
                else -> return ExecResult(stderr = "yq: Invalid output format: $outputFormat\n", exitCode = 2)
            }

            ExecResult(stdout = output, stderr = "", exitCode = 0)
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            ExecResult(stderr = "yq: parse error: $msg\n", exitCode = 5)
        }
    }

    private fun isValidFormat(fmt: String): Boolean = fmt == "yaml" || fmt == "json"

    private fun parseIndent(value: String): Int? {
        if (!INDENT_PATTERN.matches(value)) return null
        val n = value.toIntOrNull() ?: return null
        if (n < 0 || n > 32) return null
        return n
    }

    private fun invalidIndent(value: String): ExecResult =
        ExecResult(stdout = "", stderr = "yq: invalid indent '$value' (expected integer 0..32)\n", exitCode = 2)

    private fun missingArg(cmdName: String, option: String): ExecResult =
        ExecResult(stdout = "", stderr = "$cmdName: option '$option' requires an argument\n", exitCode = 1)

    /** Parse YAML (via SnakeYAML) into a Jackson JsonNode. */
    private fun parseYaml(text: String): JsonNode {
        if (text.isBlank()) return mapper.nodeFactory.nullNode()
        val yaml = Yaml()
        val loaded = yaml.load<Any?>(text)
        return toJsonNode(mapper.nodeFactory, loaded)
    }

    /** Parse JSON (via Jackson) into a Jackson JsonNode. */
    private fun parseJson(text: String): JsonNode {
        if (text.isBlank()) return mapper.nodeFactory.nullNode()
        return mapper.readTree(text)
    }

    /** Convert SnakeYAML's Map/List/scalar tree into a Jackson JsonNode. */
    private fun toJsonNode(factory: com.fasterxml.jackson.databind.node.JsonNodeFactory, value: Any?): JsonNode {
        when (value) {
            null -> return factory.nullNode()
            is Map<*, *> -> {
                val obj = ObjectNode(factory)
                for ((k, v) in value) {
                    obj.set<JsonNode>(k.toString(), toJsonNode(factory, v))
                }
                return obj
            }
            is List<*> -> {
                val arr = ArrayNode(factory)
                for (item in value) arr.add(toJsonNode(factory, item))
                return arr
            }
            is Boolean -> return factory.booleanNode(value)
            is Int -> return factory.numberNode(value)
            is Long -> return factory.numberNode(value)
            is Double -> return factory.numberNode(value)
            is Float -> return factory.numberNode(value.toDouble())
            is java.math.BigInteger -> return factory.numberNode(value)
            is java.math.BigDecimal -> return factory.numberNode(value)
            is ByteArray -> return factory.binaryNode(value)
            else -> return factory.textNode(value.toString())
        }
    }

    /** Emit a JsonNode as YAML via SnakeYAML. */
    private fun toYaml(node: JsonNode, indent: Int): String {
        val value = nodeToPlain(node)
        val options = DumperOptions()
        options.indent = indent
        options.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        if (indent == 0) {
            // SnakeYAML clamps indent to >= 1; 0 means minimal (1) spacing.
            options.indent = 1
        }
        val yaml = Yaml(options)
        val out = StringWriter()
        yaml.dump(value, out)
        // SnakeYAML emits `\n` line endings; normalize and ensure a single
        // trailing newline consistent with the JSON path.
        val dumped = out.toString().replace("\r\n", "\n").replace('\r', '\n')
        return dumped.trimEnd('\n') + "\n"
    }

    /** Convert a JsonNode into plain Java objects for SnakeYAML dumping. */
    private fun nodeToPlain(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isObject -> {
            val map = LinkedHashMap<String, Any?>()
            node.fields().forEach { (k, v) ->
                map[k] = nodeToPlain(v)
            }
            map
        }
        node.isArray -> {
            val list = ArrayList<Any?>()
            node.forEach { list.add(nodeToPlain(it)) }
            list
        }
        node.isBoolean -> node.asBoolean()
        node.isIntegralNumber -> node.asLong()
        node.isFloatingPointNumber -> node.asDouble()
        node.isTextual -> node.asText()
        node.isBinary -> node.binaryValue()
        else -> node.asText()
    }

    /** Emit a JsonNode as JSON, honoring compact and raw modes. */
    private fun toJson(node: JsonNode, compact: Boolean, raw: Boolean): String {
        // Raw output: string scalars are printed unquoted (and unwrapped).
        if (raw) {
            val text = when {
                node.isTextual -> node.asText()
                node.isNull -> ""
                else -> node.asText()
            }
            return if (text.isEmpty()) "" else text + "\n"
        }
        val rawJson = if (compact) {
            mapper.writeValueAsString(node)
        } else {
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
        }
        // Jackson DefaultPrettyPrinter may emit \r\n on Windows; normalise to \n
        // so output is platform-independent.
        val json = rawJson.replace("\r\n", "\n").replace('\r', '\n')
        return if (json.isEmpty()) "" else json + "\n"
    }
}
