package com.openmine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Facts about a selected document that the Kotlin layer read through permitted Android APIs. */
data class ImportCandidate(
    val displayName:String?,
    val sizeBytes:Long?,
    val mimeType:String?,
    /** First bytes of the file; at most [ModelImportDoctor.PROBE_BYTES]. Null when the provider refused to open it. */
    val header:ByteArray?,
    val openError:String?=null,
)

data class GgufHeader(val version:Long,val tensors:Long,val fields:Long,val architecture:String?)

enum class ImportProblem(val label:String) {
    NONE("Ready to import"),
    ACCESS("Open Mine cannot read this file"),
    LOCATION("The file has not been selected through Android's picker"),
    FILENAME("The name suggests an incomplete or split download"),
    FORMAT("This is not GGUF model data"),
    COMPATIBILITY("The GGUF architecture or version is not supported by the bundled engine"),
    STORAGE("Not enough free storage to copy the model"),
    MEMORY("The model is likely too large for this phone's memory"),
}

data class ImportDiagnosis(
    val problem:ImportProblem,
    val summary:String,
    val steps:List<String>,
    val header:GgufHeader?=null,
    val canImport:Boolean=false,
    val warnings:List<String> = emptyList(),
)

/**
 * Diagnoses why a model import fails or would fail. Pure logic over facts the app gathered with the
 * Storage Access Framework; it never touches arbitrary paths and never changes the selected file.
 */
object ModelImportDoctor {
    const val PROBE_BYTES=1024*1024
    const val MAX_MODEL_BYTES=8L*1024*1024*1024
    private val split=Regex("-(\\d{5})-of-(\\d{5})\\.gguf$",RegexOption.IGNORE_CASE)

    fun diagnose(candidate:ImportCandidate,freeStorageBytes:Long,deviceMemoryBytes:Long):ImportDiagnosis {
        val name=candidate.displayName.orEmpty()
        val lower=name.lowercase(Locale.ROOT)
        val header=candidate.header
        if(header==null) return ImportDiagnosis(ImportProblem.ACCESS,
            "The document provider did not let Open Mine open this file${candidate.openError?.let{": $it"}.orEmpty()}.",
            listOf("Choose the file again from the Android picker; a copied path does not grant access.",
                "If it is in a cloud folder, make it available offline or download it to Downloads first.",
                "Some providers expire access; selecting it again creates a fresh grant."))
        if(header.isEmpty() || candidate.sizeBytes==0L) return ImportDiagnosis(ImportProblem.FORMAT,"The selected file is empty.",
            listOf("The download probably failed. Download the GGUF again and wait for it to finish."))
        if(lower.endsWith(".crdownload") || lower.endsWith(".part") || lower.endsWith(".download") || lower.endsWith(".tmp"))
            return ImportDiagnosis(ImportProblem.FILENAME,"'$name' is an unfinished browser download.",
                listOf("Wait for the browser download to complete, then choose the final .gguf file."))
        val format=detectForeignFormat(header)
        if(format!=null) return ImportDiagnosis(ImportProblem.FORMAT,format.first,format.second)
        val parsed=runCatching{probeHeader(header)}
        val gguf=parsed.getOrNull() ?: return ImportDiagnosis(ImportProblem.FORMAT,
            "The file does not start with a valid GGUF header (${parsed.exceptionOrNull()?.message}).",
            listOf("Download a file whose name ends in .gguf from the model's 'Files and versions' list.",
                "Original PyTorch/safetensors weights must be converted to GGUF on a computer first."))
        if(gguf.version !in 2L..3L) return ImportDiagnosis(ImportProblem.COMPATIBILITY,"GGUF version ${gguf.version} is not supported.",
            listOf("Use a GGUF v3 file produced by a recent llama.cpp conversion."),gguf)
        val warnings=mutableListOf<String>()
        split.find(name)?.let{match->
            val part=match.groupValues[1].toInt();val total=match.groupValues[2].toInt()
            if(total>1)return ImportDiagnosis(ImportProblem.FILENAME,"'$name' is part $part of a $total-part split model.",
                listOf("Split models need every part in one folder, which Open Mine's single-file importer does not merge.",
                    "Download a single-file quantization (for example Q4_K_M) of the same model instead."),gguf)
        }
        if(gguf.architecture==null) warnings.add("The architecture key was not in the first ${PROBE_BYTES/1024} KiB; it is verified after copying.")
        else if(gguf.architecture !in GgufMetadata.supportedArchitectures) return ImportDiagnosis(ImportProblem.COMPATIBILITY,
            "Architecture '${gguf.architecture}' is not supported by the bundled llama.cpp engine.",
            listOf("Choose a model built on a supported architecture: ${GgufMetadata.supportedArchitectures.sorted().joinToString()}.",
                "Or run this model on a computer with Ollama/an OpenAI-compatible server and connect it as an endpoint."),gguf)
        val size=candidate.sizeBytes
        if(size==null) warnings.add("The provider did not report a size; storage is checked while copying.")
        else {
            if(size>MAX_MODEL_BYTES) return ImportDiagnosis(ImportProblem.STORAGE,"The file is ${mib(size)} MiB; Open Mine imports at most ${mib(MAX_MODEL_BYTES)} MiB.",
                listOf("Choose a smaller quantization such as Q4_K_M or Q3_K_M."),gguf)
            val needed=size+18L*1024*1024
            if(freeStorageBytes in 0 until needed) return ImportDiagnosis(ImportProblem.STORAGE,
                "Importing needs about ${mib(needed)} MiB free; ${mib(freeStorageBytes)} MiB is available.",
                listOf("Free space on the phone, or delete unused weights in Models, then try again.",
                    "The selected file is copied into private app storage; the original is kept."),gguf)
            val estimate=size+size/4+512L*1024*1024
            if(deviceMemoryBytes>0 && estimate>deviceMemoryBytes*3/4) warnings.add(
                "Estimated runtime memory ${mib(estimate)} MiB is high for ${mib(deviceMemoryBytes)} MiB of RAM; loading may fail. A smaller quantization is safer.")
            if(deviceMemoryBytes>0 && estimate>deviceMemoryBytes) return ImportDiagnosis(ImportProblem.MEMORY,
                "This model needs roughly ${mib(estimate)} MiB but the phone has ${mib(deviceMemoryBytes)} MiB of RAM.",
                listOf("Choose a smaller model or lower-bit quantization.","Or serve it from a computer and connect the endpoint instead."),gguf)
        }
        if(!lower.endsWith(".gguf")) warnings.add("The file is GGUF data but its name does not end in .gguf; it will be saved with a .gguf name.")
        return ImportDiagnosis(ImportProblem.NONE,
            "Valid GGUF v${gguf.version}${gguf.architecture?.let{" · $it"}.orEmpty()} · ${gguf.tensors} tensors${size?.let{" · ${mib(it)} MiB"}.orEmpty()}.",
            listOf("Open Mine will copy the file into private app storage and keep your original unchanged.",
                "Full metadata is re-validated after copying; loading then verifies runtime compatibility."),gguf,true,warnings)
    }

    /** Interprets text the user pasted from Files: Android paths do not grant access, so extract a name to look for. */
    @Suppress("SdCardPath") // Matches user-pasted text; never used to open a path.
    fun interpretPastedLocation(text:String):String {
        val value=text.trim().trim('"','\'')
        if(value.isEmpty())return "Paste a filename or location, or choose the file with the picker."
        val name=value.replace('\\','/').substringAfterLast('/').substringAfterLast("%2F").ifBlank{value}
        val where=when{
            value.startsWith("content://")->"This is a document link. Links copied from another app usually lack permission for Open Mine; choose '$name' with the picker to grant access."
            value.contains("/Download",ignoreCase=true)->"The file appears to be in Downloads. Open the picker, choose Downloads, then select '$name'."
            value.startsWith("/storage/") || value.startsWith("/sdcard") || value.startsWith("/data/")->"Android does not let apps open storage paths directly. Open the picker and browse to the folder containing '$name'."
            else->"Open the picker and search for '$name'."
        }
        val format=if(!name.lowercase(Locale.ROOT).endsWith(".gguf"))" Only .gguf model files can be imported." else ""
        return where+format
    }

    /** Bounded GGUF header parse that reads only the supplied prefix and stops at general.architecture. */
    fun probeHeader(bytes:ByteArray):GgufHeader {
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size>=24){"file is shorter than a GGUF header"}
        require(buffer.getInt()==0x46554747){"missing GGUF magic"}
        val version=buffer.getInt().toLong() and 0xffffffffL
        val tensors=buffer.getLong();val fields=buffer.getLong()
        require(tensors in 0..200_000 && fields in 0..100_000){"invalid tensor or metadata counts"}
        var architecture:String?=null
        fun remaining(n:Long)=n>=0 && n<=buffer.remaining()
        fun string():String? { if(!remaining(8))return null;val n=buffer.getLong();if(n>65536 || !remaining(n))return null
            val data=ByteArray(n.toInt());buffer.get(data);return String(data,Charsets.UTF_8) }
        fun skipValue(type:Int,depth:Int=0):Boolean {
            val width=when(type){0,1,7->1;2,3->2;4,5,6->4;10,11,12->8;else->-1}
            if(width>0){if(!remaining(width.toLong()))return false;buffer.position(buffer.position()+width);return true}
            return when(type){
                8->string()!=null
                9->{ if(depth>2 || !remaining(12))return false;val element=buffer.getInt();val count=buffer.getLong()
                    if(count<0 || count>2_000_000)return false;var ok=true;var i=0L;while(ok && i<count){ok=skipValue(element,depth+1);i++};ok }
                else->false
            }
        }
        var index=0L
        while(index<fields){
            val key=string() ?: break
            if(!remaining(4))break
            val type=buffer.getInt()
            if(key=="general.architecture" && type==8){architecture=string();break}
            if(!skipValue(type))break
            index++
        }
        return GgufHeader(version,tensors,fields,architecture)
    }

    private fun detectForeignFormat(header:ByteArray):Pair<String,List<String>>? {
        val text=String(header,0,minOf(header.size,512),Charsets.ISO_8859_1).trimStart('\uFEFF',' ','\n','\r','\t')
        val lower=text.lowercase(Locale.ROOT)
        return when{
            lower.startsWith("<!doctype") || lower.startsWith("<html") -> "This is a web page, not model weights. The browser saved the Hugging Face page instead of the file." to
                listOf("On Hugging Face open 'Files and versions', tap the .gguf file, then use its download button.","Choose the downloaded .gguf file here.")
            lower.startsWith("version https://git-lfs") -> "This is a Git LFS pointer file, not the weights." to
                listOf("Download the file with the 'download' button on Hugging Face instead of the raw link.")
            header.size>=4 && header[0]=='P'.code.toByte() && header[1]=='K'.code.toByte() -> "This is a ZIP archive (possibly a PyTorch checkpoint)." to
                listOf("Extract a .gguf file from it first, or download a GGUF quantization of this model.")
            header.size>=2 && header[0]==0x80.toByte() && header[1] in 2..5 -> "This is a Python pickle (PyTorch .bin) checkpoint." to
                listOf("Pickle checkpoints can execute code and are never loaded. Download a GGUF quantization instead.")
            isSafetensors(header) -> "This is a safetensors checkpoint, which llama.cpp cannot run directly." to
                listOf("Download a GGUF build of the same model (search the model name plus 'GGUF').")
            lower.startsWith("{") -> "This is a JSON file (for example config.json), not weights." to
                listOf("Choose the .gguf weights file instead of the model's configuration.")
            else -> null
        }
    }
    private fun isSafetensors(header:ByteArray):Boolean {
        if(header.size<9)return false
        val length=ByteBuffer.wrap(header,0,8).order(ByteOrder.LITTLE_ENDIAN).long
        return length in 2..100_000_000 && header[8]=='{'.code.toByte()
    }
    private fun mib(bytes:Long)=bytes/(1024*1024)
}
