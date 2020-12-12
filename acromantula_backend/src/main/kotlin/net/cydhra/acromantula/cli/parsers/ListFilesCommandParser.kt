package net.cydhra.acromantula.cli.parsers

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import net.cydhra.acromantula.cli.WorkspaceCommandParser
import net.cydhra.acromantula.commands.WorkspaceCommandInterpreter
import net.cydhra.acromantula.commands.interpreters.ListFilesCommandInterpreter
import net.cydhra.acromantula.workspace.filesystem.FileEntity
import net.cydhra.acromantula.workspace.util.TreeNode
import org.apache.logging.log4j.LogManager
import java.util.*

class ListFilesCommandParser(parser: ArgParser) : WorkspaceCommandParser<List<TreeNode<FileEntity>>> {

    companion object {
        private val logger = LogManager.getLogger()
    }

    val directoryPath by parser.storing("-d", "-p", "--path", help = "directory path").default(null)

    val directoryId by parser.storing("-i", "--identifier",
        help = "directory identifier",
        transform = { toInt() }).default(null)

    override fun build(): WorkspaceCommandInterpreter<List<TreeNode<FileEntity>>> =
        if (directoryPath != null)
            ListFilesCommandInterpreter(directoryPath)
        else
            ListFilesCommandInterpreter(directoryId)

    override fun report(result: Optional<out Result<List<TreeNode<FileEntity>>>>) {
        fun dumpFileTree(node: TreeNode<FileEntity>, prefix: String = ""): String {
            return prefix + node.value.name + "\n" + node.childList.joinToString("") {
                dumpFileTree(it, prefix + "\t")
            }
        }

        val response = result.get()
        if (response.isSuccess) {
            val tree = response.getOrThrow().map(::dumpFileTree).joinToString("\n")
            logger.info("File List:\n$tree")
        }
    }
}