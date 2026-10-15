package com.openmine

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.io.DataOutputStream

class GgufMetadataTest {
    @Test fun acceptsQwen3MoeWithMixedQ4KAndQ6KTensors() {
        val file=Files.createTempFile("gguf-qwen3moe-test",".gguf").toFile()
        try {
            DataOutputStream(file.outputStream()).use{out->
                fun u32(n:Int)=out.writeInt(Integer.reverseBytes(n))
                fun u64(n:Long)=out.writeLong(java.lang.Long.reverseBytes(n))
                fun text(s:String){val bytes=s.toByteArray();u64(bytes.size.toLong());out.write(bytes)}
                out.writeBytes("GGUF");u32(3);u64(2);u64(1)
                text("general.architecture");u32(8);text("qwen3moe")
                // Q4_K_M mixes tensor encodings. Each fixture tensor has one 256-element block.
                text("blk.0.ffn_gate_exps.weight");u32(1);u64(256);u32(12);u64(0)
                text("output.weight");u32(1);u64(256);u32(14);u64(160)
                out.write(ByteArray((32-out.size()%32)%32))
                out.write(ByteArray(160+210))
            }
            val info=GgufMetadata.inspect(file)
            assertEquals("qwen3moe",info.architecture)
            assertEquals(2L,info.tensors)
            assertEquals(file.length(),info.bytes)
        }finally{file.delete()}
    }
    @Test fun acceptsBundledEngineQwenAndGemmaArchitectures() {
        for (architecture in listOf("qwen3", "qwen35", "qwen35moe", "qwen3next", "gemma3", "gemma3n", "gemma4")) {
            val file=Files.createTempFile("gguf-architecture-test", ".gguf").toFile()
            try {
                DataOutputStream(file.outputStream()).use { out ->
                    fun u32(n:Int)=out.writeInt(Integer.reverseBytes(n))
                    fun u64(n:Long)=out.writeLong(java.lang.Long.reverseBytes(n))
                    fun text(s:String){val bytes=s.toByteArray();u64(bytes.size.toLong());out.write(bytes)}
                    out.writeBytes("GGUF");u32(3);u64(1);u64(1)
                    text("general.architecture");u32(8);text(architecture)
                    text("test.weight");u32(1);u64(1);u32(0);u64(0)
                    out.write(ByteArray((32-out.size()%32)%32));out.writeFloat(0f)
                }
                assertEquals(architecture,GgufMetadata.inspect(file).architecture)
            } finally { file.delete() }
        }
    }
    @Test fun rejectsHeaderOnlyAndUnsupportedArchitecture() {
        val file=Files.createTempFile("gguf-test",".gguf").toFile()
        try {
            file.writeText("GGUF"+"x".repeat(100))
            assertTrue(runCatching{GgufMetadata.inspect(file)}.isFailure)
            DataOutputStream(file.outputStream()).use{out->
                fun u32(n:Int)=out.writeInt(Integer.reverseBytes(n))
                fun u64(n:Long)=out.writeLong(java.lang.Long.reverseBytes(n))
                fun text(s:String){u64(s.length.toLong());out.write(s.toByteArray())}
                out.writeBytes("GGUF");u32(3);u64(1);u64(1);text("general.architecture");u32(8);text("unsupported")
            }
            val failure=runCatching{GgufMetadata.inspect(file)}.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("Unsupported model architecture"))
        }finally{file.delete()}
    }
    @Test fun validatesMetadataAndRejectsMissingTensorPayload() {
        // The actual downloaded model is exercised by a separate explicit host command
        // and Android inference suite; no absent-model assumption or passing skip here.
        val file=Files.createTempFile("gguf-test",".gguf").toFile()
        try {
            DataOutputStream(file.outputStream()).use{out->
                fun u32(n:Int)=out.writeInt(Integer.reverseBytes(n))
                fun u64(n:Long)=out.writeLong(java.lang.Long.reverseBytes(n))
                fun text(s:String){u64(s.length.toLong());out.write(s.toByteArray())}
                out.writeBytes("GGUF");u32(3);u64(1);u64(1);text("general.architecture");u32(8);text("qwen2")
                text("test.weight");u32(1);u64(1);u32(0);u64(0);out.write(ByteArray(64))
            }
            assertEquals("qwen2",GgufMetadata.inspect(file).architecture)
            file.writeBytes(file.readBytes().dropLast(64).toByteArray())
            assertTrue(runCatching{GgufMetadata.inspect(file)}.isFailure)
        }finally{file.delete()}
    }
}
