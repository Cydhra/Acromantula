package net.cydhra.acromantula.rpc.services

import com.google.api.grpc.kapt.GrpcClient
import com.google.api.grpc.kapt.GrpcServer
import net.cydhra.acromantula.ImportAnswer
import net.cydhra.acromantula.ImportMessage
import net.cydhra.acromantula.commands.CommandDispatcherService
import net.cydhra.acromantula.commands.interpreters.ImportCommandInterpreter

@GrpcClient(definedBy = "net.cydhra.acromantula.ImportService")
interface ImportServiceProxy

@GrpcServer
class ImportServer : ImportService {
    override suspend fun import(request: ImportMessage): ImportAnswer {
        if (request.directoryPath == null) {
            if (request.directoryId < 0) {
                CommandDispatcherService.dispatchCommand(ImportCommandInterpreter(directory = null, request.fileUrl!!))
            } else {
                CommandDispatcherService.dispatchCommand(
                    ImportCommandInterpreter(
                        request.directoryId,
                        request.fileUrl!!
                    )
                )
            }
        } else {
            CommandDispatcherService.dispatchCommand(ImportCommandInterpreter(request.directoryPath, request.fileUrl!!))
        }

        return ImportAnswer.getDefaultInstance()
    }

}