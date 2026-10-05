package com.openmine

import java.net.HttpURLConnection
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicReference

class ModelRequestControl(budgetMillis:Long=180000) : AutoCloseable {
    private val connection=AtomicReference<HttpURLConnection?>(null)
    @Volatile var reason:String?=null
        private set
    private val timer=Timer("openmine-model-budget",true).apply{schedule(object:TimerTask(){
        override fun run(){cancelRequest("Request exceeded the 180-second total budget. Try a shorter prompt or a warm/smaller model.")}
    },budgetMillis)}
    fun attach(value:HttpURLConnection){checkActive();connection.set(value);if(reason!=null){value.disconnect();checkActive()}}
    fun cancelRequest(message:String="Cancelled by user"){
        if(reason==null)reason=message
        timer.cancel()
        connection.getAndSet(null)?.let{value->Thread({value.disconnect()},"openmine-model-cancel").apply{isDaemon=true;start()}}
    }
    fun checkActive(){check(reason==null){reason.orEmpty()}}
    override fun close(){timer.cancel();connection.getAndSet(null)?.disconnect()}
}
