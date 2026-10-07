package com.openmine

import org.json.JSONObject

/** A validated, immutable capability request. Model-generated text is never executable code. */
data class ModelToolProposal(val id:String,val name:String,val arguments:String,val effect:String) {
    companion object {
        fun from(call:JSONObject):ModelToolProposal {
            val id=call.getString("id")
            require(id.isNotBlank() && id.length<=256){"Invalid tool call ID; no tool ran."}
            val function=call.getJSONObject("function")
            val name=function.getString("name")
            val raw=function.getString("arguments")
            require(raw.length<=16384){"Tool arguments exceed limit; no tool ran."}
            val args=JSONObject(raw)
            val keys=args.keys().asSequence().toSet()
            val effect=when(name){
                "search_library"->{
                    require(keys==setOf("query") && args.get("query") is String){"Library search accepts only a text query; no tool ran."}
                    require(args.getString("query").isNotBlank() && args.getString("query").length<=512){"Search query must be 1–512 characters; no tool ran."}
                    "Read up to five matching local records and send their content to the selected model server."
                }
                "linux_system_info"->{
                    require(keys.isEmpty()){"System information accepts no arguments; no command ran."}
                    "Run the fixed command uname -a in Open Mine's Linux environment and send its output to the selected model server."
                }
                else->throw IllegalArgumentException("Unsupported tool '$name'; no external action was executed.")
            }
            return ModelToolProposal(id,name,args.toString(2),effect)
        }
    }
}

object ReviewedModelTools {
    /** The action is not evaluated unless this exact proposal receives approval. */
    fun execute(proposal:ModelToolProposal,review:(ModelToolProposal)->Boolean,action:()->String):String =
        if(review(proposal))action() else "User declined ${proposal.name}. No tool was executed. Do not claim this action ran."
}
