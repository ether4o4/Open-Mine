package com.openmine

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelImportDoctorTest {
    private val GiB=1024L*1024*1024
    private fun gguf(architecture:String?,version:Int=3,extraFirst:Boolean=true):ByteArray {
        val out=ByteArrayOutputStream()
        fun le(n:Int)=ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        fun str(s:String){val b=s.toByteArray();out.write(le(8).putLong(b.size.toLong()).array());out.write(b)}
        out.write("GGUF".toByteArray());out.write(le(4).putInt(version).array())
        out.write(le(8).putLong(10).array());out.write(le(8).putLong(if(architecture==null)1 else 3).array())
        if(extraFirst){str("general.file_type");out.write(le(4).putInt(4).array());out.write(le(4).putInt(15).array())
            str("tokenizer.ggml.tokens");out.write(le(4).putInt(9).array());out.write(le(4).putInt(8).array());out.write(le(8).putLong(2).array());str("a");str("b")}
        if(architecture!=null){str("general.architecture");out.write(le(4).putInt(8).array());str(architecture)}
        return out.toByteArray()
    }
    private fun diagnose(name:String,header:ByteArray?,size:Long?=GiB,free:Long=20*GiB,ram:Long=12*GiB)=
        ModelImportDoctor.diagnose(ImportCandidate(name,size,"application/octet-stream",header),free,ram)

    @Test fun validModelAfterSkippingArraysIsImportable(){
        val d=diagnose("Gemma-4-E2B-Q4_K_M.gguf",gguf("gemma4"))
        assertEquals(ImportProblem.NONE,d.problem);assertTrue(d.canImport);assertEquals("gemma4",d.header?.architecture)
    }
    @Test fun liquidArchitectureIsAcceptedBecauseTheBundledEngineImplementsIt(){
        assertEquals(ImportProblem.NONE,diagnose("LFM-1.7B-Q4.gguf",gguf("lfm2")).problem)
        assertTrue("lfm2" in GgufMetadata.supportedArchitectures)
    }
    @Test fun unknownArchitectureIsACompatibilityProblem(){
        val d=diagnose("future.gguf",gguf("rwkv9"))
        assertEquals(ImportProblem.COMPATIBILITY,d.problem);assertFalse(d.canImport)
    }
    @Test fun savedHuggingFacePageIsExplained(){
        val d=diagnose("model.gguf","<!DOCTYPE html><html><head>".toByteArray())
        assertEquals(ImportProblem.FORMAT,d.problem);assertTrue(d.summary.contains("web page"))
    }
    @Test fun lfsPointerSafetensorsPickleAndZipAreRecognised(){
        assertTrue(diagnose("m.gguf","version https://git-lfs.github.com/spec/v1\noid sha256:abc".toByteArray()).summary.contains("LFS"))
        val st=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(100).put("{\"__me".toByteArray()).array()
        assertTrue(diagnose("model.safetensors",st).summary.contains("safetensors"))
        assertTrue(diagnose("pytorch_model.bin",byteArrayOf(0x80.toByte(),2,0x7d)).summary.contains("pickle"))
        assertTrue(diagnose("model.zip","PK\u0003\u0004rest".toByteArray()).summary.contains("ZIP"))
    }
    @Test fun unreadableProviderIsAnAccessProblem(){
        val d=ModelImportDoctor.diagnose(ImportCandidate("x.gguf",null,null,null,"SecurityException: Permission Denial"),GiB,GiB)
        assertEquals(ImportProblem.ACCESS,d.problem);assertTrue(d.summary.contains("Permission Denial"))
    }
    @Test fun unfinishedAndSplitDownloadsAreFilenameProblems(){
        assertEquals(ImportProblem.FILENAME,diagnose("model.gguf.crdownload",gguf("llama")).problem)
        val split=diagnose("Big-Q4_K_M-00001-of-00003.gguf",gguf("llama"))
        assertEquals(ImportProblem.FILENAME,split.problem);assertTrue(split.summary.contains("3-part"))
    }
    @Test fun storageAndMemoryLimitsAreReported(){
        assertEquals(ImportProblem.STORAGE,diagnose("m.gguf",gguf("qwen3"),size=4*GiB,free=GiB).problem)
        assertEquals(ImportProblem.STORAGE,diagnose("m.gguf",gguf("qwen3"),size=9*GiB).problem)
        assertEquals(ImportProblem.MEMORY,diagnose("m.gguf",gguf("qwen3"),size=7*GiB,ram=6*GiB).problem)
        val tight=diagnose("m.gguf",gguf("qwen3"),size=5*GiB,ram=8*GiB)
        assertTrue(tight.canImport);assertTrue(tight.warnings.any{it.contains("high")})
    }
    @Test fun headerWithArchitectureBeyondProbeIsImportableWithWarning(){
        val d=diagnose("m.gguf",gguf(null))
        assertTrue(d.canImport);assertTrue(d.warnings.any{it.contains("architecture")})
    }
    @Test fun unsupportedVersionIsRejected(){
        assertEquals(ImportProblem.COMPATIBILITY,diagnose("m.gguf",gguf("llama",version=1)).problem)
    }
    @Test fun truncatedHeaderNeverThrowsOutOfDiagnosis(){
        val full=gguf("llama")
        for(n in listOf(1,4,10,24,30,full.size-3))assertNotNull(diagnose("m.gguf",full.copyOf(n)).problem)
    }
    @Test fun pastedPathsExplainThatPathsDoNotGrantAccess(){
        assertTrue(ModelImportDoctor.interpretPastedLocation("/storage/emulated/0/Download/qwen.gguf").contains("Downloads"))
        assertTrue(ModelImportDoctor.interpretPastedLocation("/sdcard/Models/qwen.gguf").contains("does not let apps open storage paths"))
        assertTrue(ModelImportDoctor.interpretPastedLocation("content://com.android.providers.downloads.documents/document/msf%3A12").contains("picker"))
        assertTrue(ModelImportDoctor.interpretPastedLocation("model.safetensors").contains("Only .gguf"))
    }
}
