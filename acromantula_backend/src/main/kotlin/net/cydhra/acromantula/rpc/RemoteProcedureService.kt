package net.cydhra.acromantula.rpc

import net.cydhra.acromantula.bus.EventBroker
import net.cydhra.acromantula.bus.Service
import net.cydhra.acromantula.bus.events.ApplicationStartupEvent
import org.apache.logging.log4j.LogManager

/**
 * Server that translates remote procedure calls into command invocations.
 * TODO: report back command results to caller
 */
object RemoteProcedureService : Service {
    override val name: String = "RPC Server"

    private val logger = LogManager.getLogger()

    override suspend fun initialize() {
        logger.info("running RPC services...")

        EventBroker.registerEventListener(ApplicationStartupEvent::class, this::onStartUp)
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun onStartUp(@Suppress("UNUSED_PARAMETER") event: ApplicationStartupEvent) {
    }
}