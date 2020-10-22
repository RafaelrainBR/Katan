package me.devnatan.katan.core.impl.server

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.PullResponseItem
import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import me.devnatan.katan.api.annotations.UnstableKatanApi
import me.devnatan.katan.api.event.*
import me.devnatan.katan.api.server.*
import me.devnatan.katan.common.exceptions.silent
import me.devnatan.katan.common.server.ServerCompositionsImpl
import me.devnatan.katan.core.KatanCore
import me.devnatan.katan.core.impl.server.compositions.DockerCompositionFactory
import me.devnatan.katan.core.impl.server.compositions.InvisibleCompositionFactory
import me.devnatan.katan.core.repository.ServersRepository
import me.devnatan.katan.docker.DockerCompose
import org.slf4j.LoggerFactory
import java.io.*
import java.time.Duration
import kotlin.system.measureTimeMillis

@Suppress("BlockingMethodInNonBlockingContext")
@OptIn(UnstableKatanApi::class)
class DockerServerManager(
    private val core: KatanCore,
    private val repository: ServersRepository,
) : ServerManager {

    companion object {

        const val COROUTINE_SCOPE_NAME = "Katan::ServerManager"
        const val CONTAINER_NAME_PATTERN = "katan_server_%s"
        const val COMPOSE_ROOT = "composer"
        const val NETWORK_ID = "katan0"
        val logger = LoggerFactory.getLogger(ServerManager::class.java)!!

    }

    private val lastId: AtomicInt = atomic(0)
    private val coroutineScope: CoroutineScope = CoroutineScope(CoroutineName(COROUTINE_SCOPE_NAME))
    private val servers: MutableSet<Server> = hashSetOf()
    val composer = DockerCompose(core.platform, logger)
    private val compositionFactories: MutableList<ServerCompositionFactory> = arrayListOf()
    val compositionFactory = DockerCompositionFactory(core)
    private val localDataManager = ServerLocalDataManager()

    init {
        registerCompositionFactory(DockerCompositionFactory(core))
        File(COMPOSE_ROOT).let { if (!it.exists()) it.mkdirs() }
    }

    internal suspend fun loadServers() {
        try {
            core.docker.inspectNetworkCmd().withNetworkId(NETWORK_ID).exec()
        } catch (e: NotFoundException) {
            core.docker.createNetworkCmd().withName(NETWORK_ID)
                .withAttachable(true)
                .exec()
        }

        try {
            repository.listServers { entities ->
                for (entity in entities) {
                    val server = ServerImpl(
                        entity.id.value,
                        entity.name,
                        ServerTarget.byGame(entity.target),
                        ServerCompositionsImpl()
                    ).apply {
                        container = DockerServerContainer(entity.containerId, core.docker)
                    }

                    server.holders.addAll(entity.holders.mapNotNull {
                        /*
                        If the account is null this is a database synchronization error
                        we can ignore this, but in the future we should alert that kind of thing.
                     */
                        core.accountManager.getAccount(it.account.value.toString())
                    }.map { ServerHolderImpl(it, server) })

                    for (composition in entity.compositions) {
                        val factory = compositionFactories.firstOrNull {
                            it.get(composition.key) != null
                        }

                        if (factory == null) {
                            logger.warn("${server.name}: No factory found for composition ${composition.key}, skipping.")
                            continue
                        }

                        val optionsData =
                            FileInputStream(localDataManager.getCompositionOptions(server, composition.key)).use {
                                core.objectMapper.readValue(it, Map::class.java)
                            } as Map<String, Any>

                        val key = factory.get(composition.key)!!
                        val impl = factory.create(
                            key,
                            factory.generate(key, optionsData)
                        )
                        impl.read(server)
                        (server.compositions as ServerCompositionsImpl)[key] = impl
                    }

                    try {
                        inspectServer(server)
                        servers.add(server)
                    } catch (e: NotFoundException) {
                        logger.warn("Server \"${server.name}\" container was not found, it could not be initialized.")
                    }

                    // Ensuring that the next id is after the id of any server
                    // already registered this will prevent future collisions.
                    lastId.lazySet(server.id)
                }
            }

            if (servers.isNotEmpty())
                logger.debug("${servers.size} server(s) have been loaded.")
        } catch (e: Throwable) {
            // if it's SQLErrorSyntaxException probably the Katan version is different
            throw e.silent(logger)
        }
    }

    override fun getServerList(): Collection<Server> {
        return synchronized(servers) {
            servers.toCollection(hashSetOf())
        }
    }

    override fun getServer(id: Int): Server {
        return servers.first { it.id == id }
    }

    override fun getServer(name: String): Server {
        return servers.first { it.name.equals(name, true) }
    }

    override fun addServer(server: Server): Boolean {
        return synchronized(servers) {
            servers.add(server)
        }
    }

    override fun existsServer(id: Int): Boolean {
        return servers.any { it.id == id }
    }

    override fun existsServer(name: String): Boolean {
        return servers.any { it.name.equals(name, true) }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun createServer(server: Server): Server {
        val id = lastId.incrementAndGet()
        val impl = ServerImpl(id, server.name, server.target, server.compositions)
        impl.container = DockerServerContainer(CONTAINER_NAME_PATTERN.format(id), core.docker)

        core.eventBus.publish(ServerCreateEvent(impl))
        for (composition in impl.compositions) {
            composition.write(impl)
        }

        for (composition in impl.compositions) {
            OutputStreamWriter(
                FileOutputStream(localDataManager.getCompositionOptions(
                    impl, composition.factory.get(
                        composition.key
                    )!!
                ).apply {
                    createNewFile()
                }), Charsets.UTF_8
            ).use { core.objectMapper.writeValue(it, composition.options) }
        }

        core.eventBus.publish(ServerComposedEvent(impl))
        return impl
    }

    override suspend fun registerServer(server: Server) {
        repository.insertServer(server)
    }

    override suspend fun startServer(server: Server) {
        core.eventBus.publish(ServerStartingEvent(server))
        val duration = Duration.ofMillis(measureTimeMillis {
            server.container.start()
        })
        core.eventBus.publish(ServerStartedEvent(server, duration = duration))
    }

    override suspend fun stopServer(server: Server, killAfter: Duration) {
        core.eventBus.publish(ServerStoppingEvent(server))
        val duration = Duration.ofMillis(measureTimeMillis {
            server.container.stop()
        })
        core.eventBus.publish(ServerStoppedEvent(server, duration = duration))
    }

    override suspend fun inspectServer(server: Server) {
        val response = core.docker.inspectContainerCmd(server.container.id).exec()
        val state = response.state.run {
            when {
                dead ?: false -> ServerState.DEAD
                running ?: false -> ServerState.RUNNING
                paused ?: false -> ServerState.PAUSED
                restarting ?: false -> ServerState.RESTARTING
                else -> ServerState.UNKNOWN
            }
        }

        core.eventBus.publish(ServerStateChangeEvent(server, state))
        server.state = state

        val inspection = DockerServerContainerInspection(response)
        core.eventBus.publish(ServerInspectedEvent(server, result = inspection))
        server.container.inspection = inspection
    }

    @ExperimentalCoroutinesApi
    override suspend fun runServerCommand(server: Server, command: String): Flow<String> {
        return callbackFlow {
            val callback = object : ResultCallback.Adapter<Frame>() {
                override fun onNext(frame: Frame) {
                    sendBlocking(frame.payload.toString(Charsets.UTF_8))
                }

                override fun close() {
                    super.close()
                    channel.close()
                }
            }

            core.docker.execStartCmd(core.docker.execCreateCmd(server.container.id).withCmd(command).exec().id)
                .exec(callback)
            awaitClose()
        }
    }

    override fun getCompositionFactory(key: ServerComposition.Key<*>): ServerCompositionFactory? {
        return compositionFactories.filterNot {
            it is InvisibleCompositionFactory
        }.firstOrNull { factory ->
            factory.registrations.any { it == key }
        }
    }

    override fun getCompositionFactory(name: String): ServerCompositionFactory? {
        return compositionFactories.filterNot {
            it is InvisibleCompositionFactory
        }.firstOrNull { factory ->
            factory.get(name) != null
        }
    }

    override fun registerCompositionFactory(factory: ServerCompositionFactory) {
        synchronized(compositionFactories) {
            compositionFactories.add(factory)
        }

        if (factory !is InvisibleCompositionFactory)
            logger.debug(
                "Composition factory registered for ${
                    factory.registrations.entries.joinToString {
                        it.key
                    }
                }."
            )
    }

    override fun unregisterCompositionFactory(factory: ServerCompositionFactory) {
        synchronized(compositionFactories) {
            compositionFactories.remove(factory)
        }

        if (factory !is InvisibleCompositionFactory)
            logger.debug(
                "Unregistered composition factory of ${
                    factory.registrations.entries.joinToString {
                        it.key
                    }
                }."
            )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun pullImage(image: String): Flow<String> = callbackFlow {
        val callback = object : ResultCallback<PullResponseItem> {
            val progress = arrayListOf<String>()
            override fun onNext(response: PullResponseItem) {
                if (response.status === "Waiting" ||
                    (response.id == null || response.id == image.substringAfterLast(":")) &&
                    !response.status!!.contains("Image is up to date")
                ) {
                    sendBlocking("${response.status} (${response.id}) - ignored.")
                    return
                }

                sendBlocking(response.toString())
                if (response.progressDetail != null &&
                    response.progressDetail!!.current != null &&
                    !progress.contains(response.id)
                ) {
                    progress.add(response.id!!)
                    sendBlocking("${response.id}: ${response.status}....")
                }

                if (response.isPullSuccessIndicated)
                    progress.remove(response.id)

                if (progress.isEmpty())
                    channel.close()

                // sendBlocking("${response.status}: ${response.progress}")
            }

            override fun close() {}

            override fun onStart(closeable: Closeable?) {
            }

            override fun onError(cause: Throwable) {
                if (cause is NullPointerException)
                    cause.printStackTrace()

                channel.close(cause)
            }

            override fun onComplete() {}
        }

        core.docker.pullImageCmd(image).exec(callback)

        awaitClose {
            callback.close()
        }
    }

}