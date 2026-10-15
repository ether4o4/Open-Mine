package com.openmine

/** Writes the newest immutable snapshot, including edits arriving while an older snapshot is on disk. */
class RevisionedCheckpoint<T>(initial:T,private val write:(T)->Unit) {
    private val stateLock=Any()
    private val writeLock=Any()
    private var value=initial
    private var revision=0L
    private var writtenRevision=-1L

    fun update(next:T)=synchronized(stateLock){value=next;revision++}
    val isDirty:Boolean get()=synchronized(stateLock){revision!=writtenRevision}

    /** A failed write leaves its revision dirty. Later flushes retry without losing the latest value. */
    fun flush()=synchronized(writeLock){
        while(true){
            val snapshot=synchronized(stateLock){if(revision==writtenRevision)null else revision to value} ?: return@synchronized
            write(snapshot.second)
            synchronized(stateLock){writtenRevision=snapshot.first}
        }
    }
}
