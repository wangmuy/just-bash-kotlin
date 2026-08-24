package com.justbash.commands

import com.justbash.Command

/**
 * Registry of ported core commands, mirroring just-bash `commands/registry.ts`
 * (the subset of commands ported to Kotlin).
 */
object Registry {
    /** All ported core commands, keyed by name. */
    fun createCoreCommands(): List<Command> = listOf(
        EchoCommand,
        CatCommand,
        PwdCommand,
        LsCommand,
        HeadCommand,
        TailCommand,
        MkdirCommand,
        RmdirCommand,
        RmCommand,
        CpCommand,
        MvCommand,
        LnCommand,
        TouchCommand,
        ChmodCommand,
        WcCommand,
        SortCommand,
        UniqCommand,
        BasenameCommand,
        DirnameCommand,
        TrCommand,
        PrintenvCommand,
        EnvCommand,
        TrueCommand,
        FalseCommand,
        GrepCommand,
        // Second-round commands (Java stdlib and pure-logic ports)
        Base64Command,
        PrintfCommand,
        SeqCommand,
        SleepCommand,
        StatCommand,
        DateCommand,
        Md5sumCommand,
        Sha1sumCommand,
        Sha256sumCommand,
        ExprCommand,
        ReadlinkCommand,
        // Third-round commands backed by JVM libraries
        AwkCommand,
        JqCommand,
        DiffCommand,
        FindCommand,
        SedCommand,
        CurlCommand,
        TimeoutCommand,
        TarCommand,
        GzipCommand,
        GunzipCommand,
        ZcatCommand,
        YqCommand,
        RgCommand,
        XanCommand,
    )

    /** Commands keyed by name, for direct lookup. */
    fun createCoreCommandMap(): Map<String, Command> =
        createCoreCommands().associateBy { it.name }
}
