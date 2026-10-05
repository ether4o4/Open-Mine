package com.openmine

import com.openmine.sandbox.RootfsDownloader
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.GZIPOutputStream

class RootfsArchiveTest {
    private fun archive(directory:File,name:String,declaredSize:Int,data:ByteArray):File {
        val file=File(directory,"fixture.tar.gz")
        val header=ByteArray(512)
        name.toByteArray().copyInto(header,0)
        "0000644".toByteArray().copyInto(header,100)
        declaredSize.toString(8).padStart(11,'0').toByteArray().copyInto(header,124)
        header[156]='0'.code.toByte()
        GZIPOutputStream(file.outputStream()).use{it.write(header);it.write(data)}
        return file
    }
    @Test fun refusesSiblingPrefixTraversal(){
        val directory=kotlin.io.path.createTempDirectory("archive-test").toFile()
        try{
            val root=File(directory,"root").apply{mkdirs()}
            val archive=archive(directory,"../root-escape/file",3,"bad".toByteArray())
            RootfsDownloader().extractTarGz(archive,root)
            assertFalse(File(directory,"root-escape/file").exists())
        }finally{directory.deleteRecursively()}
    }
    @Test fun rejectsTruncatedFile(){
        val directory=kotlin.io.path.createTempDirectory("archive-test").toFile()
        try{
            val root=File(directory,"root")
            val archive=archive(directory,"data",100,"short".toByteArray())
            assertThrows(java.io.IOException::class.java){RootfsDownloader().extractTarGz(archive,root)}
        }finally{directory.deleteRecursively()}
    }
}
