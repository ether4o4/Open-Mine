package com.openmine

/** Validation does not rewrite a command: the bytes displayed for review are the bytes executed. */
object RuntimeCommandPolicy {
    fun validate(command:String):String {
        require(command.isNotBlank()){"Enter a shell command."}
        val bytes=command.toByteArray(Charsets.UTF_8)
        require(bytes.size<=8192){"Command exceeds the 8192-byte limit. Shorten it before review."}
        require(String(bytes,Charsets.UTF_8)==command){"Command contains invalid Unicode."}
        require(command.none{it.code<32 && it!='\n' && it!='\t' || it.code==127}){"Command contains unsupported control characters."}
        return command
    }
}
