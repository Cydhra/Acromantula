package net.cydhra.acromantula.features.view

import net.cydhra.acromantula.workspace.WorkspaceService
import net.cydhra.acromantula.workspace.disassembly.FileRepresentation
import net.cydhra.acromantula.workspace.filesystem.FileEntity
import org.apache.logging.log4j.LogManager
import java.io.OutputStream

/**
 * This feature holds a set of [ViewGeneratorStrategy]s that can be used to generate human-readable interpretations
 * of binary data, like disassemblies or image data.
 */
object GenerateViewFeature {

    private val logger = LogManager.getLogger()

    private val registeredGenerators = mutableMapOf<String, ViewGeneratorStrategy>()

    /**
     * Generate a representation for [fileEntity] using the strategy identified by [viewType]. If no view can be
     * generated for the file, because the strategy does not handle this file, `null` is returned.
     */
    fun generateView(fileEntity: FileEntity, viewType: String): FileRepresentation? {
        val representation = WorkspaceService.queryRepresentation(fileEntity, viewType)
        if (representation != null) {
            logger.debug("reusing existing representation from ${representation.created}")
            return representation
        }

        if (!(registeredGenerators[viewType]?.handles(fileEntity)
                ?: throw IllegalArgumentException("View generator \"$viewType\" does not exist"))
        )
            return null

        logger.info("creating representation for \"${fileEntity.name}\"")
        return registeredGenerators[viewType]!!.generateView(fileEntity)
    }

    /**
     * Exports binary content of a file representation into [outputStream]. The output stream is not closed afterwards.
     */
    fun exportView(representation: FileRepresentation, outputStream: OutputStream) {
        outputStream.write(WorkspaceService.getRepresentationContent(representation).readBytes())
    }

    /**
     * Register a [ViewGeneratorStrategy] at this feature
     */
    fun registerViewGenerator(viewGeneratorStrategy: ViewGeneratorStrategy) {
        this.registeredGenerators[viewGeneratorStrategy.viewType] = viewGeneratorStrategy
    }
}