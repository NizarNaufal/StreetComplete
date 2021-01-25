package de.westnordost.streetcomplete.data.download

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import de.westnordost.streetcomplete.ApplicationConstants
import de.westnordost.streetcomplete.Injector
import de.westnordost.streetcomplete.data.download.tiles.DownloadedTilesDao
import de.westnordost.streetcomplete.data.download.tiles.DownloadedTilesType
import de.westnordost.streetcomplete.ktx.format
import de.westnordost.streetcomplete.util.TilesRect
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.max

/** Downloads all quests and tiles in a given area asynchronously. To use, start the service with
 * the appropriate parameters.
 *
 * Generally, starting a new download cancels the old one. This is a feature; Consideration:
 * If the user requests a new area to be downloaded, he'll generally be more interested in his last
 * request than any request he made earlier and he wants that as fast as possible.
 *
 * The service can be bound to snoop into the state of the downloading process:
 * * To receive progress callbacks
 * * To receive callbacks when new quests are created or old ones removed
 * * To query for the state of the service and/or current download task, i.e. if the current
 * download job was started by the user
 */
class DownloadService : SingleIntentService(TAG), CoroutineScope by CoroutineScope(Dispatchers.IO) {
    @Inject internal lateinit var downloaders: List<Downloader>
    @Inject internal lateinit var downloadedTilesDao: DownloadedTilesDao

    private lateinit var notificationController: DownloadNotificationController

    // interface
    private val binder = Interface()

    // listener
    private var progressListener: DownloadProgressListener? = null

    // state
    private var isPriorityDownload: Boolean = false
    private var isDownloading: Boolean = false
    set(value) {
        field = value
        if (!value || !showNotification) notificationController.hide()
        else notificationController.show()
    }

    private var showNotification = false
    set(value) {
        field = value
        if (!value || !isDownloading) notificationController.hide()
        else notificationController.show()
    }

    init {
        Injector.applicationComponent.inject(this)
    }

    override fun onCreate() {
        super.onCreate()
        notificationController = DownloadNotificationController(
            this, ApplicationConstants.NOTIFICATIONS_CHANNEL_DOWNLOAD, 1)
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onHandleIntent(intent: Intent?, cancelState: AtomicBoolean) {
        if (cancelState.get()) return
        if (intent == null) return
        if (intent.getBooleanExtra(ARG_CANCEL, false)) {
            cancel()
            Log.i(TAG, "Download cancelled")
            return
        }

        val tiles = intent.getSerializableExtra(ARG_TILES_RECT) as TilesRect
        val bbox = tiles.asBoundingBox(ApplicationConstants.DOWNLOAD_TILE_ZOOM)

        if (hasDownloadedAlready(tiles)) {
            Log.i(TAG, "Not downloading (${bbox.asLeftBottomRightTopString}), data still fresh")
        } else {
            isPriorityDownload = intent.hasExtra(ARG_IS_PRIORITY)
            isDownloading = true

            progressListener?.onStarted()

            Log.i(TAG, "Starting download (${bbox.asLeftBottomRightTopString})")
            val time = System.currentTimeMillis()

            var error: Exception? = null
            try {
                runBlocking {
                    for (downloader in downloaders) {
                        // all downloaders run concurrently
                        launch(Dispatchers.IO) {
                            downloader.download(bbox, cancelState)
                        }
                    }
                }
                putDownloadedAlready(tiles)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to download", e)
                error = e
            } finally {
                // downloading flags must be set to false before invoking the callbacks
                isPriorityDownload = false
                isDownloading = false
            }
            if (error != null) {
                progressListener?.onError(error)
            } else {
                progressListener?.onSuccess()
            }
            progressListener?.onFinished()

            val seconds = (System.currentTimeMillis() - time) / 1000.0
            Log.i(TAG, "Finished download (${bbox.asLeftBottomRightTopString}) in ${seconds.format(1)}s")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineContext.cancel()
    }

    private fun hasDownloadedAlready(tiles: TilesRect): Boolean {
        val freshTime = ApplicationConstants.REFRESH_DATA_AFTER
        val ignoreOlderThan = max(0, System.currentTimeMillis() - freshTime)
        return downloadedTilesDao.get(tiles, ignoreOlderThan).contains(DownloadedTilesType.ALL)
    }

    private fun putDownloadedAlready(tiles: TilesRect) {
        downloadedTilesDao.put(tiles, DownloadedTilesType.ALL)
    }

    /** Public interface to classes that are bound to this service  */
    inner class Interface : Binder() {
        fun setProgressListener(listener: DownloadProgressListener?) {
            progressListener = listener
        }

        val isPriorityDownloadInProgress: Boolean get() = isPriorityDownload

        val isDownloadInProgress: Boolean get() = isDownloading

        var showDownloadNotification: Boolean
            get() = showNotification
            set(value) { showNotification = value }
    }

    companion object {
        private const val TAG = "Download"
        const val ARG_TILES_RECT = "tilesRect"
        const val ARG_IS_PRIORITY = "isPriority"
        const val ARG_CANCEL = "cancel"

        fun createIntent(context: Context, tilesRect: TilesRect?, isPriority: Boolean): Intent {
            val intent = Intent(context, DownloadService::class.java)
            intent.putExtra(ARG_TILES_RECT, tilesRect)
            intent.putExtra(ARG_IS_PRIORITY, isPriority)
            return intent
        }

        fun createCancelIntent(context: Context): Intent {
            val intent = Intent(context, DownloadService::class.java)
            intent.putExtra(ARG_CANCEL, true)
            return intent
        }
    }
}