package me.devnatan.katan.api

import br.com.devsrsouza.eventkt.EventScope
import kotlinx.coroutines.CoroutineScope
import me.devnatan.katan.api.account.AccountManager
import me.devnatan.katan.api.cache.Cache
import me.devnatan.katan.api.cli.CommandManager
import me.devnatan.katan.api.game.Game
import me.devnatan.katan.api.game.GameManager
import me.devnatan.katan.api.plugin.Plugin
import me.devnatan.katan.api.plugin.PluginManager
import me.devnatan.katan.api.security.permission.PermissionManager
import me.devnatan.katan.api.server.Server
import me.devnatan.katan.api.server.ServerManager
import me.devnatan.katan.api.service.ServiceManager
import org.slf4j.event.Level

/**
 * Interface that provides access to Katan handlers without having
 * direct access to it, useful for plugins and extensions.
 */
interface Katan : CoroutineScope {

    companion object {

        /**
         * Returns the current version of the Katan.
         */
        val VERSION = Version(1, 0, 0, "alpha.1")

        const val ENVIRONMENT_PROPERTY = "katan.environment"
        const val LOCALE_PROPERTY = "katan.locale"

    }

    /**
     * Platform on which this instance is currently running.
     */
    val platform: Platform

    /**
     * Returns the environment mode that has been defined for this instance.
     */
    val environment: KatanEnvironment

    /**
     * Query, creation and management of accounts
     */
    val accountManager: AccountManager

    /**
     * Returns the Katan services manager.
     */
    val serviceManager: ServiceManager

    /**
     * The caching provider for that instance.
     * Can return an uninitialized value, use [Cache.isAvailable] to check.
     */
    val cache: Cache<Any>

    /**
     * Returns the Katan [Server] manager.
     */
    val serverManager: ServerManager

    /**
     * Returns the Katan [Plugin] manager.
     */
    val pluginManager: PluginManager

    /**
     * Returns the Katan [Game] manager.
     */
    val gameManager: GameManager

    /**
     * Returns the event publisher for this entire instance.
     */
    val eventBus: EventScope

    /**
     * Returns the Katan message translator.
     */
    val translator: Translator

    /**
     * Returns the Katan permissions manager.
     * It is used to register custom permissions for plugins.
     */
    val permissionManager: PermissionManager

    val commandManager: CommandManager

}

/**
 * Represents the mode of the environment in which Katan is running.
 * @property value the name of the environment
 */
inline class KatanEnvironment(private val value: String) {

    companion object {

        const val DEVELOPMENT = "dev"
        const val TESTING = "test"
        const val STAGING = "stage"
        const val PRODUCTION = "production"

        val ALL: Array<String> get() = arrayOf(DEVELOPMENT, TESTING, STAGING, PRODUCTION)

    }

    /**
     * Returns `true` if the current environment mode is [DEVELOPMENT],
     */
    fun isDevelopment(): Boolean {
        return value == DEVELOPMENT
    }

    /**
     * Returns `true` if the current environment mode is in [TESTING] or [STAGING].
     */
    fun isTesting(): Boolean {
        return value == TESTING || value == STAGING
    }

    /**
     * Returns `true` if the current environment mode is in [PRODUCTION].
     */
    fun isProduction(): Boolean {
        return value == PRODUCTION
    }

    override fun toString(): String {
        return value
    }

}

/**
 * Returns the default recommended logging level for this environment.
 */
fun KatanEnvironment.defaultLogLevel(): Level = when {
    isDevelopment() || isTesting() -> Level.TRACE
    else -> Level.INFO
}