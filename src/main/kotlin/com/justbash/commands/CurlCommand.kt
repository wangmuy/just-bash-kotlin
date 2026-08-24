package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Port of just-bash `curl/curl.ts`.
 *
 * Simplified `curl` implemented on top of the JDK's built-in
 * `java.net.http.HttpClient` (zero external dependencies). The port is fully
 * synchronous: it uses `HttpClient.send(...)` rather than coroutines.
 *
 * Supported options (a subset of the TypeScript original):
 *   -X/--request, -H/--header, -d/--data, --data-raw, -o/--output,
 *   -s/--silent, -S/--show-error, -v/--verbose, -L/--location,
 *   -k/--insecure, -i/--include, -I/--head.
 */
object CurlCommand : Command {
    override val name = "curl"

    /** Accumulated `-d`/`--data*` parts, resolved to a joined body at execute time. */
    private class DataPart(
        val value: String? = null,
        val file: String? = null,
        val binary: Boolean = false,
    )

    private class Options {
        var method: String = "GET"
        var headers = ArrayList<Pair<String, String>>()
        val dataParts = ArrayList<DataPart>()
        var getMode = false
        var headOnly = false
        var includeHeaders = false
        var silent = false
        var showError = false
        var followRedirects = false
        var insecure = false
        var verbose = false
        var outputFile: String? = null
        var url: String? = null
    }

    private sealed class ParseResult {
        data class Ok(val options: Options) : ParseResult()
        data class Err(val error: ExecResult) : ParseResult()
    }

    private fun parse(args: List<String>): ParseResult {
        val options = Options()
        var impliesPost = false
        var explicitMethod = false

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-X" || arg == "--request" -> {
                    options.method = args.getOrNull(++i) ?: "GET"
                    explicitMethod = true
                }
                arg.startsWith("-X") -> {
                    options.method = arg.substring(2)
                    explicitMethod = true
                }
                arg.startsWith("--request=") -> {
                    options.method = arg.substring(10)
                    explicitMethod = true
                }
                arg == "-H" || arg == "--header" -> {
                    val header = args.getOrNull(++i)
                    if (header != null) addHeader(options, header)
                }
                arg.startsWith("--header=") -> {
                    addHeader(options, arg.substring(9))
                }
                arg == "-d" || arg == "--data" -> {
                    pushDataPart(options, args.getOrNull(++i) ?: "", allowFile = true, binary = false)
                    impliesPost = true
                }
                arg == "--data-raw" -> {
                    pushDataPart(options, args.getOrNull(++i) ?: "", allowFile = false, binary = false)
                    impliesPost = true
                }
                arg.startsWith("-d") -> {
                    pushDataPart(options, arg.substring(2), allowFile = true, binary = false)
                    impliesPost = true
                }
                arg.startsWith("--data=") -> {
                    pushDataPart(options, arg.substring(7), allowFile = true, binary = false)
                    impliesPost = true
                }
                arg.startsWith("--data-raw=") -> {
                    pushDataPart(options, arg.substring(11), allowFile = false, binary = false)
                    impliesPost = true
                }
                arg == "-o" || arg == "--output" -> {
                    options.outputFile = args.getOrNull(++i)
                }
                arg.startsWith("--output=") -> {
                    options.outputFile = arg.substring(9)
                }
                arg == "-s" || arg == "--silent" -> options.silent = true
                arg == "-S" || arg == "--show-error" -> options.showError = true
                arg == "-v" || arg == "--verbose" -> options.verbose = true
                arg == "-L" || arg == "--location" -> options.followRedirects = true
                arg == "-k" || arg == "--insecure" -> options.insecure = true
                arg == "-i" || arg == "--include" -> options.includeHeaders = true
                arg == "-I" || arg == "--head" -> {
                    options.headOnly = true
                    options.method = "HEAD"
                    explicitMethod = true
                }
                arg == "--" -> {
                    // Everything after -- is treated as positional (the URL).
                    i++
                    while (i < args.size) {
                        if (options.url == null) options.url = args[i]
                        i++
                    }
                    continue
                }
                arg.startsWith("--") -> return ParseResult.Err(unknownOption("curl", arg))
                arg.startsWith("-") && arg != "-" -> {
                    // Combined short boolean options like -sSLIivk
                    for (c in arg.substring(1)) {
                        when (c) {
                            's' -> options.silent = true
                            'S' -> options.showError = true
                            'L' -> options.followRedirects = true
                            'k' -> options.insecure = true
                            'i' -> options.includeHeaders = true
                            'v' -> options.verbose = true
                            'I' -> {
                                options.headOnly = true
                                options.method = "HEAD"
                                explicitMethod = true
                            }
                            else -> return ParseResult.Err(unknownOption("curl", "-$c"))
                        }
                    }
                }
                else -> {
                    // Positional argument: the URL.
                    options.url = arg
                }
            }
            i++
        }

        if (impliesPost && !explicitMethod && !options.getMode) {
            options.method = "POST"
        }

        return ParseResult.Ok(options)
    }

    private fun addHeader(options: Options, header: String) {
        val colonIndex = header.indexOf(':')
        if (colonIndex > 0) {
            val name = header.substring(0, colonIndex).trim()
            val value = header.substring(colonIndex + 1).trim()
            options.headers.add(name to value)
        }
    }

    private fun pushDataPart(options: Options, value: String, allowFile: Boolean, binary: Boolean) {
        if (allowFile && value.startsWith("@")) {
            options.dataParts.add(DataPart(file = value.substring(1), binary = binary))
        } else {
            options.dataParts.add(DataPart(value = value, binary = binary))
        }
    }

    /** Resolve `-d`/`--data*` parts into one payload, reading `@file` references and joining with `&`. */
    private fun resolveData(options: Options, ctx: CommandContext): String? {
        if (options.dataParts.isEmpty()) return null
        val parts = ArrayList<String>()
        for (part in options.dataParts) {
            if (part.file != null) {
                val path = ctx.fs.resolvePath(ctx.cwd, part.file)
                val content = ctx.fs.readFile(path)
                parts.add(if (part.binary) content else content.replace(Regex("[\r\n]"), ""))
            } else {
                parts.add(part.value ?: "")
            }
        }
        return parts.joinToString("&")
    }

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "curl",
                "transfer a URL",
                "curl [OPTIONS] URL",
                listOf(
                    "-X, --request METHOD  HTTP method (GET, POST, PUT, DELETE, etc.)",
                    "-H, --header HEADER   Add header (can be used multiple times)",
                    "-d, --data DATA       HTTP POST data (DATA=@file reads from file, strips newlines)",
                    "    --data-raw DATA   HTTP POST data (no @ interpretation)",
                    "-o, --output FILE     Write output to file",
                    "-I, --head            Show headers only (HEAD request)",
                    "-i, --include         Include response headers in output",
                    "-s, --silent          Silent mode (no progress)",
                    "-S, --show-error      Show errors even when silent",
                    "-L, --location        Follow redirects",
                    "-k, --insecure        Allow insecure TLS connections",
                    "-v, --verbose         Verbose output",
                    "    --help            Display this help and exit",
                ),
            )
        }

        val parsed = parse(args)
        if (parsed is ParseResult.Err) return parsed.error
        parsed as ParseResult.Ok
        val options = parsed.options

        if (options.url == null) {
            return ExecResult(stdout = "", stderr = "curl: no URL specified\n", exitCode = 2)
        }

        // Normalize URL: add https:// when no protocol is present.
        var url = options.url!!
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        try {
            val resolvedData = resolveData(options, ctx)
            if (options.getMode) {
                url = appendDataToUrl(url, resolvedData)
            }

            val body: String? = if (resolvedData != null && !options.getMode) resolvedData else null

            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))

            val methodProducesBody = body != null && body.isNotEmpty()
            if (methodProducesBody) {
                builder.method(options.method, HttpRequest.BodyPublishers.ofString(body!!))
            } else {
                builder.method(options.method, HttpRequest.BodyPublishers.noBody())
            }

            // Default Content-Type for form-urlencoded data unless already set.
            if (methodProducesBody && options.headers.none { it.first.equals("Content-Type", ignoreCase = true) }) {
                builder.header("Content-Type", "application/x-www-form-urlencoded")
            }
            for ((name, value) in options.headers) {
                builder.header(name, value)
            }

            val followRedirects = if (options.followRedirects) {
                HttpClient.Redirect.NORMAL
            } else {
                HttpClient.Redirect.NEVER
            }

            val client = HttpClient.newBuilder().followRedirects(followRedirects).build()
            val request = builder.build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            var output = buildOutput(options, response, url)

            // Write to file when -o/--output was given.
            if (options.outputFile != null) {
                val filePath = ctx.fs.resolvePath(ctx.cwd, options.outputFile!!)
                val content = if (options.headOnly) "" else response.body()
                ctx.fs.writeFile(filePath, content)
                if (!options.verbose) {
                    output = ""
                }
            }

            return ExecResult(stdout = output, stderr = "", exitCode = 0, stdoutKind = "text")
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            val showErr = !options.silent || options.showError
            val stderr = if (showErr) "curl: (1) $message\n" else ""
            return ExecResult(stdout = "", stderr = stderr, exitCode = 1)
        }
    }

    private fun buildOutput(
        options: Options,
        response: HttpResponse<String>,
        requestUrl: String,
    ): String {
        val sb = StringBuilder()
        val statusLine = "HTTP/1.1 ${response.statusCode()}"

        if (options.verbose) {
            sb.append("> ${options.method} $requestUrl\n")
            for ((name, value) in options.headers) {
                sb.append("> $name: $value\n")
            }
            sb.append(">\n")
            sb.append("< $statusLine\n")
            appendHeaders(sb, response.headers())
            sb.append("<\n")
        }

        if (options.includeHeaders && !options.verbose) {
            sb.append("$statusLine\r\n")
            appendHeaders(sb, response.headers())
            sb.append("\r\n\r\n")
        }

        if (!options.headOnly) {
            sb.append(response.body())
        } else if (options.includeHeaders || options.verbose) {
            // HEAD with -i/-v already printed headers above.
        } else {
            sb.append("$statusLine\r\n")
            appendHeaders(sb, response.headers())
            sb.append("\r\n")
        }

        return sb.toString()
    }

    private fun appendHeaders(sb: StringBuilder, headers: java.net.http.HttpHeaders) {
        for (entry in headers.map().entries) {
            val name = entry.key ?: continue
            val values = entry.value
            if (values != null) {
                for (v in values) {
                    sb.append("$name: $v\r\n")
                }
            }
        }
    }

    private fun appendDataToUrl(url: String, data: String?): String {
        if (data.isNullOrEmpty()) return url
        val hashIndex = url.indexOf('#')
        val base = if (hashIndex == -1) url else url.substring(0, hashIndex)
        val fragment = if (hashIndex == -1) "" else url.substring(hashIndex)
        val separator = when {
            base.endsWith("?") || base.endsWith("&") -> ""
            base.contains('?') -> "&"
            else -> "?"
        }
        return base + separator + data + fragment
    }
}
