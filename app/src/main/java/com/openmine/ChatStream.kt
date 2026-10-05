package com.openmine

import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader

/** Bounded OpenAI SSE decoder, including fragmented function calls. */
object ChatStream {
    fun read(reader:Reader,checkActive:()->Unit={},onText:(String)->Unit={}):JSONObject {
        val text=StringBuilder()
        val calls=sortedMapOf<Int,JSONObject>()
        val event=StringBuilder()
        var total=0;var done=false;var finished=false;var finishReason=""
        fun dispatch(){
            val data=event.toString();event.setLength(0)
            if(data.isBlank())return
            if(data.trim()=="[DONE]"){done=true;return}
            val json=JSONObject(data)
            check(!json.has("error")){"Model stream error: ${json.opt("error")?.toString()?.take(1000)}"}
            val choice=json.optJSONArray("choices")?.optJSONObject(0) ?: return
            if(!choice.isNull("finish_reason")){finished=true;finishReason=choice.optString("finish_reason")}
            val delta=choice.optJSONObject("delta") ?: return
            val content=delta.optString("content","").takeUnless{it=="null"}.orEmpty()
            if(content.isNotEmpty()){text.append(content);check(text.length<=65536){"Model text exceeds limit"};onText(text.toString())}
            val fragments=delta.optJSONArray("tool_calls") ?: return
            for(i in 0 until fragments.length()){
                val fragment=fragments.getJSONObject(i);val index=fragment.getInt("index")
                check(index in 0..2){"Model exceeded three tool calls"}
                val call=calls.getOrPut(index){JSONObject().put("type","function").put("id","").put("function",JSONObject().put("name","").put("arguments",""))}
                if(fragment.has("id"))call.put("id",call.getString("id")+fragment.getString("id"))
                fragment.optJSONObject("function")?.let{part->val function=call.getJSONObject("function")
                    for(key in listOf("name","arguments"))if(part.has(key)){
                        val combined=function.getString(key)+part.getString(key)
                        check(combined.length<=16384){"Tool request exceeds limit"};function.put(key,combined)
                    }
                }
            }
        }
        while(!done){
            val line=StringBuilder();var eof=false
            while(true){checkActive();val char=reader.read();if(char<0){eof=true;break}
                total++;check(total<=1024*1024){"Model stream exceeds 1 MiB"}
                if(char==10)break
                line.append(char.toChar());check(line.length<=65536){"Model event line exceeds limit"}
            }
            val lineText=line.toString().removeSuffix("\r")
            if(lineText.isEmpty()){
                dispatch()
            }else if(lineText.startsWith("data:")){
                if(event.isNotEmpty())event.append('\n')
                event.append(lineText.removePrefix("data:").removePrefix(" "))
                check(event.length<=65536){"Model event exceeds limit"}
            }
            if(eof){dispatch();break}
        }
        check(done || finished){"Model stream ended before completion; partial text is not a completed answer"}
        val answer=JSONObject().put("role","assistant").put("content",text.toString()).put("_finish_reason",finishReason)
        if(calls.isNotEmpty())answer.put("tool_calls",JSONArray(calls.values.toList()))
        return answer
    }
}
