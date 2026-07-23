package id.my.jangrana.rtmp

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import android.media.MediaCodecInfo
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private var glView: OpenGlView? = null
    private lateinit var btnCamera: Button
    private lateinit var btnPathCycle: Button
    private lateinit var btnMirror: Button
    private lateinit var btnAudioOnly: Button
    private lateinit var btnRotate: Button
    private lateinit var btnStream: Button
    private lateinit var btnLogout: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvZoomLabel: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var tapOverlay: View
    private lateinit var controls: View

    private var rtmpCamera: RtmpCamera2? = null
    private var isStreaming = false
    private var isAudioOnly = false
    private var isFrontCamera = true
    private var isMirror = true
    private var surfaceReady = false
    private var permissionsGranted = false
    private var currentPath = 1
    private var controlsHidden = false
    private var lastStopStatus = "Siap"
    private var streamWakeLock: PowerManager.WakeLock? = null
    private var userRequestedStop = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var encodersPrepared = false

    private val baseRtmpUrl = "rtmp://stream.jangrana.my.id:1935/"

    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )
    private val permReqCode = 100

    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glView = findViewById(R.id.glView)
        btnCamera = findViewById(R.id.btnCamera)
        btnPathCycle = findViewById(R.id.btnPathCycle)
        btnMirror = findViewById(R.id.btnMirror)
        btnAudioOnly = findViewById(R.id.btnAudioOnly)
        btnRotate = findViewById(R.id.btnRotate)
        btnStream = findViewById(R.id.btnStream)
        btnLogout = findViewById(R.id.btnLogout)
        tvStatus = findViewById(R.id.tvStatus)
        tvZoomLabel = findViewById(R.id.tvZoomLabel)
        zoomSeek = findViewById(R.id.zoomSeek)
        tapOverlay = findViewById(R.id.tapOverlay)
        controls = findViewById(R.id.controls)

        val streamKey = intent.getStringExtra("stream_key") ?: ""
        if (streamKey.isNotEmpty()) {
            val num = streamKey.replace("stream", "").toIntOrNull() ?: 1
            currentPath = num.coerceIn(1, 12)
        }
        btnPathCycle.text = "Stream $currentPath"
        updateRotateLabel()
        updateAudioLabel()

        try {
            rtmpCamera = glView?.let { gv ->
                RtmpCamera2(gv, object : ConnectChecker {
                    override fun onConnectionStarted(url: String) = runOnUiThread {
                        tvStatus.text = "Menghubungkan..."
                    }

                    override fun onConnectionSuccess() = runOnUiThread {
                        reconnectAttempts = 0
                        tvStatus.text = if (isAudioOnly) "Audio Only" else "Streaming LIVE..."
                    }

                    override fun onConnectionFailed(reason: String) = runOnUiThread {
                        handleUnexpectedDisconnect("Gagal: $reason")
                    }

                    override fun onDisconnect() = runOnUiThread {
                        handleUnexpectedDisconnect("Terputus")
                    }

                    override fun onAuthError() = runOnUiThread {
                        stopStream("Auth RTMP gagal")
                    }

                    override fun onAuthSuccess() = runOnUiThread {
                        tvStatus.text = "Auth RTMP OK"
                    }

                    override fun onNewBitrate(bitrate: Long) = Unit
                })
            }
        } catch (e: Exception) {
            tvStatus.text = "Gagal init kamera: ${e.localizedMessage}"
        }

        glView?.holder?.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                if (permissionsGranted) initCamera()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                tvStatus.text = "Fokus"
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isStreaming) tvStatus.text = "Siap"
                }, 1500)
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }
        })

        tapOverlay.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        btnStream.setOnClickListener { toggleStream() }

        btnCamera.setOnClickListener {
            rtmpCamera?.let { cam ->
                if (cam.isStreaming || cam.isOnPreview) {
                    try { cam.switchCamera() } catch (e: Exception) { }
                }
            }
            isFrontCamera = !isFrontCamera
            isMirror = isFrontCamera
            updateMirrorLabel()
            btnCamera.text = if (isFrontCamera) "Kamera: Depan" else "Kamera: Belakang"
        }

        btnPathCycle.setOnClickListener {
            currentPath = if (currentPath >= 12) 1 else currentPath + 1
            btnPathCycle.text = "Stream $currentPath"
        }

        btnMirror.setOnClickListener {
            isMirror = !isMirror
            updateMirrorLabel()
        }

        btnAudioOnly.setOnClickListener {
            isAudioOnly = !isAudioOnly
            updateAudioLabel()

            encodersPrepared = false
            if (isAudioOnly) {
                glView?.visibility = View.GONE
                rtmpCamera?.let { if (it.isOnPreview) it.stopPreview() }
            } else {
                glView?.visibility = View.VISIBLE
                rtmpCamera?.let { if (!it.isOnPreview) initCamera() }
            }

            if (isStreaming) {
                stopStream()
                startStream()
            }
        }

        btnRotate.setOnClickListener {
            val cur = resources.configuration.orientation
            if (cur == Configuration.ORIENTATION_LANDSCAPE) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }

        btnLogout.setOnClickListener {
            if (isStreaming) stopStream()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val zoom = 1f + (progress / 100f) * 2f
                tvZoomLabel.text = if (zoom <= 1.01f) "1x" else String.format("%.1fx", zoom)
                try { rtmpCamera?.setZoom(zoom) } catch (e: Exception) { }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        checkPermissions()
    }

    private fun initCamera(forcePrepare: Boolean = false) {
        try {
            rtmpCamera?.let { cam ->
                if (forcePrepare && cam.isOnPreview) {
                    try { cam.stopPreview() } catch (e: Exception) { }
                }
                if (!forcePrepare && cam.isOnPreview && encodersPrepared) return@let

                if (!isAudioOnly) {
                    val res = getResolution()
                    val videoOk = cam.prepareVideo(
                        res.width, res.height, res.fps, res.bitrate, res.iframeInterval,
                        0,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                        MediaCodecInfo.CodecLevel.AVCLevel31
                    )
                    if (!videoOk) {
                        tvStatus.text = "Gagal init video encoder"
                        return@let
                    }
                    cam.forceFpsLimit(true)
                }
                val audioOk = cam.prepareAudio(32 * 1000, 44100, false, false, false)
                if (!audioOk) {
                    tvStatus.text = "Gagal init audio encoder"
                    return@let
                }
                encodersPrepared = true

                if (!isAudioOnly) {
                    cam.startPreview()
                }
            }
        } catch (e: Exception) {
            encodersPrepared = false
            tvStatus.text = "Init: ${e.localizedMessage}"
        }
    }

    private fun getResolution(): ResConfig {
        return ResConfig(640, 360, 20, 800 * 1000, 2)
    }

    data class ResConfig(val width: Int, val height: Int, val fps: Int, val bitrate: Int, val iframeInterval: Int)

    private fun toggleControls() {
        controlsHidden = !controlsHidden
        controls.visibility = if (controlsHidden) View.GONE else View.VISIBLE
    }

    private fun updateMirrorLabel() {
        btnMirror.text = if (isMirror) "Flip: ON" else "Flip: OFF"
    }

    private fun updateRotateLabel() {
        val cur = resources.configuration.orientation
        btnRotate.text = if (cur == Configuration.ORIENTATION_LANDSCAPE) "Potrait" else "Landscape"
    }

    private fun updateAudioLabel() {
        btnAudioOnly.text = if (isAudioOnly) "Audio Only" else "Video+Audio"
    }

    private fun getStreamEndpoint(): String {
        return "${baseRtmpUrl}stream$currentPath"
    }

    private fun checkPermissions() {
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            permissionsGranted = true
            if (surfaceReady) initCamera()
        } else {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), permReqCode)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        if (requestCode == permReqCode) {
            if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
                permissionsGranted = true
                if (surfaceReady) initCamera()
            } else {
                Toast.makeText(this, "Izin kamera & audio diperlukan", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleStream() {
        if (isStreaming) stopStream() else startStream()
    }

    private fun startStream() {
        val endpoint = getStreamEndpoint()

        try {
            rtmpCamera?.let { cam ->
                if (!encodersPrepared) {
                    initCamera(forcePrepare = true)
                    if (!encodersPrepared) return@let
                }
                if (!isAudioOnly && !cam.isOnPreview) {
                    initCamera()
                    if (!cam.isOnPreview) return@let
                }
                try {
                    userRequestedStop = false
                    cam.startStream(endpoint)
                    lastStopStatus = "Siap"
                    isStreaming = true
                    btnStream.text = "Hentikan"
                    btnStream.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_red_dark))
                    tvStatus.text = if (isAudioOnly) "Audio Only" else "Streaming LIVE..."
                    if (isAudioOnly) glView?.visibility = View.GONE
                    acquireStreamWakeLock()
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } catch (e: IOException) {
                    tvStatus.text = "Gagal: ${e.localizedMessage}"
                }
            }
        } catch (e: Exception) {
            tvStatus.text = "Stream error: ${e.localizedMessage}"
        }
    }

    private fun stopStream(status: String = lastStopStatus) {
        userRequestedStop = true
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectAttempts = 0
        lastStopStatus = status
        try {
            rtmpCamera?.let { cam ->
                if (cam.isStreaming) cam.stopStream()
            }
        } catch (e: Exception) { }
        encodersPrepared = false
        isStreaming = false
        glView?.visibility = View.VISIBLE
        releaseStreamWakeLock()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnStream.text = "Mulai Stream"
        btnStream.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark))
        tvStatus.text = status
    }

    private fun handleUnexpectedDisconnect(status: String) {
        if (userRequestedStop) {
            stopStream(status)
            return
        }
        try {
            rtmpCamera?.let { cam ->
                if (cam.isStreaming) cam.stopStream()
            }
        } catch (e: Exception) { }
        encodersPrepared = false
        isStreaming = false

        if (!isAudioOnly) {
            stopStream(status)
            return
        }

        if (reconnectAttempts >= maxReconnectAttempts) {
            stopStream("$status. Reconnect gagal")
            return
        }

        reconnectAttempts += 1
        tvStatus.text = "$status. Reconnect $reconnectAttempts/$maxReconnectAttempts..."
        reconnectHandler.postDelayed({
            if (!userRequestedStop) startStream()
        }, 2000L)
    }

    override fun onDestroy() {
        if (isStreaming) stopStream()
        releaseStreamWakeLock()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        if (isStreaming) return
        try {
            rtmpCamera?.let { cam ->
                if (cam.isOnPreview) cam.stopPreview()
            }
        } catch (e: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        updateRotateLabel()
        if (permissionsGranted && surfaceReady && !isStreaming) {
            initCamera()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRotateLabel()
    }

    private fun acquireStreamWakeLock() {
        if (streamWakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        streamWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MasbadStream:AudioStreamLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseStreamWakeLock() {
        try {
            if (streamWakeLock?.isHeld == true) streamWakeLock?.release()
        } catch (e: Exception) { }
        streamWakeLock = null
    }

}
