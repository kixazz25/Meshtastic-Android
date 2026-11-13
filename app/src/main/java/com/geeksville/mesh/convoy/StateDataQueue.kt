package com.geeksville.mesh.convoy

import android.content.Context
import com.geeksville.mesh.grouptrack.GtSettingsAndroid
import com.grouptrack.shared.state.GtQueueRow
import com.grouptrack.shared.state.GtStateDataQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

// SWITCH17G2E2-2026-11-12 (2.8 step 17, G2e-2): front door to the shared GtStateDataQueue (cert 17e2 in the cert runner).
// Every public name of the 2.7e object is kept: the import controller and the picker are unchanged.
// The shared queue works on GtQueueRow; JsonRow wraps the same JSONObject, so the rows the import saves are the rows the queue changes.

/** A JSONObject seen through the shared queue's row interface (the same object, not a copy). */
private class JsonRow(val json: JSONObject) : GtQueueRow {
    override fun optString(key: String): String = json.optString(key)
    override fun optBoolean(key: String): Boolean = json.optBoolean(key)
    override fun put(key: String, value: String) { json.put(key, value) }
    override fun put(key: String, value: Boolean) { json.put(key, value) }
    override fun remove(key: String) { json.remove(key) }
}

object StateDataQueue {
    private const val PREFS = "state_data_queue"

    /** The shared queue. abort = HomeStateImportController.abortDownload: closes the connection of a stopped file (2.7e STOPNOW). */
    private val core = GtStateDataQueue(Dispatchers.IO) { r ->
        // STEP17G2E2F-2026-11-12: ON ITS OWN THREAD, as 2.7e did (STOPNOW-2026-11-02:
        // Thread { HomeStateImportController.abortDownload(row) }.start()). The G2e-2 switch called it
        // directly, so a read waiting on a slow network would close on whatever thread pressed Stop.
        // A port changes nothing: the thread goes back.
        Thread { HomeStateImportController.abortDownload((r as JsonRow).json) }.start()
    }

    /** Files downloaded at the same time, 1-4. */
    val maxAtOnce: MutableStateFlow<Int> = core.maxAtOnce
    /** True while the queue has settled with a failed or stopped file -- the panel asks: Retry, or Cancel the import. */
    val waitingOnRider: MutableStateFlow<Boolean> = core.waitingOnRider
    /** True while [run] is running. */
    val active: MutableStateFlow<Boolean> = core.active

    private fun store(ctx: Context) = GtSettingsAndroid(ctx).store(PREFS)

    /** The saved maximum (the same preferences file as 2.7e: "state_data_queue"). */
    fun load(ctx: Context) = core.load(store(ctx))

    /** Set the maximum at once (1-4) and save it. */
    fun setMax(ctx: Context, v: Int) = core.setMax(store(ctx), v)

    /** Stop a downloading file (it ends as "stopped"; Retry starts it again). */
    fun stop(id: String) = core.stop(id)

    /** Start a failed or stopped file again. */
    fun retry(id: String) = core.retry(id)

    /** Stop every file and end the import before processing. */
    fun cancelAll() = core.cancelAll()

    /**
     * Download every row not already completed, [maxAtOnce] at a time. [fetch] does one row (blocking; true = the file is here).
     * [onChange] is called about once a second and on every change -- the caller saves the record and publishes progress.
     * Returns true when every row is completed; false when the rider cancels.
     */
    suspend fun run(list: List<JSONObject>, fetch: (JSONObject) -> Boolean, onChange: () -> Unit): Boolean =
        core.run(list.map { JsonRow(it) }, { r -> fetch((r as JsonRow).json) }, onChange)
}
