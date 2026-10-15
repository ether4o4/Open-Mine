package com.openmine

import java.net.HttpURLConnection
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicReference

class ModelRequestCancelledException(message:String): IllegalStateException(message)

class ModelRequestControl(budgetMillis:Long=180000) : AutoCloseable {
    private val connection=AtomicReference<HttpURLConnection?>(null)
    private val cancellation=AtomicReference<String?>(null)
    val reason:String? get()=cancellation.get()
    private val timer=Timer("openmine-model-budget",true)
    init {
        require(budgetMillis>0){"Request budget must be positive."}
        timer.schedule(object:TimerTask(){
            override fun run(){cancelRequest("Request exceeded its ${budgetMillis/1000}-second budget. Try a shorter prompt or a warm/smaller model.")}
        },budgetMillis)
    }
    fun attach(value:HttpURLConnection){checkActive();connection.set(value);if(reason!=null){value.disconnect();checkActive()}}
    fun detach(value:HttpURLConnection){connection.compareAndSet(value,null)}
    fun cancelRequest(message:String="Cancelled by user"){
        cancellation.compareAndSet(null,message)
        timer.cancel()
        connection.getAndSet(null)?.let{value->Thread({value.disconnect()},"openmine-model-cancel").apply{isDaemon=true;start()}}
    }
    fun checkActive(){reason?.let{throw ModelRequestCancelledException(it)}}
    override fun close(){timer.cancel();connection.getAndSet(null)?.disconnect()}
}
