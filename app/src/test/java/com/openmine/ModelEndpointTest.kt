package com.openmine

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ModelEndpointTest {
    @Test fun acceptsOllamaBuiltInAndLocalHostWithAnyValidPort() {
        for(base in listOf(ModelEndpoint.OLLAMA,ModelEndpoint.BUILT_IN,"http://localhost:11434/v1"," HTTP://LOCALHOST:11434/v1/ ","http://127.0.0.1:38657/v1")) {
            val uri=ModelEndpoint.chatUri(base)
            assertEquals("/v1/chat/completions",uri.path)
            assertEquals("http",uri.scheme)
            assertTrue(uri.host in setOf("127.0.0.1","localhost"))
        }
        assertEquals(11434,ModelEndpoint.chatUri(ModelEndpoint.OLLAMA).port)
        assertEquals(ModelEndpoint.OLLAMA+"/chat/completions",ModelEndpoint.chatUri("http://127.0.0.1:11434").toString())
    }
    @Test fun preservesRemoteHttpsBaseAndNormalizesCasing() {
        assertEquals("https://example.com/gateway/v1/chat/completions",ModelEndpoint.chatUri("HTTPS://EXAMPLE.COM/gateway/v1/").toString())
    }
    @Test fun rejectsRemoteHttpSpoofsAndMalformedUrls() {
        for(base in listOf("http://127,0,0,1:11434/v1","http://127,0.0.1:11434/v1","127.0.0.1:11434/v1","http://192.168.1.2:11434/v1","http://localhost.evil.test:11434/v1","http://127.0.0.1.evil.test/v1","http://127.0.0.2/v1","http://user@localhost/v1","http://localhost:65536/v1","http://localhost:0/v1","http://localhost/v1?token=secret","http://localhost/v1#fragment","http://localhost/api/chat","http://localhost/v1/chat/completions","http://localhost/a/../v1","http://localhost/%76%31","file:///v1")) {
            assertTrue(base,runCatching{ModelEndpoint.chatUri(base)}.isFailure)
        }
    }
    @Test fun androidCleartextPolicyMatchesOnlySupportedLocalHosts() {
        val source=listOf(File("src/main/res/xml/network_security_config.xml"),File("app/src/main/res/xml/network_security_config.xml")).first{it.isFile}
        val xml=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(source)
        assertEquals("false",xml.getElementsByTagName("base-config").item(0).attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
        val domains=xml.getElementsByTagName("domain")
        assertEquals(setOf("localhost","127.0.0.1"),(0 until domains.length).map{domains.item(it).textContent.trim()}.toSet())
        for(i in 0 until domains.length){
            assertEquals("false",domains.item(i).attributes.getNamedItem("includeSubdomains").nodeValue)
            assertEquals("true",domains.item(i).parentNode.attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
        }
    }
}
