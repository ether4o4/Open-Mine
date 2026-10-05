package com.openmine

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LoopbackStreamTest {
    @Test fun ollamaCompatiblePathStreamsBeforeCompletionWithOneSubmission() {
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val received=CountDownLatch(1)
        val requestLine=AtomicReference<String>()
        val failure=AtomicReference<Throwable>()
        val worker=Thread{
            try{server.accept().use{socket->
                socket.soTimeout=4000
                val reader=socket.getInputStream().bufferedReader()
                requestLine.set(reader.readLine())
                var length=0
                while(true){val header=reader.readLine() ?: error("Missing HTTP headers");if(header.isEmpty())break
                    if(header.startsWith("Content-Length:",true))length=header.substringAfter(':').trim().toInt()
                }
                repeat(length){check(reader.read()>=0)}
                val out=socket.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"first\"},\"finish_reason\":null}]}\r\n\r\n".toByteArray());out.flush()
                check(received.await(3,TimeUnit.SECONDS)){"Text not delivered before stream completion"}
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\" second\"},\"finish_reason\":\"stop\"}]}\r\n\r\ndata: [DONE]\r\n\r\n".toByteArray());out.flush()
            }}catch(e:Throwable){failure.set(e)}
        }.apply{isDaemon=true;start()}
        val control=ModelRequestControl(5000)
        try{
            val uri=ModelEndpoint.chatUri("http://127.0.0.1:${server.localPort}/v1")
            val connection=uri.toURL().openConnection() as HttpURLConnection
            control.attach(connection);connection.requestMethod="POST";connection.doOutput=true;connection.readTimeout=4000
            val body="{\"model\":\"qwen2.5:3b\",\"stream\":true}".toByteArray()
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use{it.write(body)}
            val answer=connection.inputStream.bufferedReader().use{reader->ChatStream.read(reader,{control.checkActive()}){text->if(text=="first")received.countDown()}}
            assertEquals("first second",answer.getString("content"))
            assertEquals("POST /v1/chat/completions HTTP/1.1",requestLine.get())
            worker.join(1000);assertNull(failure.get())
        }finally{control.close();server.close();worker.join(1000)}
    }
}
