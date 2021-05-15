package net.cydhra.acromantula.features.mapper

import net.cydhra.acromantula.workspace.WorkspaceService
import net.cydhra.acromantula.workspace.database.DatabaseManager
import net.cydhra.acromantula.workspace.database.mapping.ContentMappingReference
import net.cydhra.acromantula.workspace.database.mapping.ContentMappingReferenceDelegate
import net.cydhra.acromantula.workspace.database.mapping.ContentMappingSymbol
import net.cydhra.acromantula.workspace.database.mapping.ContentMappingSymbolTypeDelegate
import net.cydhra.acromantula.workspace.filesystem.FileEntity
import net.cydhra.acromantula.workspace.filesystem.events.AddedResourceEvent
import org.apache.logging.log4j.LogManager

object MapperFeature {

    private val logger = LogManager.getLogger()

    /**
     * Registered factories for generating mappings
     */
    private val mappingFactories = mutableListOf<MappingFactory>()

    private val registeredSymbolTypes =
        mutableMapOf<AcromantulaSymbolType, ContentMappingSymbolTypeDelegate>()

    private val registeredReferenceTypes =
        mutableMapOf<AcromantulaReferenceType, ContentMappingReferenceDelegate>()

    /**
     * Register a factory to generate mappings
     */
    fun registerMappingFactory(factory: MappingFactory) {
        this.mappingFactories += factory
    }

    /**
     * Register a symbol type at the database
     */
    fun registerSymbolType(symbolType: AcromantulaSymbolType) {
        val typeDelegate = DatabaseManager.registerContentMappingSymbolType(symbolType.symbolType)
        this.registeredSymbolTypes[symbolType] = typeDelegate
    }

    /**
     * Register a symbol reference type at the database. The referenced symbol type must have already been registered.
     *
     * @param referenceType the reference type implementation
     * @param symbolType the symbol type referenced by this reference type
     */
    fun insertReferenceIntoDatabase(referenceType: AcromantulaReferenceType, symbolType: AcromantulaSymbolType) {
        if (!this.registeredSymbolTypes.containsKey(symbolType))
            throw IllegalStateException("the symbol type of this reference has not been registered yet")

        if (this.registeredReferenceTypes.containsKey(referenceType))
            throw IllegalStateException("this reference tyoe has been registered before")

        val delegate = DatabaseManager.registerContentMappingReferenceType(
            referenceType.referenceType,
            this.registeredSymbolTypes[symbolType]!!
        )
        this.registeredReferenceTypes[referenceType] = delegate
    }

    fun findSymbolsInFile(file: FileEntity): List<ContentMappingSymbol> {
        TODO("not implemented yet")
    }

    fun findReferencesInFile(file: FileEntity): List<ContentMappingReference> {
        TODO("not implemented yet")
    }

    /**
     * Generate mappings for a given file entity and its content
     */
    private fun generateMappings(file: FileEntity, content: ByteArray) {
        this.mappingFactories
            .filter { it.handles(file, content) }
            .forEach {
                logger.debug("generating [${it.name}] mappings for ${file.name}...")

                // start the mapper and forget the deferred result
                WorkspaceService.getWorkerPool().submit { it.generateMappings(file, content) }.start()
            }
    }

    @Suppress("RedundantSuspendModifier")
    internal suspend fun onFileAdded(event: AddedResourceEvent) {
        generateMappings(event.file, event.content)
    }
}