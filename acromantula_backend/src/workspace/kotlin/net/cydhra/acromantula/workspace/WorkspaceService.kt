package net.cydhra.acromantula.workspace

import net.cydhra.acromantula.bus.EventBroker
import net.cydhra.acromantula.bus.Service
import net.cydhra.acromantula.bus.events.ApplicationShutdownEvent
import net.cydhra.acromantula.pool.WorkerPool
import net.cydhra.acromantula.workspace.database.DatabaseManager
import net.cydhra.acromantula.workspace.disassembly.FileRepresentation
import net.cydhra.acromantula.workspace.disassembly.FileRepresentationTable
import net.cydhra.acromantula.workspace.filesystem.ArchiveEntity
import net.cydhra.acromantula.workspace.filesystem.FileEntity
import net.cydhra.acromantula.workspace.filesystem.FileTable
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.lookup.StrSubstitutor
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * An SQL query to list a directory tree relative to a starting directory selected by `%clause`
 */
private const val RECURSIVE_LIST_FILE_TREE_QUERY = """
    WITH RECURSIVE tree (id, name, parent, is_directory, type, resource, archive, path) AS 
    (
      SELECT id, name, parent, is_directory, type, resource, archive, CAST (id AS VARCHAR) As path
      FROM TreeFile
      WHERE %{clause}
      UNION ALL
        SELECT tf.id, tf.name, tf.parent, tf.is_directory, tf.type, tf.resource, tf.archive,
         (r.path || '.' || CAST  (tf.id AS VARCHAR)) AS path
        FROM TreeFile AS tf
          INNER JOIN tree AS r
          ON tf.parent = r.id
    )
    SELECT id, name, parent, is_directory, type, resource, archive FROM tree
    ORDER BY path
"""

/**
 * A `%clause` variant for [RECURSIVE_LIST_FILE_TREE_QUERY] for the workspace root
 */
private val FILE_TREE_QUERY_ROOT_CLAUSE = "parent IS NULL"

/**
 * A `%clause` variant for [RECURSIVE_LIST_FILE_TREE_QUERY] for a specified directory id as parent. The id is
 * inserted via prepared statement parameter
 */
private val FILE_TREE_QUERY_RELATIVE_CLAUSE = "parent = (?)"

/**
 * Facade service for the workspace sub-system. Everything related to data storage and data operation is delegated
 * from here.
 */
object WorkspaceService : Service {

    override val name: String = "workspace-service"

    private val logger = LogManager.getLogger()

    /**
     * The client of the current workspace connection.
     */
    private lateinit var workspaceClient: WorkspaceClient

    /**
     * Called upon application startup. Load default workspace and subscribe to events if necessary.
     */
    override suspend fun initialize() {
        workspaceClient = LocalWorkspaceClient(File(".tmp"))
        workspaceClient.initialize()

        EventBroker.registerEventListener(ApplicationShutdownEvent::class, ::onShutdown)
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun onShutdown(@Suppress("UNUSED_PARAMETER") e: ApplicationShutdownEvent) {
        this.workspaceClient.shutdown()
    }

    /**
     * Get the common thread pool for the current workspace
     */
    fun getWorkerPool(): WorkerPool {
        return workspaceClient.workerPool
    }

    /**
     * Get the [DatabaseManager] that is responsible for managing content models
     */
    fun getDatabaseManager(): DatabaseManager {
        return workspaceClient.databaseManager
    }

    /**
     * Add an archive entry into the workspace file tree. Since the archive is only parent to its content, no actual
     * data is associated with it. It is simply a [FileEntity] that has an [ArchiveEntity] associated
     *
     * @param archiveName simple name of the archive
     */
    fun addArchiveEntry(archiveName: String, parent: FileEntity?): FileEntity {
        logger.trace("creating archive entry in file tree: \"$archiveName\"")
        return workspaceClient.databaseClient.transaction {
            val archive = ArchiveEntity.new {}

            FileEntity.new {
                this.name = archiveName
                this.parent = parent
                this.isDirectory = true
                this.archiveEntity = archive
            }
        }
    }

    /**
     * Add a directory entry into the workspace file tree.
     */
    fun addDirectoryEntry(name: String, parent: FileEntity?): FileEntity {
        logger.trace("creating directory entry in file tree: \"$name\"")
        return workspaceClient.databaseClient.transaction {
            FileEntity.new {
                this.name = name
                this.parent = parent
                this.isDirectory = true
            }
        }
    }

    /**
     * Add a file into the workspace. An entry in database is create for reference and the content is uploaded into
     * the workspace.
     *
     * @param name file name
     * @param parent optional parent directory
     * @param content file binary content
     */
    fun addFileEntry(name: String, parent: FileEntity?, content: ByteArray): FileEntity {
        logger.trace("creating file entry in file tree: \"$name\"")
        val fileEntity = workspaceClient.databaseClient.transaction {
            FileEntity.new {
                this.name = name
                this.parent = parent
            }
        }

        workspaceClient.uploadFile(fileEntity, content)
        return fileEntity
    }

    /**
     * Upload the binary data of a file representation into the workspace to cache it for later access. It will be
     * automatically deleted when the reference file changes //TODO
     *
     * @param file reference file for the representation data
     * @param type representation type identifier
     * @param viewData binary data of the representation
     */
    fun addFileRepresentation(file: FileEntity, type: String, viewData: ByteArray): FileRepresentation {
        return workspaceClient.databaseClient.transaction {
            file.refresh()
            logger.trace("creating file view for file: \"${file.name}\"")

            workspaceClient.uploadFileRepresentation(file, type, viewData)
            FileRepresentation
                .find { FileRepresentationTable.file eq file.id and (FileRepresentationTable.type eq type) }
                .single()
        }
    }

    /**
     * Add a file into workspace and parse its contents as a java class and insert its members into database. The
     * file is uploaded into the workspace.
     *
     * @param name file name
     * @param parent optional parent directory
     * @param content file binary content (bytecode)
     */
    fun addClassEntry(name: String, parent: FileEntity?, content: ByteArray): FileEntity {
        val fileEntity = addFileEntry(name, parent, content)

        logger.trace("scheduling class parsing for: \"$name\"")
        @Suppress("DeferredResultUnused")
        this.getWorkerPool().submit {
            workspaceClient.classParser.import(
                content,
                this@WorkspaceService.workspaceClient.databaseClient,
                fileEntity
            )
        }

        return fileEntity
    }

    /**
     * Update content of a file with new content
     */
    fun updateFileEntry(fileEntity: FileEntity, byteArray: ByteArray) {
        workspaceClient.databaseClient.transaction {
            fileEntity.refresh()
            logger.trace("updating file content for: \"${fileEntity.name}\"")
            workspaceClient.updateFile(fileEntity, byteArray)
        }
    }

    /**
     * Get a [FileEntity] instance by a unique path in workspace.
     */
    fun queryPath(path: String): FileEntity {
        return this.workspaceClient.databaseClient.transaction {
            val results = FileEntity.find { FileTable.name like "%$path" }

            when {
                results.empty() -> error("file with path $path does not exist")
                results.count() == 1 -> return@transaction results.first()
                else ->
                    throw IllegalArgumentException("there exist multiple files with this partial path, please specify")
            }
        }
    }

    /**
     * Get a [FileEntity] instance by a file id.
     */
    fun queryPath(id: Int): FileEntity {
        return this.workspaceClient.databaseClient.transaction {
            FileEntity.find { FileTable.id eq id }.firstOrNull()
                ?: error("file with id $id does not exist")
        }
    }

    /**
     * Query a representation of a file. If it does exist, its entity is returned.
     *
     * @param fileEntity reference file for the representation
     * @param viewType type of representation in question
     *
     * @return a [FileRepresentation] instance if the view already exists, `null` otherwise
     */
    fun queryRepresentation(fileEntity: FileEntity, viewType: String): FileRepresentation? {
        return this.workspaceClient.databaseClient.transaction {
            FileRepresentation.find {
                FileRepresentationTable.file eq fileEntity.id and
                        (FileRepresentationTable.type eq viewType)
            }.firstOrNull()
        }
    }

    /**
     * Recursively list files beginning with a root directory in a tree structure. If the root directory is null, the
     * repository root is used.
     */
    fun listFilesRecursively(root: FileEntity? = null): List<FileEntity> {
        return this.workspaceClient.databaseClient.transaction {
            val con = TransactionManager.current().connection

            val statement = if (root == null) {
                val substitutor = StrSubstitutor(mapOf("clause" to FILE_TREE_QUERY_ROOT_CLAUSE))
                    .setVariablePrefix("%{")
                    .setVariableSuffix("}")
                con.prepareStatement(substitutor.replace(RECURSIVE_LIST_FILE_TREE_QUERY))
            } else {
                val substitutor = StrSubstitutor(mapOf("clause" to FILE_TREE_QUERY_RELATIVE_CLAUSE))
                    .setVariablePrefix("%{")
                    .setVariableSuffix("}")
                val statement = con.prepareStatement(substitutor.replace(RECURSIVE_LIST_FILE_TREE_QUERY))
                statement.setInt(1, root.id.value)
                statement
            }

            val rs = statement.executeQuery()
            val entities = mutableListOf<FileEntity>()
            while (rs.next()) {
                entities += FileEntity.wrapRow(
                    ResultRow.create(
                        rs, listOf(
                            FileTable.id,
                            FileTable.name,
                            FileTable.parent,
                            FileTable.isDirectory,
                            FileTable.type,
                            FileTable.archive,
                        )
                    )
                )
            }
            entities
        }
    }

    fun getDirectoryContent(directory: FileEntity?): List<FileEntity> {
        return workspaceClient.databaseClient.transaction {
            val content = if (directory == null) {
                FileEntity.find { FileTable.parent.isNull() }
            } else {
                FileEntity.find { FileTable.parent eq directory.id }
            }

            content.toList()
        }
    }

    /**
     * A debug function to directly execute a raw, unprepared SQL query on the workspace database. This function
     * should not be called in production builds, but is only meant for debugging the database from the CLI
     */
    fun directQuery(query: String): List<List<String>> {
        return this.workspaceClient.databaseClient.directQuery(query)
    }

    /**
     * Get an [InputStream] of the file contents of the given [fileEntity]
     */
    fun getFileContent(fileEntity: FileEntity): InputStream {
        return this.workspaceClient.downloadFile(fileEntity)
    }

    /**
     * Get an [InputStream] that contains a file representation as binary data.
     */
    fun getRepresentationContent(representation: FileRepresentation): InputStream {
        return this.workspaceClient.downloadRepresentation(representation)
    }

    /**
     * Export a file into the given output stream
     */
    fun exportFile(fileEntity: FileEntity, outputStream: OutputStream) {
        this.workspaceClient.exportFile(fileEntity, outputStream)
    }
}