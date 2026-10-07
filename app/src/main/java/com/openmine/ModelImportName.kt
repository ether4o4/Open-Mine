package com.openmine

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/** Provider names are labels, never trusted filesystem paths. Legacy model files stay untouched. */
object ModelImportName {
    fun filename(displayName:String?):String {
        val basename=displayName.orEmpty().replace('\\','/').substringAfterLast('/').trim()
        val stem=if(basename.endsWith(".gguf",ignoreCase=true))basename.dropLast(5)else basename
        val sanitized=buildString{
            var bytes=0
            val codepoints=stem.codePoints().iterator()
            while(codepoints.hasNext()){
                val point=codepoints.nextInt()
                val text=if(Character.isLetterOrDigit(point) || point<=127 && point.toChar() in " ._-")String(Character.toChars(point))else "_"
                val count=text.toByteArray(Charsets.UTF_8).size
                if(bytes+count>150)break
                append(text);bytes+=count
            }
        }.trim(' ','.','_','-').ifBlank{"model"}
        return "$sanitized.gguf"
    }

    /** Atomically publishes complete bytes without ever replacing an existing destination. */
    fun publish(partial:File,directory:File,displayName:String?):File {
        require(partial.parentFile.canonicalFile==directory.canonicalFile){"Model import is outside its destination directory."}
        require(Files.isRegularFile(partial.toPath(),LinkOption.NOFOLLOW_LINKS)){"Model import data is missing or is a symbolic link."}
        val preferred=filename(displayName)
        repeat(16){attempt->
            val name=if(attempt==0)preferred else preferred.removeSuffix(".gguf")+"-"+UUID.randomUUID()+".gguf"
            val destination=File(directory,name)
            try{
                // Creating the hard link fails atomically with EEXIST. A check-then-rename could
                // overwrite a model created between the existence check and rename syscall.
                Files.createLink(destination.toPath(),partial.toPath())
                partial.delete()
                return destination
            }catch(_:FileAlreadyExistsException){ /* Preserve the collision and choose a unique suffix. */ }
        }
        error("Could not allocate a unique model filename. Existing models were preserved.")
    }
}

object ModelImportSelection {
    fun afterRefresh(previous:List<String>,current:List<String>,selected:String):String {
        val old=previous.toSet()
        val added=if(current.size>previous.size)current.filterNot{it in old}else emptyList()
        return added.lastOrNull() ?: selected.takeIf{it in current} ?: current.firstOrNull().orEmpty()
    }
}
