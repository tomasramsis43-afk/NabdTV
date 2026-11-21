package com.nabd.tv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Owns the observable list of scheduled/finished recordings so [AppViewModel] does not have to. */
class RecordingsController(private val ctx: Context, private val store: Store) {
    var items: List<Recording> by mutableStateOf(store.recordings()); private set

    fun refresh() { items = store.recordings() }

    /** Schedules [p]; returns null when it was already scheduled, otherwise whether it clashes with another recording. */
    fun schedule(c: Channel, p: Prog): Boolean? {
        val clash = Recorder.conflicts(store, p.start, p.stop).isNotEmpty()
        val r = Recorder.schedule(ctx, store, c, p)
        refresh()
        return if (r == null) null else clash
    }

    fun cancel(r: Recording) { Recorder.cancel(ctx, store, r.id); refresh() }
    fun delete(r: Recording) { Recorder.delete(ctx, store, r.id); refresh() }
}
