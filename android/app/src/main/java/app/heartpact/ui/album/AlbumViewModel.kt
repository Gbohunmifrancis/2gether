package app.heartpact.ui.album

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.heartpact.HeartpactApplication
import app.heartpact.album.AlbumReminderWorker
import app.heartpact.album.AlbumSyncWorker
import app.heartpact.album.PhotoImporter
import app.heartpact.data.ApiResult
import app.heartpact.data.apiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class AlbumViewModel(application: Application) : AndroidViewModel(application) {
    private val app = (application as HeartpactApplication).container
    private val queue = app.albumQueue
    private val importer = PhotoImporter(application.contentResolver, queue)
    private val work = WorkManager.getInstance(application)
    private val _ui = MutableStateFlow(AlbumUi(local = queue.photos.value))
    val ui: StateFlow<AlbumUi> = _ui.asStateFlow()
    /** A sync finished while this screen watched it: only then is its outcome worth announcing. */
    private var watchedRunning = false

    init {
        viewModelScope.launch { queue.photos.collect { photos -> _ui.update { it.copy(local = photos) } } }
        viewModelScope.launch { app.pushEvents.collect { if (it == "memoriesSynced") refresh() } }
        viewModelScope.launch { work.getWorkInfosForUniqueWorkFlow(AlbumSyncWorker.NAME).collect { onWork(it.lastOrNull()) } }
    }

    private fun onWork(info: WorkInfo?) {
        when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> { watchedRunning = true; _ui.update { it.copy(sync = SyncState.WaitingForNetwork, result = null) } }
            WorkInfo.State.RUNNING -> {
                watchedRunning = true
                val p = info.progress
                _ui.update { it.copy(sync = SyncState.Running(p.getInt(AlbumSyncWorker.DONE, 0), p.getInt(AlbumSyncWorker.TOTAL, it.waiting.size)), result = null) }
            }
            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                val announce = watchedRunning
                watchedRunning = false
                val d = info.outputData
                val result = when {
                    !announce -> null
                    info.state == WorkInfo.State.SUCCEEDED -> AlbumText.outcome(
                        d.getInt(AlbumSyncWorker.SYNCED, 0), d.getInt(AlbumSyncWorker.REFUSED, 0), d.getInt(AlbumSyncWorker.LEFT, 0),
                        d.getBoolean(AlbumSyncWorker.FULL, false), d.getString(AlbumSyncWorker.STOPPED),
                    )
                    else -> "The sync stopped. Your photos are still on this phone; try again when you're ready."
                }
                _ui.update { it.copy(sync = SyncState.Idle, result = result ?: it.result) }
                if (announce) refresh()
            }
            null -> _ui.update { it.copy(sync = SyncState.Idle) }
        }
    }

    /** The shared album's first page, on-this-day and usage. Local photos never wait for this. */
    fun refresh() = viewModelScope.launch {
        val page = apiCall(app.json) { app.api.memories(null) }
        when (page) {
            is ApiResult.Ok -> _ui.update { it.copy(shared = page.value.items, nextCursor = page.value.nextCursor, note = null, storageMissing = false, loaded = true) }
            is ApiResult.Failure -> _ui.update {
                if (page.code == STORAGE_MISSING) it.copy(storageMissing = true, note = null, loaded = true) else it.copy(note = page.message, loaded = true)
            }
            ApiResult.Offline -> _ui.update { it.copy(note = if (it.shared.isEmpty()) "You're offline. Your shared album shows up when you're back; photos you add stay on this phone meanwhile." else null, loaded = true) }
        }
        if (page !is ApiResult.Ok) return@launch
        (apiCall(app.json) { app.api.albumSync() } as? ApiResult.Ok)?.let { status -> _ui.update { it.copy(usage = status.value.usage) } }
        (apiCall(app.json) { app.api.onThisDay(LocalDate.now().toString()) } as? ApiResult.Ok)?.let { days -> _ui.update { it.copy(onThisDay = days.value) } }
    }

    fun loadMore() {
        val cursor = _ui.value.nextCursor ?: return
        if (_ui.value.loadingMore) return
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val page = apiCall(app.json) { app.api.memories(cursor) }
            _ui.update {
                if (page is ApiResult.Ok) it.copy(shared = (it.shared + page.value.items).distinctBy { m -> m.id }, nextCursor = page.value.nextCursor, loadingMore = false)
                else it.copy(loadingMore = false)
            }
        }
    }

    /** From the photo picker: copy each into the app, then tell the server how much is waiting. */
    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val skipped = withContext(Dispatchers.IO) { uris.map { importer.import(it) }.filterIsInstance<PhotoImporter.Result.Skipped>() }
            if (skipped.isNotEmpty()) {
                val first = skipped.first().reason
                _ui.update { it.copy(result = if (skipped.size == 1) first else "${AlbumText.photos(skipped.size)} weren't added. $first") }
            }
            AlbumReminderWorker.schedule(getApplication())
            app.albumSync.reportPending()
        }
    }

    fun remove(clientId: String) = viewModelScope.launch { queue.delete(clientId); app.albumSync.reportPending() }

    fun retry(clientId: String) = viewModelScope.launch { queue.retry(clientId); app.albumSync.reportPending() }

    fun sync() {
        if (_ui.value.storageMissing || _ui.value.waiting.isEmpty()) return
        watchedRunning = true
        _ui.update { it.copy(result = null) }
        AlbumSyncWorker.enqueue(getApplication())
    }

    /** What a phone-only tile draws: the small thumbnail when there is one. */
    fun localModel(photo: app.heartpact.album.LocalPhoto): Any = queue.thumb(photo)?.takeIf { it.exists() } ?: queue.file(photo)

    fun dismissResult() = _ui.update { it.copy(result = null) }

    /** For the sync sheet: on Wi-Fi (or another unmetered network) right now? */
    fun onUnmetered(): Boolean {
        val manager = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private companion object {
        const val STORAGE_MISSING = "memories.storage_unavailable"
    }
}
