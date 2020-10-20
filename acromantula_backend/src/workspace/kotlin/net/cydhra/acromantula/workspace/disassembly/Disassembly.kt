package net.cydhra.acromantula.workspace.disassembly

import net.cydhra.acromantula.workspace.filesystem.FileEntity
import net.cydhra.acromantula.workspace.filesystem.FileTable
import net.cydhra.acromantula.workspace.java.JavaClass
import net.cydhra.acromantula.workspace.java.JavaClassTable
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.IntIdTable

internal object DisassemblyTable : IntIdTable() {
    val codeFile = reference("codefile", JavaClassTable)
    val disassembly = reference("disassembly", FileTable)
    val created = datetime("created")
}

/**
 * Output of a [net.cydhra.acromantula.features.disassemble.Disassembler]. The disassembly remains valid until the
 * source file is modified.
 */
class Disassembly(entityID: EntityID<Int>) : IntEntity(entityID) {
    companion object : IntEntityClass<Disassembly>(DisassemblyTable)

    var codeFile by JavaClass referencedOn DisassemblyTable.codeFile
        internal set
    var disassembly by FileEntity referencedOn DisassemblyTable.disassembly
        internal set
    var created by DisassemblyTable.created
        internal set
}