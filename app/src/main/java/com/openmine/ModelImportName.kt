package com.openmine

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
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

    /** Publish a completed same-directory copy; serialize app imports and preserve collisions. */
    @Synchronized fun publish(partial:File,directory:File,displayName:String?):File {
        require(partial.parentFile.canonicalFile==directory.canonicalFile){"Model import is outside its destination directory."}
        require(Files.isRegularFile(partial.toPath(),LinkOption.NOFOLLOW_LINKS)){"Model import data is missing or is a symbolic link."}
        val preferred=filename(displayName)
        repeat(16){attempt->
            val name=if(attempt==0)preferred else preferred.removeSuffix(".gguf")+"-"+UUID.randomUUID()+".gguf"
            val destination=File(directory,name)
            try{
                // Android can reject hard links in app storage. A same-directory move uses
                // rename, requiring neither a hard link nor another multi-gigabyte copy.
                // Do not add ATOMIC_MOVE: it may replace a destination even without
                // REPLACE_EXISTING. Runtime jobs and this method serialize app imports.
                Files.move(partial.toPath(),destination.toPath())
                return destination
            }catch(_:FileAlreadyExistsException){ /* Preserve the collision and choose a unique suffix. */ }
            catch(failure:FileSystemException){
                val reason=failure.reason?.takeIf{it.isNotBlank()}?.let{": $it"}.orEmpty()
                throw IOException("Could not finish saving the imported model (${failure.javaClass.simpleName}$reason). The original selected file was not changed.",failure)
            }
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
