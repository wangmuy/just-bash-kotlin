package com.justbash.interpreter

import com.justbash.ExecResult

/**
 * Additional builtins: shopt, dirs, complete, compgen, compopt.
 *
 * These are builtins (not [com.justbash.Command] objects), called directly by
 * [Interpreter.dispatchBuiltin]. Each receives [InterpreterContext] and returns
 * [ExecResult].
 */
object MoreBuiltins {

    // ─── shopt ───────────────────────────────────────────────────────────────

    /** All supported shopt option names. */
    val SHOPT_OPTIONS = listOf(
        "extglob", "dotglob", "nullglob", "failglob", "globstar",
        "globskipdots", "nocaseglob", "nocasematch", "expand_aliases",
        "lastpipe", "xpg_echo",
    )

    /** Options recognized but not implemented (always report as off). */
    private val STUB_OPTIONS = listOf(
        "autocd", "cdable_vars", "cdspell", "checkhash", "checkjobs",
        "checkwinsize", "cmdhist", "compat31", "compat32", "compat40",
        "compat41", "compat42", "compat43", "compat44", "complete_fullquote",
        "direxpand", "dirspell", "execfail", "extdebug", "extquote",
        "force_fignore", "globasciiranges", "gnu_errfmt", "histappend",
        "histreedit", "histverify", "hostcomplete", "huponexit",
        "inherit_errexit", "interactive_comments", "lithist",
        "localvar_inherit", "localvar_unset", "login_shell", "mailwarn",
        "no_empty_cmd_completion", "progcomp", "progcomp_alias", "promptvars",
        "restricted_shell", "shift_verbose", "sourcepath",
    )

    private fun isShoptOption(name: String): Boolean = name in SHOPT_OPTIONS
    private fun isStubOption(name: String): Boolean = name in STUB_OPTIONS

    private fun getShopt(ctx: InterpreterContext, name: String): Boolean =
        when (name) {
            "extglob" -> ctx.state.shoptOptions.extglob
            "dotglob" -> ctx.state.shoptOptions.dotglob
            "nullglob" -> ctx.state.shoptOptions.nullglob
            "failglob" -> ctx.state.shoptOptions.failglob
            "globstar" -> ctx.state.shoptOptions.globstar
            "globskipdots" -> ctx.state.shoptOptions.globskipdots
            "nocaseglob" -> ctx.state.shoptOptions.nocaseglob
            "nocasematch" -> ctx.state.shoptOptions.nocasematch
            "expand_aliases" -> ctx.state.shoptOptions.expand_aliases
            "lastpipe" -> ctx.state.shoptOptions.lastpipe
            "xpg_echo" -> ctx.state.shoptOptions.xpg_echo
            else -> false
        }

    private fun setShopt(ctx: InterpreterContext, name: String, value: Boolean) {
        when (name) {
            "extglob" -> ctx.state.shoptOptions.extglob = value
            "dotglob" -> ctx.state.shoptOptions.dotglob = value
            "nullglob" -> ctx.state.shoptOptions.nullglob = value
            "failglob" -> ctx.state.shoptOptions.failglob = value
            "globstar" -> ctx.state.shoptOptions.globstar = value
            "globskipdots" -> ctx.state.shoptOptions.globskipdots = value
            "nocaseglob" -> ctx.state.shoptOptions.nocaseglob = value
            "nocasematch" -> ctx.state.shoptOptions.nocasematch = value
            "expand_aliases" -> ctx.state.shoptOptions.expand_aliases = value
            "lastpipe" -> ctx.state.shoptOptions.lastpipe = value
            "xpg_echo" -> ctx.state.shoptOptions.xpg_echo = value
        }
    }

    fun shopt(ctx: InterpreterContext, args: List<String>): ExecResult {
        var setFlag = false
        var unsetFlag = false
        var printFlag = false
        var quietFlag = false
        var oFlag = false
        val optionNames = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg == "--") { i++; break }
            if (arg.startsWith("-") && arg.length > 1) {
                for (j in 1 until arg.length) {
                    when (arg[j]) {
                        's' -> setFlag = true
                        'u' -> unsetFlag = true
                        'p' -> printFlag = true
                        'q' -> quietFlag = true
                        'o' -> oFlag = true
                        else -> return Result.failure("shopt: -${arg[j]}: invalid option\n", 2)
                    }
                }
                i++
            } else break
        }
        while (i < args.size) optionNames.add(args[i++])

        if (oFlag) {
            return handleShopSetOptions(ctx, optionNames, setFlag, unsetFlag, printFlag, quietFlag)
        }

        if (setFlag && unsetFlag) {
            return Result.failure("shopt: cannot set and unset shell options simultaneously\n")
        }

        // No option names: list all
        if (optionNames.isEmpty()) {
            if (setFlag || unsetFlag) {
                val sb = StringBuilder()
                for (opt in SHOPT_OPTIONS) {
                    val value = getShopt(ctx, opt)
                    if (setFlag && value) sb.appendLine(if (printFlag) "shopt -s $opt" else "$opt\t\ton")
                    else if (unsetFlag && !value) sb.appendLine(if (printFlag) "shopt -u $opt" else "$opt\t\toff")
                }
                return Result.success(sb.toString())
            }
            val sb = StringBuilder()
            for (opt in SHOPT_OPTIONS) {
                val value = getShopt(ctx, opt)
                sb.appendLine(if (printFlag) "shopt ${if (value) "-s" else "-u"} $opt" else "$opt\t\t${if (value) "on" else "off"}")
            }
            return Result.success(sb.toString())
        }

        var hasError = false
        val stderrLines = mutableListOf<String>()
        val output = mutableListOf<String>()

        for (name in optionNames) {
            if (!isShoptOption(name) && !isStubOption(name)) {
                stderrLines.add("shopt: $name: invalid shell option name")
                hasError = true
                continue
            }
            if (setFlag) {
                if (isShoptOption(name)) setShopt(ctx, name, true)
            } else if (unsetFlag) {
                if (isShoptOption(name)) setShopt(ctx, name, false)
            } else {
                if (isShoptOption(name)) {
                    val value = getShopt(ctx, name)
                    if (quietFlag) {
                        if (!value) hasError = true
                    } else if (printFlag) {
                        output.add("shopt ${if (value) "-s" else "-u"} $name")
                        if (!value) hasError = true
                    } else {
                        output.add("$name\t\t${if (value) "on" else "off"}")
                        if (!value) hasError = true
                    }
                } else {
                    // Stub options report as off
                    if (quietFlag) hasError = true
                    else if (printFlag) { output.add("shopt -u $name"); hasError = true }
                    else { output.add("$name\t\toff"); hasError = true }
                }
            }
        }

        return Result.result(
            if (output.isNotEmpty()) output.joinToString("\n", postfix = "\n") else "",
            if (stderrLines.isNotEmpty()) stderrLines.joinToString("\n", postfix = "\n") else "",
            if (hasError) 1 else 0,
        )
    }

    /** Handle -o flag: use set -o option names instead of shopt options. */
    private fun handleShopSetOptions(
        ctx: InterpreterContext,
        optionNames: List<String>,
        setFlag: Boolean,
        unsetFlag: Boolean,
        printFlag: Boolean,
        quietFlag: Boolean,
    ): ExecResult {
        val setOptions = linkedMapOf(
            "errexit" to "errexit", "pipefail" to "pipefail", "nounset" to "nounset",
            "xtrace" to "xtrace", "verbose" to "verbose", "posix" to "posix",
            "allexport" to "allexport", "noclobber" to "noclobber", "noglob" to "noglob",
            "noexec" to "noexec", "vi" to "vi", "emacs" to "emacs",
        )
        val noopOptions = listOf(
            "braceexpand", "errtrace", "functrace", "hashall", "histexpand",
            "history", "ignoreeof", "interactive-comments", "keyword", "monitor",
            "nolog", "notify", "onecmd", "physical", "privileged",
        )
        val allOptions = (setOptions.keys + noopOptions).sorted()

        fun getOption(ctx: InterpreterContext, key: String): Boolean = when (key) {
            "errexit" -> ctx.state.options.errexit
            "pipefail" -> ctx.state.options.pipefail
            "nounset" -> ctx.state.options.nounset
            "xtrace" -> ctx.state.options.xtrace
            "verbose" -> ctx.state.options.verbose
            "posix" -> ctx.state.options.posix
            "allexport" -> ctx.state.options.allexport
            "noclobber" -> ctx.state.options.noclobber
            "noglob" -> ctx.state.options.noglob
            "noexec" -> ctx.state.options.noexec
            "vi" -> ctx.state.options.vi
            "emacs" -> ctx.state.options.emacs
            else -> false
        }

        fun setOption(ctx: InterpreterContext, key: String, value: Boolean) {
            when (key) {
                "errexit" -> ctx.state.options.errexit = value
                "pipefail" -> ctx.state.options.pipefail = value
                "nounset" -> ctx.state.options.nounset = value
                "xtrace" -> ctx.state.options.xtrace = value
                "verbose" -> ctx.state.options.verbose = value
                "posix" -> ctx.state.options.posix = value
                "allexport" -> ctx.state.options.allexport = value
                "noclobber" -> ctx.state.options.noclobber = value
                "noglob" -> ctx.state.options.noglob = value
                "noexec" -> ctx.state.options.noexec = value
                "vi" -> ctx.state.options.vi = value
                "emacs" -> ctx.state.options.emacs = value
            }
        }

        if (optionNames.isEmpty()) {
            val sb = StringBuilder()
            for (opt in allOptions) {
                val isNoop = opt in noopOptions
                val value = if (isNoop) false else getOption(ctx, setOptions[opt]!!)
                if (setFlag && !value) continue
                if (unsetFlag && value) continue
                sb.appendLine(if (printFlag) "set ${if (value) "-o" else "+o"} $opt" else "$opt\t\t${if (value) "on" else "off"}")
            }
            return Result.success(sb.toString())
        }

        var hasError = false
        val stderrLines = mutableListOf<String>()
        val output = mutableListOf<String>()

        for (name in optionNames) {
            val isImplemented = setOptions.containsKey(name)
            val isNoop = name in noopOptions
            if (!isImplemented && !isNoop) {
                stderrLines.add("shopt: $name: invalid option name")
                hasError = true
                continue
            }
            if (isNoop) {
                if (setFlag || unsetFlag) { /* silently accept */ }
                else {
                    if (quietFlag) hasError = true
                    else if (printFlag) { output.add("set +o $name"); hasError = true }
                    else { output.add("$name\t\toff"); hasError = true }
                }
                continue
            }
            val key = setOptions[name]!!
            if (setFlag) {
                if (key == "vi") ctx.state.options.emacs = false
                else if (key == "emacs") ctx.state.options.vi = false
                setOption(ctx, key, true)
            } else if (unsetFlag) {
                setOption(ctx, key, false)
            } else {
                val value = getOption(ctx, key)
                if (quietFlag) { if (!value) hasError = true }
                else if (printFlag) { output.add("set ${if (value) "-o" else "+o"} $name"); if (!value) hasError = true }
                else { output.add("$name\t\t${if (value) "on" else "off"}"); if (!value) hasError = true }
            }
        }
        return Result.result(
            if (output.isNotEmpty()) output.joinToString("\n", postfix = "\n") else "",
            if (stderrLines.isNotEmpty()) stderrLines.joinToString("\n", postfix = "\n") else "",
            if (hasError) 1 else 0,
        )
    }

    // ─── dirs ────────────────────────────────────────────────────────────────

    private fun getDirStack(ctx: InterpreterContext): MutableList<String> {
        if (ctx.state.directoryStack == null) ctx.state.directoryStack = ArrayList()
        return ctx.state.directoryStack!!
    }

    fun dirs(ctx: InterpreterContext, args: List<String>): ExecResult {
        val stack = getDirStack(ctx)
        var clearStack = false
        var longFormat = false
        var perLine = false
        var withNumbers = false
        var plusN: Int? = null
        var minusN: Int? = null

        for (arg in args) {
            if (arg == "--") continue
            if (arg.startsWith("+") && arg.length > 1) {
                val n = arg.substring(1).toIntOrNull()
                    ?: return Result.failure("bash: dirs: $arg: numeric argument required\n", 1)
                plusN = n
            } else if (arg.startsWith("-") && arg.length > 1) {
                when (arg) {
                    "-c" -> clearStack = true
                    "-l" -> longFormat = true
                    "-p" -> perLine = true
                    "-v" -> { perLine = true; withNumbers = true }
                    else -> {
                        val n = arg.substring(1).toIntOrNull()
                        if (n != null) minusN = n
                        else return Result.failure("bash: dirs: ${arg[1]}: invalid option\n", 2)
                    }
                }
            } else {
                return Result.failure("bash: dirs: too many arguments\n", 1)
            }
        }

        if (clearStack) {
            ctx.state.directoryStack = ArrayList()
            return Result.ok()
        }

        if (plusN != null || minusN != null) {
            val fullStack = listOf(ctx.state.cwd) + stack
            val idx = if (plusN != null) plusN else (fullStack.size - (minusN ?: 0))
            if (idx < 0 || idx >= fullStack.size) {
                val shown = if (plusN != null) plusN else "-$minusN"
                return Result.failure("bash: dirs: $shown: directory stack index out of range\n", 1)
            }
            val home = ctx.state.env["HOME"] ?: ""
            val path = if (longFormat) fullStack[idx] else formatPath(fullStack[idx], home)
            return Result.success("$path\n")
        }

        val fullStack = listOf(ctx.state.cwd) + stack
        val home = ctx.state.env["HOME"] ?: ""

        val output = when {
            withNumbers -> fullStack.mapIndexed { idx, p -> " $idx  ${if (longFormat) p else formatPath(p, home)}" }.joinToString("\n", postfix = "\n")
            perLine -> fullStack.joinToString("\n", postfix = "\n") { if (longFormat) it else formatPath(it, home) }
            else -> fullStack.joinToString(" ", postfix = "\n") { if (longFormat) it else formatPath(it, home) }
        }
        return Result.success(output)
    }

    private fun formatPath(path: String, home: String): String {
        if (home.isNotEmpty() && path == home) return "~"
        if (home.isNotEmpty() && path.startsWith("$home/")) return "~${path.substring(home.length)}"
        return path
    }

    // ─── complete ────────────────────────────────────────────────────────────

    fun complete(ctx: InterpreterContext, args: List<String>): ExecResult {
        var printMode = false
        var removeMode = false
        var wordlist: String? = null
        val commands = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "-p" -> printMode = true
                "-r" -> removeMode = true
                "-W" -> {
                    i++; if (i >= args.size) return Result.failure("complete: -W: option requires an argument\n", 2)
                    wordlist = args[i]
                }
                "--" -> { i++; commands.addAll(args.drop(i)); break }
                else -> {
                    if (!arg.startsWith("-")) commands.add(arg)
                    else if (!arg.startsWith("-") || arg.length == 1) commands.add(arg)
                }
            }
            i++
        }

        if (removeMode) {
            if (commands.isEmpty()) {
                ctx.state.completions.clear()
                return Result.ok()
            }
            for (cmd in commands) ctx.state.completions.remove(cmd)
            return Result.ok()
        }

        if (printMode) return printCompletions(ctx, commands)

        // No options, no commands: list all
        if (args.isEmpty() || (commands.isEmpty() && wordlist == null)) {
            return printCompletions(ctx, commands)
        }

        for (cmd in commands) {
            if (wordlist != null) ctx.state.completions[cmd] = wordlist
        }
        return Result.ok()
    }

    private fun printCompletions(ctx: InterpreterContext, commands: List<String> = emptyList()): ExecResult {
        val specs = ctx.state.completions
        if (specs.isEmpty()) {
            if (commands.isNotEmpty()) {
                val sb = StringBuilder()
                for (cmd in commands) sb.append("complete: $cmd: no completion specification\n")
                return Result.result("", sb.toString(), 1)
            }
            return Result.ok()
        }
        val output = mutableListOf<String>()
        val targetCommands = if (commands.isNotEmpty()) commands else specs.keys.toList()
        for (cmd in targetCommands) {
            val wordlist = specs[cmd]
            if (wordlist == null) {
                if (commands.isNotEmpty()) {
                    return Result.result(
                        if (output.isNotEmpty()) output.joinToString("\n", postfix = "\n") else "",
                        "complete: $cmd: no completion specification\n",
                        1,
                    )
                }
                continue
            }
            output.add("complete -W $wordlist $cmd")
        }
        return Result.success(if (output.isEmpty()) "" else output.joinToString("\n", postfix = "\n"))
    }

    // ─── compgen ─────────────────────────────────────────────────────────────

    private val SHELL_KEYWORDS = listOf(
        "!", "[[", "]]", "case", "do", "done", "elif", "else", "esac", "fi",
        "for", "function", "if", "in", "then", "time", "until", "while", "{", "}",
    )

    private val SHELL_BUILTINS = listOf(
        ".", ":", "[", "alias", "bg", "bind", "break", "builtin", "caller", "cd",
        "command", "compgen", "complete", "compopt", "continue", "declare", "dirs",
        "disown", "echo", "enable", "eval", "exec", "exit", "export", "false", "fc",
        "fg", "getopts", "hash", "help", "history", "jobs", "kill", "let", "local",
        "logout", "mapfile", "popd", "printf", "pushd", "pwd", "read", "readarray",
        "readonly", "return", "set", "shift", "shopt", "source", "suspend", "test",
        "times", "trap", "true", "type", "typeset", "ulimit", "umask", "unalias",
        "unset", "wait",
    )

    private val COMPGEN_SHOPT_OPTIONS = listOf(
        "autocd", "assoc_expand_once", "cdable_vars", "cdspell", "checkhash",
        "checkjobs", "checkwinsize", "cmdhist", "compat31", "compat32", "compat40",
        "compat41", "compat42", "compat43", "compat44", "complete_fullquote",
        "direxpand", "dirspell", "dotglob", "execfail", "expand_aliases",
        "extdebug", "extglob", "extquote", "failglob", "force_fignore",
        "globasciiranges", "globstar", "gnu_errfmt", "histappend", "histreedit",
        "histverify", "hostcomplete", "huponexit", "inherit_errexit",
        "interactive_comments", "lastpipe", "lithist", "localvar_inherit",
        "localvar_unset", "login_shell", "mailwarn", "no_empty_cmd_completion",
        "nocaseglob", "nocasematch", "nullglob", "progcomp", "progcomp_alias",
        "promptvars", "restricted_shell", "shift_verbose", "sourcepath", "xpg_echo",
    )

    fun compgen(ctx: InterpreterContext, args: List<String>): ExecResult {
        val actionTypes = mutableListOf<String>()
        var wordlist: String? = null
        var prefix = ""
        var suffix = ""
        var searchPrefix: String? = null
        val processedArgs = mutableListOf<String>()

        val validActions = setOf(
            "alias", "arrayvar", "binding", "builtin", "command", "directory",
            "disabled", "enabled", "export", "file", "function", "group",
            "helptopic", "hostname", "job", "keyword", "running", "service",
            "setopt", "shopt", "signal", "stopped", "user", "variable",
        )

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "-v" -> actionTypes.add("variable")
                "-e" -> actionTypes.add("export")
                "-f" -> actionTypes.add("file")
                "-d" -> actionTypes.add("directory")
                "-k" -> actionTypes.add("keyword")
                "-A" -> {
                    i++; if (i >= args.size) return Result.failure("compgen: -A: option requires an argument\n", 2)
                    val actionType = args[i]
                    if (actionType !in validActions) return Result.failure("compgen: $actionType: invalid action name\n", 2)
                    actionTypes.add(actionType)
                }
                "-W" -> {
                    i++; if (i >= args.size) return Result.failure("compgen: -W: option requires an argument\n", 2)
                    wordlist = args[i]
                }
                "-P" -> { i++; if (i >= args.size) return Result.failure("compgen: -P: option requires an argument\n", 2); prefix = args[i] }
                "-S" -> { i++; if (i >= args.size) return Result.failure("compgen: -S: option requires an argument\n", 2); suffix = args[i] }
                "-o" -> {
                    i++; if (i >= args.size) return Result.failure("compgen: -o: option requires an argument\n", 2)
                    val opt = args[i]
                    if (opt !in listOf("plusdirs", "dirnames", "default", "filenames", "nospace", "bashdefault", "noquote")) {
                        return Result.failure("compgen: $opt: invalid option name\n", 2)
                    }
                    // Ignored in simplified compgen
                }
                "-F", "-C", "-X", "-G" -> {
                    i++; if (i >= args.size) return Result.failure("compgen: ${args[i - 1]}: option requires an argument\n", 2)
                    // Skip - not fully implemented
                }
                "--" -> { i++; processedArgs.addAll(args.drop(i)); break }
                else -> {
                    if (!arg.startsWith("-")) processedArgs.add(arg)
                }
            }
            i++
        }

        searchPrefix = processedArgs.firstOrNull()

        val completions = mutableListOf<String>()
        val completionSet = mutableSetOf<String>()

        fun addAll(values: Iterable<String>) {
            for (v in values) {
                if (searchPrefix != null && !v.startsWith(searchPrefix)) continue
                if (v in completionSet) continue
                completions.add(v)
                completionSet.add(v)
            }
        }

        for (actionType in actionTypes) {
            when (actionType) {
                "variable" -> addAll(getVariableNames(ctx))
                "export" -> addAll(getExportedVariableNames(ctx))
                "function" -> addAll(ctx.state.functions.keys)
                "builtin" -> addAll(SHELL_BUILTINS)
                "keyword" -> addAll(SHELL_KEYWORDS)
                "alias" -> addAll(getAliasNames(ctx))
                "shopt" -> addAll(COMPGEN_SHOPT_OPTIONS)
                "helptopic" -> addAll(SHELL_BUILTINS)
                "directory" -> addAll(getDirectoryCompletions(ctx, searchPrefix))
                "file" -> addAll(getFileCompletions(ctx, searchPrefix))
                "user" -> addAll(listOf("root", "nobody"))
                "command" -> addAll(getCommandCompletions(ctx))
                else -> { /* unknown action - skip */ }
            }
        }

        if (wordlist != null) {
            val ifs = ctx.state.env["IFS"] ?: " \t\n"
            val ifsSet = ifs.toSet()
            val words = mutableListOf<String>()
            var current = StringBuilder()
            var j = 0
            while (j < wordlist.length) {
                val ch = wordlist[j]
                if (ch == '\\' && j + 1 < wordlist.length) {
                    current.append(wordlist[j + 1])
                    j += 2
                } else if (ch in ifsSet) {
                    if (current.isNotEmpty()) { words.add(current.toString()); current = StringBuilder() }
                    j++
                } else {
                    current.append(ch)
                    j++
                }
            }
            if (current.isNotEmpty()) words.add(current.toString())
            addAll(words)
        }

        if (completions.isEmpty() && searchPrefix != null) {
            return Result.result("", "", 1)
        }

        val sb = StringBuilder()
        for (c in completions.sorted()) {
            sb.append(prefix).append(c).append(suffix).append("\n")
        }
        return Result.success(sb.toString())
    }

    private fun getVariableNames(ctx: InterpreterContext): List<String> {
        val vars = mutableListOf<String>()
        for (key in ctx.state.env.keys) {
            if (key.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))) vars.add(key)
        }
        ctx.state.arrays?.keys?.let { vars.addAll(it) }
        return vars.sorted()
    }

    private fun getExportedVariableNames(ctx: InterpreterContext): List<String> {
        val exported = ctx.state.exportedVars ?: return emptyList()
        return exported.filter { it in ctx.state.env }.sorted()
    }

    private fun getAliasNames(ctx: InterpreterContext): List<String> {
        val aliases = mutableListOf<String>()
        for (key in ctx.state.env.keys) {
            if (key.startsWith("BASH_ALIAS_")) aliases.add(key.removePrefix("BASH_ALIAS_"))
        }
        return aliases.sorted()
    }

    private fun getDirectoryCompletions(ctx: InterpreterContext, prefix: String?): List<String> {
        val dirs = mutableListOf<String>()
        try {
            var searchDir = ctx.state.cwd
            var matchPrefix = prefix ?: ""
            if (prefix != null) {
                val lastSlash = prefix.lastIndexOf('/')
                if (lastSlash != -1) {
                    val dirPart = prefix.substring(0, lastSlash).ifEmpty { "/" }
                    matchPrefix = prefix.substring(lastSlash + 1)
                    searchDir = if (dirPart.startsWith("/")) dirPart else "${ctx.state.cwd}/$dirPart"
                }
            }
            val entries = try { ctx.fs.readdir(searchDir) } catch (e: Exception) { return emptyList() }
            for (entry in entries) {
                val fullPath = "$searchDir/$entry"
                try {
                    val stat = ctx.fs.stat(fullPath)
                    if (stat.isDirectory) {
                        if (matchPrefix.isEmpty() || entry.startsWith(matchPrefix)) {
                            if (prefix?.contains('/') == true) {
                                val lastSlash = prefix.lastIndexOf('/')
                                dirs.add(prefix.substring(0, lastSlash + 1) + entry)
                            } else {
                                dirs.add(entry)
                            }
                        }
                    }
                } catch (e: Exception) { /* ignore */ }
            }
        } catch (e: Exception) { /* ignore */ }
        return dirs.sorted()
    }

    private fun getFileCompletions(ctx: InterpreterContext, prefix: String?): List<String> {
        val files = mutableListOf<String>()
        try {
            var searchDir = ctx.state.cwd
            var matchPrefix = prefix ?: ""
            if (prefix != null) {
                val lastSlash = prefix.lastIndexOf('/')
                if (lastSlash != -1) {
                    val dirPart = prefix.substring(0, lastSlash).ifEmpty { "/" }
                    matchPrefix = prefix.substring(lastSlash + 1)
                    searchDir = if (dirPart.startsWith("/")) dirPart else "${ctx.state.cwd}/$dirPart"
                }
            }
            val entries = try { ctx.fs.readdir(searchDir) } catch (e: Exception) { return emptyList() }
            for (entry in entries) {
                if (matchPrefix.isEmpty() || entry.startsWith(matchPrefix)) {
                    if (prefix?.contains('/') == true) {
                        val lastSlash = prefix.lastIndexOf('/')
                        files.add(prefix.substring(0, lastSlash + 1) + entry)
                    } else {
                        files.add(entry)
                    }
                }
            }
        } catch (e: Exception) { /* ignore */ }
        return files.sorted()
    }

    private fun getCommandCompletions(ctx: InterpreterContext): List<String> {
        val commands = mutableSetOf<String>()
        commands.addAll(SHELL_BUILTINS)
        commands.addAll(ctx.state.functions.keys)
        for (key in ctx.state.env.keys) {
            if (key.startsWith("BASH_ALIAS_")) commands.add(key.removePrefix("BASH_ALIAS_"))
        }
        commands.addAll(SHELL_KEYWORDS)
        return commands.toList().sorted()
    }

    // ─── compopt ─────────────────────────────────────────────────────────────

    fun compopt(ctx: InterpreterContext, args: List<String>): ExecResult {
        val enableOptions = mutableListOf<String>()
        val disableOptions = mutableListOf<String>()
        val commands = mutableListOf<String>()

        val validOptions = setOf("bashdefault", "default", "dirnames", "filenames", "noquote", "nosort", "nospace", "plusdirs")

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "-D", "-E" -> { /* default/empty-line completion - apply to all, then nospace below */ }
                "-o" -> {
                    i++; if (i >= args.size) return Result.failure("compopt: -o: option requires an argument\n", 2)
                    val opt = args[i]
                    if (opt !in validOptions) return Result.failure("compopt: $opt: invalid option name\n", 2)
                    enableOptions.add(opt)
                }
                "+o" -> {
                    i++; if (i >= args.size) return Result.failure("compopt: +o: option requires an argument\n", 2)
                    val opt = args[i]
                    if (opt !in validOptions) return Result.failure("compopt: $opt: invalid option name\n", 2)
                    disableOptions.add(opt)
                }
                "--" -> { i++; commands.addAll(args.drop(i)); break }
                else -> {
                    if (!arg.startsWith("-") && !arg.startsWith("+")) commands.add(arg)
                }
            }
            i++
        }

        if (commands.isNotEmpty()) {
            for (cmd in commands) {
                val current = ctx.state.completionOptions.getOrPut(cmd) { LinkedHashSet() }
                current.addAll(enableOptions)
                current.removeAll(disableOptions)
            }
            return Result.ok()
        }

        return Result.failure("compopt: not currently executing completion function\n")
    }
}