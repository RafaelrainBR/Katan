package me.devnatan.katan.cli

import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.devnatan.katan.api.Katan
import me.devnatan.katan.api.account.AccountManager
import me.devnatan.katan.api.cli.RegisteredCommand
import me.devnatan.katan.api.server.ServerManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class KatanCLI(val katan: Katan) {

    companion object {

        const val KATAN_COMMAND = "katan"

    }

    val logger: Logger = LoggerFactory.getLogger(KatanCLI::class.java)!!

    val serverManager: ServerManager get() = katan.serverManager
    val accountManager: AccountManager get() = katan.accountManager
    val pluginManager get() = katan.pluginManager

    private var running = false
    private val command = KatanCommand(this)
    val console = KatanConsole(logger)

    // Clikt's localization is not recursive, so we will have
    // to declare the object here and spread it out for commands.
    val localization by lazy {
        KatanLocalization(katan.translator)
    }

    /*
        Due to Clikt (command framework) does not have support for coroutines
        we have a CoroutineScope,  it is used in all commands and the dispatcher
        is specified by the command itself. Canceling this scope cancels all pending tasks.

        SupervisorJob is required in case of failure of any child, he will ensure that
        in case that failure occurs the rest of the jobs that are being
        performed in the background do not fail and the scope is not canceled.
     */
    val coroutineScope = CoroutineScope(SupervisorJob() + CoroutineName("KatanCLI"))

    // TODO: wrap native help to plugins help command
    fun init() {
        running = true
        var line: String?
        do {
            line = readLine()
            try {
                val args = line?.split(" ") ?: continue
                val cmd = args.getOrNull(0) ?: continue
                if (cmd.equals(KATAN_COMMAND, true)) {
                    if (args.size == 1)
                        throw PrintHelpMessage(command)

                    command.parse(args.subList(1, args.size))
                    continue
                }

                // search for plugins commands
                val match = katan.commandManager.getCommand(cmd) as? RegisteredCommand
                    ?: throw PrintHelpMessage(command)

                katan.commandManager.executeCommand(match.plugin, match, cmd, args.subList(1, args.size).toTypedArray())
            } catch (e: PrintHelpMessage) {
                logger.info(e.command.getFormattedHelp())
            } catch (e: UsageError) {
                logger.error(e.message)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        } while (line != null)
    }

    fun close() {
        check(running)
        coroutineScope.cancel()
    }

    fun translate(key: String, vararg args: Any): String {
        return katan.translator.translate(key, *args)
    }

}