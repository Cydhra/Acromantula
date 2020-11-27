package net.cydhra.acromantula.rpc

import kotlinx.coroutines.runBlocking
import net.cydhra.acromantula.ImportMessage
import net.cydhra.acromantula.bus.EventBroker
import net.cydhra.acromantula.bus.events.ApplicationStartupEvent
import net.cydhra.acromantula.commands.CommandDispatcherService
import net.cydhra.acromantula.rpc.services.ImportServer
import net.cydhra.acromantula.rpc.services.ImportService
import net.cydhra.acromantula.rpc.services.asGrpcServer
import net.cydhra.acromantula.workspace.WorkspaceService
import org.junit.jupiter.api.Test

class ImportServerTest {

    @Test
    fun testImport() {
        runBlocking {
            EventBroker.registerService(EventBroker)
            EventBroker.registerService(WorkspaceService)
            EventBroker.registerService(CommandDispatcherService)
            EventBroker.fireEvent(ApplicationStartupEvent())

            ImportServer().asGrpcServer(18080) {
                ImportService.forAddress("localhost", 18080, channelOptions = { usePlaintext() }).use { client ->
                    client.import(
                        ImportMessage.newBuilder()
                            .setDirectoryId(-1)
                            .setFileUrl(this.javaClass.classLoader.getResource("testfile.txt")!!.toExternalForm())
                            .build()
                    )
                }
            }
        }
    }
}