package com.henry.kite.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.henry.kite.KiteApplication
import com.henry.kite.MainActivity
import com.henry.kite.R
import com.henry.kite.airplay.discovery.AirPlayReceiver
import com.henry.kite.airplay.discovery.PairingPath
import com.henry.kite.airplay.discovery.WifiNetworkResolver
import com.henry.kite.airplay.pairing.PairingException
import com.henry.kite.airplay.pairing.TlvError
import com.henry.kite.airplay.session.AirPlay2Session
import com.henry.kite.airplay.session.MirrorSession
import com.henry.kite.airplay.session.NaluRepacker
import com.henry.kite.airplay.session.PasswordRequiredException
import com.henry.kite.airplay.session.RaopAudioStream
import com.henry.kite.airplay.session.RaopSession
import com.henry.kite.airplay.session.SessionState
import com.henry.kite.capture.AlacAudioEncoder
import com.henry.kite.capture.AudioFrameSource
import com.henry.kite.capture.AudioPcmSource
import com.henry.kite.capture.ScreenEncoder
import com.henry.kite.capture.VideoFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs the mirroring session in the foreground.
 *
 * MediaProjection needs a foreground service of type `mediaProjection` to already
 * be running on API 34+, so the projection result from the Activity is handed here
 * and only turned into a capture session after [startForeground].
 */
class MirrorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sessionJob: Job? = null
    private var metricJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var audioEncoder: AudioFrameSource? = null
    private var session: MirrorSession? = null
    private var raopSession: RaopSession? = null
    private var airPlay2Session: AirPlay2Session? = null

    /** Encoded dimensions right now; rotation swaps them mid-session. */
    @Volatile
    private var liveWidth: Int = 0

    @Volatile
    private var liveHeight: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Keep the Quick Settings tile in step with the session state.
        MirrorController.onPublish = { KiteTileService.requestRefresh(this) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopMirroring()
            else -> Log.w(TAG, "Ignoring unknown action ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    // Straight-line intent dispatch (stop / audio-only / video-only / mirror);
    // each branch is two or three lines, splitting would add indirection only.
    @Suppress("LongMethod")
    private fun handleStart(intent: Intent) {
        @Suppress("DEPRECATION")
        val receiver = intent.getSerializableExtra(EXTRA_RECEIVER) as? SerializableReceiver
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (receiver == null || resultData == null) {
            Log.e(TAG, "Start request was missing the receiver or the projection grant")
            stopSelf()
            return
        }

        val target = receiver.toReceiver()
        // A mirroring-capable receiver can still be casted to in audio-only
        // mode (the receiver card's long-press); that routes like any other
        // audio session. The reverse — video without sound — is the same
        // mirroring session with the audio feed skipped.
        val audioOnly = intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false)
        val videoOnly = intent.getBooleanExtra(EXTRA_VIDEO_ONLY, false)
        // Promote before touching MediaProjection: API 34+ throws otherwise.
        // The microphone type may only be claimed with RECORD_AUDIO granted;
        // claiming it ungranted throws SecurityException and would take down
        // video-only casts, so the mask follows the actual grant.
        val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            if (hasAudioPermission()) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(target.name), serviceTypes)

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, resultData)
        if (mediaProjection == null) {
            Log.e(TAG, "getMediaProjection returned null; the grant was probably consumed already")
            MirrorController.publish(
                SessionState.Failed(target, getString(R.string.error_projection_grant_invalid)),
            )
            stopSelf()
            return
        }
        projection = mediaProjection

        acquireWakeLock()

        // Live metrics in the notification: the once-per-second session states
        // that drive the status panel also refresh the notification's text.
        metricJob = scope.launch {
            MirrorController.state.collect { state ->
                val metrics = metricsText(state) ?: return@collect
                // POST_NOTIFICATIONS may be denied: the foreground-service
                // notification itself is exempt, but re-posting it needs the
                // grant on API 33+; without it the initial notification stays.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MirrorService,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@collect
                }
                runCatching {
                    NotificationManagerCompat.from(this@MirrorService)
                        .notify(NOTIFICATION_ID, buildNotification(target.name, metrics))
                }
            }
        }

        sessionJob = scope.launch {
            if (target.isAudioOnly) {
                // A device that ONLY advertises audio never takes a mirror session.
                runAudioSession(target, mediaProjection)
            } else if (audioOnly && target.pairingPath == PairingPath.HAP) {
                // macOS rejects every standalone audio-session form we tried
                // (400 on the audio SETUP); its accepted audio stream only
                // exists inside a mirroring session. Sound-only therefore runs
                // the full modern session and simply never starts the encoder.
                runSession(
                    receiver = target,
                    mediaProjection = mediaProjection,
                    maxLongEdge = intent.qualityLongEdge(),
                    frameRate = intent.qualityFrameRate(),
                    bitRateOverride = intent.bitRateOverride(),
                    skipVideo = true,
                )
            } else if (audioOnly) {
                runAudioSession(target, mediaProjection)
            } else {
                runSession(
                    receiver = target,
                    mediaProjection = mediaProjection,
                    maxLongEdge = intent.qualityLongEdge(),
                    frameRate = intent.qualityFrameRate(),
                    bitRateOverride = intent.bitRateOverride(),
                    videoOnly = videoOnly,
                )
            }
        }
    }

    /**
     * Audio-only casting. AirPlay 2 receivers (HomePod et al., advertised by
     * the buffered-audio/PTP feature bits) take the plist session flow; the
     * rest get classic RAOP. Either way the pump is the same L16 PCM feed.
     */
    private suspend fun runAudioSession(receiver: AirPlayReceiver, mediaProjection: MediaProjection) {
        if (!hasAudioPermission()) {
            Log.w(TAG, "Audio-only cast without RECORD_AUDIO; failing early")
            MirrorController.publish(SessionState.Failed(receiver, getString(R.string.error_audio_permission_needed)))
            stopSelf()
            return
        }
        if (receiver.features.supportsBufferedAudio || receiver.features.supportsPtp) {
            runAirPlay2AudioSession(receiver, mediaProjection)
        } else {
            runRaopAudioSession(receiver, mediaProjection)
        }
    }

    /** AirPlay 2 session: plist SETUPs, ChaCha20-Poly1305 audio, no FairPlay. */
    private suspend fun runAirPlay2AudioSession(receiver: AirPlayReceiver, mediaProjection: MediaProjection) {
        val app = application as KiteApplication
        val airPlay2 = AirPlay2Session(receiver, app.credentialStore, deviceName())
        airPlay2Session = airPlay2

        var audioJob: Job? = null
        try {
            MirrorController.publish(SessionState.Connecting(receiver))
            val stream = airPlay2.connect { MirrorController.requestPin(receiver) }

            MirrorController.publish(SessionState.StreamingAudio(receiver))

            // The SETUP declares ALAC (ct=2), so the pump must feed ALAC frames.
            val alac = AlacAudioEncoder(projection = mediaProjection)
            audioEncoder = alac
            audioJob = scope.launch {
                try {
                    alac.frames().collect { frame ->
                        if (!frame.isCodecConfig) stream.send(frame.data)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Audio pump stopped", e)
                }
            }

            var lastBytes = 0L
            while (scope.isActive) {
                delay(STATS_INTERVAL_MS)
                val bytes = stream.bytesSent
                val kilobitsPerSecond = (bytes - lastBytes) * 8L / 1_000L
                lastBytes = bytes
                MirrorController.publish(SessionState.StreamingAudio(receiver, kilobitsPerSecond))
            }
        } catch (e: Exception) {
            Log.e(TAG, "AirPlay 2 audio session ended with an error", e)
            if (MirrorController.state.value !is SessionState.Failed) {
                MirrorController.publish(
                    SessionState.Failed(receiver, pairingFailureReason(e), e),
                )
            }
        } finally {
            audioJob?.cancel()
            cleanupSession()
            stopSelf()
        }
    }

    /** Classic RAOP (AirTunes v1) for AirPort Express, shairport-sync and friends. */
    private suspend fun runRaopAudioSession(receiver: AirPlayReceiver, mediaProjection: MediaProjection) {
        val app = application as KiteApplication
        val raop = RaopSession(receiver, app.credentialStore)
        raopSession = raop

        var pcmSource: AudioPcmSource? = null
        var audioJob: Job? = null
        try {
            // connect() asks the UI for a password via the provider whenever the
            // receiver 401s; PasswordRequiredException means the user dismissed.
            MirrorController.publish(SessionState.Connecting(receiver))
            val stream = try {
                raop.connect { MirrorController.requestPassword(receiver) }
            } catch (e: PasswordRequiredException) {
                MirrorController.publish(
                    SessionState.Failed(receiver, pairingFailureReason(e), e),
                )
                return
            }

            MirrorController.publish(SessionState.StreamingAudio(receiver))

            val pcm = AudioPcmSource(
                projection = mediaProjection,
                chunkBytes = RaopAudioStream.FRAME_BYTES,
            )
            pcmSource = pcm
            audioJob = scope.launch {
                try {
                    pcm.frames().collect { chunk -> stream.send(chunk) }
                } catch (e: Exception) {
                    Log.w(TAG, "Audio pump stopped", e)
                }
            }

            var lastBytes = 0L
            while (scope.isActive) {
                delay(STATS_INTERVAL_MS)
                val bytes = stream.bytesSent
                val kilobitsPerSecond = (bytes - lastBytes) * 8L / 1_000L
                lastBytes = bytes
                MirrorController.publish(SessionState.StreamingAudio(receiver, kilobitsPerSecond))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio session ended with an error", e)
            if (MirrorController.state.value !is SessionState.Failed) {
                MirrorController.publish(
                    SessionState.Failed(receiver, pairingFailureReason(e), e),
                )
            }
        } finally {
            audioJob?.cancel()
            runCatching { pcmSource?.close() }
            cleanupSession()
            stopSelf()
        }
    }

    // Session bring-up with per-mode knobs (audio/video/skip-video); the
    // branches are independent setup steps rather than nested decisions.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun runSession(
        receiver: AirPlayReceiver,
        mediaProjection: MediaProjection,
        maxLongEdge: Int,
        frameRate: Int,
        bitRateOverride: Int?,
        videoOnly: Boolean = false,
        skipVideo: Boolean = false,
    ) {
        val metrics = currentDisplayMetrics()
        val format = VideoFormat.forDisplay(
            displayWidth = metrics.widthPixels,
            displayHeight = metrics.heightPixels,
            maxLongEdge = maxLongEdge,
            frameRate = frameRate,
            bitRate = bitRateOverride,
        )
        Log.i(
            TAG,
            "Encoding ${format.width}x${format.height}@${format.frameRate} " +
                "at ${format.bitRate / 1_000_000.0} Mbps " +
                "(display ${metrics.widthPixels}x${metrics.heightPixels}, maxLongEdge=$maxLongEdge)",
        )

        val app = application as KiteApplication
        // Route the whole process over the Wi-Fi network for the session's
        // lifetime: with a VPN up, RTSP/data/audio sockets would otherwise go
        // through the tunnel and the receiver would never see them.
        val wifi = WifiNetworkResolver.find(application).network
        val unbindWifi = runCatching {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            cm.bindProcessToNetwork(wifi)
            wifi != null
        }.getOrDefault(false)

        val mirrorSession = MirrorSession(
            receiver = receiver,
            credentialStore = app.credentialStore,
            // The user-visible device name ("Henry's Phone"), not the model —
            // receivers show this in their AirPlay prompts.
            clientName = deviceName(),
        )
        session = mirrorSession
        // The receiver stopped mirroring (closed its data channel): end the
        // session instead of streaming into a dead socket until something
        // throws somewhere unguarded.
        mirrorSession.onConnectionLost = { error ->
            MirrorController.publish(
                SessionState.Failed(receiver, getString(R.string.error_receiver_disconnected), error),
            )
            sessionJob?.cancel()
        }

        // Mirror the session's own state out to the UI.
        scope.launch {
            mirrorSession.state.collect { MirrorController.publish(it) }
        }

        var audioJob: Job? = null
        try {
            val stream = mirrorSession.connect(
                width = format.width,
                height = format.height,
                frameRate = format.frameRate,
                pinProvider = { MirrorController.requestPin(receiver) },
                passwordProvider = { MirrorController.requestPassword(receiver) },
                videoEnabled = !skipVideo,
            )

            // Sound-only keeps the whole modern session (its audio SETUP is the
            // only shape macOS accepts) and simply never starts the encoder.
            val screenEncoder = if (skipVideo) {
                Log.i(TAG, "Sound-only session: video encoder skipped")
                null
            } else {
                ScreenEncoder(
                    context = this,
                    projection = mediaProjection,
                    format = format,
                    maxLongEdge = maxLongEdge,
                    bitRateOverride = bitRateOverride,
                ).also { encoder ->
                    liveWidth = format.width
                    liveHeight = format.height
                    encoder.onDimensionsChanged = { width, height ->
                        liveWidth = width
                        liveHeight = height
                    }
                }
            }
            encoder = screenEncoder

            // Audio encoder reuses the same projection grant. The stream can be null
            // if the receiver declined audio or if capture is unavailable.
            // Assigned to the field, not a local, so cleanupSession() can close it —
            // AudioRecord is exclusive and a leak breaks the next session's audio.
            // Without the mic permission there is no capture; video carries on.
            // Both paths carry ALAC now: legacy receivers validate ELD frames
            // against Apple's encoder header and drop all others, and macOS's
            // own verified-sound path (doubletake's default) is ALAC as well.
            audioEncoder = when {
                // "Screen without sound" from the long-press menu.
                videoOnly -> null

                !hasAudioPermission() -> {
                    Log.w(TAG, "RECORD_AUDIO not granted; casting video without sound")
                    null
                }

                mirrorSession.modernAudio != null || mirrorSession.audioStream != null ->
                    AlacAudioEncoder(projection = mediaProjection)

                else -> null
            }

            // Start audio feed before video so priming packets arrive first.
            audioJob = audioEncoder?.let { audio ->
                scope.launch {
                    try {
                        pumpAudio(audio, mirrorSession)
                    } catch (e: Exception) {
                        Log.w(TAG, "Audio feed stopped", e)
                    }
                }
            }

            // Statistics ticker; cheap enough to run while mirroring.
            scope.launch {
                while (isActive) {
                    delay(STATS_INTERVAL_MS)
                    mirrorSession.refreshStatistics()
                }
            }

            // Sound-only has no encoder; the session lives on its audio stream
            // and the feedback/heartbeat loops inside MirrorSession.
            if (screenEncoder != null && stream != null) {
                var sentConfig = false
                screenEncoder.frames()
                    .buffer(FRAME_BUFFER, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
                    .collect { frame ->
                        if (frame.isCodecConfig) {
                            // avcC has to arrive before any slice or the receiver drops everything.
                            val sets = NaluRepacker.parseParameterSets(frame.data)
                            if (sets == null) {
                                Log.e(TAG, "Codec config had no SPS/PPS pair; cannot start the decoder")
                                return@collect
                            }
                            // liveWidth/Height, not `format`: rotation may have swapped
                            // the geometry since the session started.
                            stream.sendCodecConfig(sets, liveWidth, liveHeight, frame.presentationTimeUs)
                            sentConfig = true
                            return@collect
                        }
                        if (!sentConfig) return@collect // Wait for config; slices before it are useless.
                        stream.sendVideoFrame(frame.data, frame.presentationTimeUs)
                    }
            } else {
                // Park the coroutine for the session's lifetime; the audio pump
                // and MirrorSession's feedback loop keep everything alive.
                delay(Long.MAX_VALUE)
            }
        } catch (e: Exception) {
            audioJob?.cancel()
            Log.e(TAG, "Mirroring session ended with an error", e)
            // MirrorSession already published Failed for its own errors.
            if (MirrorController.state.value !is SessionState.Failed) {
                MirrorController.publish(
                    SessionState.Failed(receiver, pairingFailureReason(e), e),
                )
            }
        } finally {
            cleanupSession()
            stopSelf()
        }
    }

    private suspend fun pumpAudio(encoder: AudioFrameSource, session: MirrorSession) {
        // Modern (macOS) sessions stream ChaCha-sealed AAC-ELD over their own
        // RTP sender; legacy receivers use the AES-CBC AudioStream.
        session.modernAudio?.let { modern ->
            // Refresh the audio TimeAnnounce once per second (doubletake's
            // sync ticker) so the receiver keeps the RTP↔timeline mapping.
            val syncJob = scope.launch {
                while (isActive) {
                    delay(1_000)
                    runCatching { modern.sendSyncPacket(false) }
                }
            }
            try {
                encoder.frames().collect { frame ->
                    if (!frame.isCodecConfig) modern.sendDataFrame(frame.data)
                }
            } finally {
                syncJob.cancel()
            }
            return
        }
        val stream = session.audioStream ?: return
        stream.sendPrimingPackets()
        encoder.frames().collect { frame ->
            if (!frame.isCodecConfig) {
                stream.sendAudioFrame(frame.data)
            }
        }
    }

    private fun stopMirroring() {
        sessionJob?.cancel()
        // Session teardown closes sockets that may be mid-write on a dead TCP
        // peer; on the main thread that stalls input for minutes (observed as
        // a 60 s ANR). The UI switch to Idle is immediate either way.
        scope.launch(Dispatchers.IO) { cleanupSession() }
        MirrorController.publish(SessionState.Idle)
        stopSelf()
    }

    private fun cleanupSession() {
        metricJob?.cancel()
        metricJob = null
        runCatching { encoder?.close() }
        // AudioRecord holds an exclusive capture session; leaking it makes the
        // next mirroring attempt fail to open audio at all.
        runCatching { audioEncoder?.close() }
        runCatching { session?.close() }
        runCatching { raopSession?.close() }
        runCatching { airPlay2Session?.close() }
        runCatching { projection?.stop() }
        encoder = null
        audioEncoder = null
        session = null
        raopSession = null
        airPlay2Session = null
        projection = null
        releaseWakeLock()
        // Undo the Wi-Fi process binding so normal (VPN-inclusive) routing
        // resumes when we are not casting.
        runCatching {
            getSystemService(android.net.ConnectivityManager::class.java)
                .bindProcessToNetwork(null)
        }
    }

    /**
     * Wi-Fi power saving parks the radio between beacons when the screen is off,
     * which shows up as multi-hundred-millisecond stalls in the video. The wake
     * lock is what keeps the session smooth.
     */
    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /**
     * "1920 × 1080 @ 30 fps · 12.3 Mbps", or just the live rate for audio-only
     * states — the same line the status panel shows.
     */
    private fun metricsText(state: SessionState): String? = when (state) {
        is SessionState.Mirroring -> getString(
            R.string.mirroring_resolution,
            state.width,
            state.height,
            state.frameRate,
        ) + "  ·  " + rateText(state.kilobitsPerSecond)

        is SessionState.StreamingAudio -> rateText(state.kilobitsPerSecond)

        else -> null
    }

    /** kbps under 1 Mbps, Mbps above it, so the number stays short either way. */
    private fun rateText(kilobitsPerSecond: Long): String = if (kilobitsPerSecond < 1_000) {
        getString(R.string.mirroring_rate_kbps, kilobitsPerSecond)
    } else {
        getString(R.string.mirroring_rate_mbps, kilobitsPerSecond / 1000.0f)
    }

    private fun buildNotification(receiverName: String, metrics: String? = null): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, KiteApplication.CHANNEL_ID_MIRRORING)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.notification_title, receiverName))
            .setContentText(metrics ?: getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** Throttled receivers need a clear instruction, not the raw protocol error. */
    private fun pairingFailureReason(e: Exception): String =
        if (e is PairingException && e.tlvError == TlvError.BACKOFF) {
            getString(R.string.error_pairing_throttled)
        } else {
            e.message ?: getString(R.string.error_cast_interrupted)
        }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The name the user gave this device in system settings ("Henry's Phone"),
     * falling back to the model. Receivers surface it in their AirPlay prompts.
     */
    private fun deviceName(): String = Settings.Global.getString(contentResolver, "device_name")
        ?: Build.MODEL
        ?: "Android"

    @Suppress("DEPRECATION")
    private fun currentDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val window = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = window.currentWindowMetrics.bounds
            metrics.apply {
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                densityDpi = resources.configuration.densityDpi
            }
        } else {
            window.defaultDisplay.getRealMetrics(metrics)
            metrics
        }
    }

    private fun Intent.qualityLongEdge(): Int = getIntExtra(EXTRA_MAX_LONG_EDGE, DEFAULT_MAX_LONG_EDGE)

    private fun Intent.qualityFrameRate(): Int = getIntExtra(EXTRA_FRAME_RATE, DEFAULT_FRAME_RATE)

    /** Zero means "let the size decide", matching [VideoFormat.forDisplay]'s null. */
    private fun Intent.bitRateOverride(): Int? = getIntExtra(EXTRA_BIT_RATE, 0).takeIf { it > 0 }

    override fun onDestroy() {
        cleanupSession()
        scope.cancel()
        MirrorController.onPublish = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MirrorService"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "kite:mirroring"
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
        private const val STATS_INTERVAL_MS = 1_000L
        private const val FRAME_BUFFER = 8
        private const val DEFAULT_MAX_LONG_EDGE = 1920
        private const val DEFAULT_FRAME_RATE = 30

        const val ACTION_START = "com.henry.kite.START"
        const val ACTION_STOP = "com.henry.kite.STOP"

        private const val EXTRA_RECEIVER = "receiver"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_MAX_LONG_EDGE = "maxLongEdge"
        private const val EXTRA_FRAME_RATE = "frameRate"
        private const val EXTRA_BIT_RATE = "bitRate"
        private const val EXTRA_AUDIO_ONLY = "audioOnly"
        private const val EXTRA_VIDEO_ONLY = "videoOnly"

        /**
         * @param maxLongEdge [VideoFormat.LONG_EDGE_UNLIMITED] keeps the native size.
         * @param bitRate bits per second, or 0 to derive it from the frame size.
         * @param audioOnly cast just the sound (receiver-card long-press), even
         *   to a receiver that also supports mirroring.
         * @param videoOnly cast the screen with the audio feed skipped.
         */
        fun start(
            context: Context,
            receiver: AirPlayReceiver,
            resultCode: Int,
            resultData: Intent,
            maxLongEdge: Int,
            frameRate: Int = DEFAULT_FRAME_RATE,
            bitRate: Int = 0,
            audioOnly: Boolean = false,
            videoOnly: Boolean = false,
        ) {
            val intent = Intent(context, MirrorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RECEIVER, SerializableReceiver.from(receiver))
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_MAX_LONG_EDGE, maxLongEdge)
                putExtra(EXTRA_FRAME_RATE, frameRate)
                putExtra(EXTRA_BIT_RATE, bitRate)
                putExtra(EXTRA_AUDIO_ONLY, audioOnly)
                putExtra(EXTRA_VIDEO_ONLY, videoOnly)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MirrorService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
