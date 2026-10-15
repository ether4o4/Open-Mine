package com.openmine

import java.io.File
import java.io.RandomAccessFile

data class GgufInfo(val architecture:String,val tensors:Long,val bytes:Long) {
    // Conservative CPU weights + KV/compute budget for the app's bounded context.
    val estimatedMemory:Long get() = bytes + bytes/4 + 512L*1024*1024
}

/** Bounded metadata inspection; weights are never materialized as a byte array/text. */
object GgufMetadata {
    fun inspect(file:File):GgufInfo = RandomAccessFile(file,"r").use { input ->
        fun u32():Long = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        fun u64():Long = java.lang.Long.reverseBytes(input.readLong()).also { require(it>=0){"GGUF length overflows"} }
        fun skip(n:Long) { require(n>=0 && n<=file.length()-input.filePointer){"Truncated GGUF metadata"};input.seek(input.filePointer+n) }
        fun string():String { val n=u64();require(n<=1024*1024){"GGUF string exceeds metadata limit"};val data=ByteArray(n.toInt());input.readFully(data);return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString() }
        var values=0
        fun value(type:Long,depth:Int=0):String? {
            require(++values<=2_000_000 && input.filePointer<=64L*1024*1024){"GGUF metadata exceeds inspection budget"}
            if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Model inspection cancelled")
            require(depth<4){"Nested GGUF metadata exceeds limit"}
            when(type) {
                0L,1L,7L -> skip(1)
                2L,3L -> skip(2)
                4L,5L,6L -> skip(4)
                8L -> return string()
                9L -> { val element=u32();val count=u64();require(count<=2_000_000){"GGUF array exceeds limit"};repeat(count.toInt()){value(element,depth+1)} }
                10L,11L,12L -> skip(8)
                else -> error("Unsupported GGUF metadata type $type")
            }
            return null
        }
        require(input.readInt()==0x47475546){"Not a GGUF file"}
        require(u32() in 2L..3L){"Only GGUF versions 2 and 3 are supported"}
        val tensors=u64();val fields=u64()
        require(tensors in 1..200_000 && fields in 1..100_000){"Invalid GGUF counts"}
        var architecture:String?=null
        repeat(fields.toInt()) {
            require(input.filePointer<=64L*1024*1024){"GGUF metadata exceeds 64 MiB"}
            val key=string();val type=u32();val data=value(type)
            if(key=="general.architecture")architecture=data
        }
        val supported=setOf("llama","qwen2","qwen2moe","qwen3moe","gemma","gemma2","phi2","phi3","mistral","stablelm","starcoder2","gpt2","gptneox","falcon")
        require(architecture in supported){"Unsupported model architecture: ${architecture ?: "missing"}. Use a compatible llama.cpp GGUF."}
        var largestOffset=0L
        repeat(tensors.toInt()) {
            string();val dimensions=u32();require(dimensions in 1L..4L){"Invalid tensor dimensions"}
            var elements=1L
            repeat(dimensions.toInt()){ val dimension=u64();require(dimension>0 && elements<=Long.MAX_VALUE/dimension){"Invalid tensor size"};elements*=dimension }
            require(u32()<=40){"Unsupported GGUF tensor encoding"}
            val offset=u64();largestOffset=maxOf(largestOffset,offset)
        }
        require(largestOffset<file.length()-input.filePointer){"Truncated GGUF tensor data"}
        GgufInfo(architecture!!,tensors,file.length())
    }
}
