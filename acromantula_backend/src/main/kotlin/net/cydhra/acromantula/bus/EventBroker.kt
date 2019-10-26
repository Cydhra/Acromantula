package net.cydhra.acromantula.bus

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import net.cydhra.acromantula.bus.event.Event
import net.cydhra.acromantula.bus.service.Service
import org.apache.logging.log4j.LogManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

/**
 * Central event bus. This is the core of the application where all services are registered and all events are
 * dispatched. Components should not talk to each other but only to the broker using events. The broker
 * handles service and listener registration thread-safe and dispatches events in parallel.
 */
object EventBroker : Service {
    override val name: String = "event-broker"

    private val logger = LogManager.getLogger()

    /**
     * A list of all services that are registered at the application
     */
    private val registeredServices = mutableListOf<Service>()

    /**
     * A map that maps registered event listeners to their registering service. The listeners are mapped to their
     * services, so they are not called before the service is registered. The listeners are paired with a [KClass]
     * instance of the event class that is listened to.
     */
    private val registeredEventHandlers = mutableMapOf<Service,
            MutableList<Pair<KClass<out Event>, suspend (Event) -> Unit>>>()

    private val singleThreadPool = Executors.newSingleThreadExecutor()

    private val singleThreadScope = CoroutineScope(singleThreadPool.asCoroutineDispatcher())

    /**
     * Single thread context for critical modifications of event handler list and service list
     */
    private val singleThreadContext = singleThreadScope.coroutineContext

    private val eventIdCounter = AtomicInteger()

    /**
     * Default handling for exceptions during event dispatch, so the logger reports any errors.
     */
    private val eventExceptionHandler = CoroutineExceptionHandler { _, exception ->
        logger.error("error during event dispatch", exception)
    }

    override suspend fun initialize() {

    }

    /**
     * Register a new service at the system. The service is initialized before it is registered. As soon as it is
     * registered, event dispatching considers the service.
     */
    suspend fun registerService(service: Service) {
        logger.debug("attempting to register service: ${service.name}")
        service.initialize()

        withContext(singleThreadContext) {
            try {
                registeredServices += service
                logger.info("registered service: ${service.name}")
            } catch (t: Throwable) {
                logger.error("error during service registration", t)
            }
        }
    }

    /**
     * Fire an event into the event bus. This method will synchronously call all event handlers and may suspend until
     * all the handlers have finished their execution. The method will then return. Events that finish in a result or
     * error may contain such, if attached by one or more handlers.
     */
    suspend fun fireEvent(event: Event) {
        val handlers = withContext(singleThreadContext) {
            registeredEventHandlers.values.flatten()
                    .filter { (type, _) -> type == event.javaClass }
        }

        handlers.forEach { (_, handler) -> handler(event) }
    }

    /**
     * Register a listener function for a given event class. May suspend to synchronize
     */
    suspend fun <T : Event> registerEventListener(
        service: Service,
        eventClass: KClass<T>,
        listener: suspend (T) -> Unit
    ) {
        withContext(singleThreadContext) {
            @Suppress("UNCHECKED_CAST")
            registeredEventHandlers.getOrPut(service, { mutableListOf() })
                .add(Pair(eventClass, listener as (suspend (Event) -> Unit)))
        }
    }

    private suspend fun shutdown() {
        logger.info("shutdown event broker thread")
        singleThreadPool.shutdown()
    }
}