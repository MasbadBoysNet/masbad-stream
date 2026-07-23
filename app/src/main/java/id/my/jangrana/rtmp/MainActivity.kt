package id.my.jangrana.rtmp

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private var glView: OpenGlView? = null
    private lateinit var btnCamera: Button
    private lateinit var btnPathCycle: Button
    private lateinit var btnMirror: Button
    private lateinit var btnAudioOnly: Button
    private lateinit var btnRotate: Button
    private lateinit var btnResCycle: Button
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
    private var encoderPrepared = false

    private var currentResolutionIdx = 0
    private val resolutions = listOf(
        ResConfig("720p", 1280, 720, 1000),
        ResConfig("1080p", 1920, 1080, 2000),
    )

    private val baseRtmpUrl = "rtmp://stream.jangrana.my.id:1935/"

    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )
    private val permReqCode = 100

    private lateinit var gestureDetector: GestureDetector

    data class ResConfig(val label: String, val width: Int, val height: Int, val bitrateKbps: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glView = findViewById(R.id.glView)
        btnCamera = findViewById(R.id.btnCamera)
        btnPathCycle = findViewById(R.id.btnPathCycle)
        btnMirror = findViewById(R.id.btnMirror)
        btnAudioOnly = findViewById(R.id.btnAudioOnly)
        btnRotate = findViewById(R.id.btnRotate)
        btnResCycle = findViewById(R.id.btnResCycle)
        btnStream = findViewById(R.id.btnStream)
        btnLogout = findViewById(R.id.btnLogout)
        tvStatus = findViewById(R.id.tvStatus)
        tvZoomLabel = findViewById(R.id.tvZoomLabel)
        zoomSeek = findViewById(R.id.zoomSeek)
        tapOverlay = findViewById(R.id.tapOverlay)
        controls = findViewById(R.id.controls)

        val streamKey = intent.getStringExtra("stream_key") ?: ""
        val rtmpUrl = intent.getStringExtra("rtmp_url") ?: ""

        if (streamKey.isNotEmpty()) {
            val num = streamKey.replace("stream", "").toIntOrNull() ?: 1
            currentPath = num.coerceIn(1, 12)
        } else if (rtmpUrl.isNotEmpty()) {
            val key = rtmpUrl.substringAfterLast("/")
            val num = key.replace("stream", "").toIntOrNull() ?: 1
            currentPath = num.coerceIn(1, 12)
        }
        updatePathLabel()
        updateRotateLabel()
        updateResLabel()
        updateAudioLabel()

        try {
            rtmpCamera = glView?.let { gv ->
                RtmpCamera2(gv, object : ConnectChecker {
                    override fun onConnectionStarted(url: String) = runOnUiThread {
                        tvStatus.text = "Menghubungkan..."
                    }

                    override fun onConnectionSuccess() = runOnUiThread {
                        tvStatus.text = if (isAudioOnly) "Streaming Audio Only..." else "Streaming LIVE..."
                    }

                    override fun onConnectionFailed(reason: String) = runOnUiThread {
                        tvStatus.text = "Koneksi gagal: $reason"
                        stopStream()
                    }

                    override fun onDisconnect() = runOnUiThread {
                        tvStatus.text = "Terputus"
                        stopStream()
                    }

                    override fun onAuthError() = runOnUiThread {
                        tvStatus.text = "Auth RTMP gagal"
                        stopStream()
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
                if (permissionsGranted) {
                    prepareEncoders()
                    startPreview()
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                try { rtmpCamera?.enableAutoFocus() } catch (ex: Exception) { }
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
            updatePathLabel()
        }

        btnMirror.setOnClickListener {
            isMirror = !isMirror
            updateMirrorLabel()
        }

        btnAudioOnly.setOnClickListener {
            isAudioOnly = !isAudioOnly
            updateAudioLabel()

            if (isStreaming) {
                stopStream()
                prepareEncoders()
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

        btnResCycle.setOnClickListener {
            currentResolutionIdx = (currentResolutionIdx + 1) % resolutions.size
            updateResLabel()
            if (!isStreaming) {
                if (rtmpCamera?.isOnPreview == true) {
                    try { rtmpCamera?.stopPreview() } catch (e: Exception) { }
                }
                prepareEncoders()
                startPreview()
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

    private fun prepareEncoders() {
        encoderPrepared = false
        try {
            rtmpCamera?.let { cam ->
                var ok = true
                if (!isAudioOnly) {
                    val res = resolutions[currentResolutionIdx]
                    try {
                        if (!cam.prepareVideo(res.width, res.height, res.bitrateKbps * 1000)) {
                            tvStatus.text = "Gagal siapkan video"
                            ok = false
                        }
                    } catch (e: Exception) {
                        tvStatus.text = "Video: ${e.localizedMessage}"
                        ok = false
                    }
                }
                try {
                    if (!cam.prepareAudio()) {
                        tvStatus.text = "Gagal siapkan audio"
                        ok = false
                    }
                } catch (e: Exception) {
                    tvStatus.text = "Audio: ${e.localizedMessage}"
                    ok = false
                }
                encoderPrepared = ok
            }
        } catch (e: Exception) {
            tvStatus.text = "Encoder: ${e.localizedMessage}"
        }
    }

    private fun toggleControls() {
        controlsHidden = !controlsHidden
        controls.visibility = if (controlsHidden) View.GONE else View.VISIBLE
    }

    private fun updatePathLabel() {
        btnPathCycle.text = "Stream $currentPath"
    }

    private fun updateMirrorLabel() {
        btnMirror.text = if (isMirror) "Flip: ON" else "Flip: OFF"
    }

    private fun updateRotateLabel() {
        val cur = resources.configuration.orientation
        btnRotate.text = if (cur == Configuration.ORIENTATION_LANDSCAPE) "Potrait" else "Landscape"
    }

    private fun updateResLabel() {
        btnResCycle.text = resolutions[currentResolutionIdx].label
    }

    private fun updateAudioLabel() {
        btnAudioOnly.text = if (isAudioOnly) "Audio Only" else "Stream Audio"
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
            if (surfaceReady) {
                prepareEncoders()
                startPreview()
            }
        } else {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), permReqCode)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        if (requestCode == permReqCode) {
            if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
                permissionsGranted = true
                if (surfaceReady) {
                    prepareEncoders()
                    startPreview()
                }
            } else {
                Toast.makeText(this, "Izin kamera & audio diperlukan", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startPreview(): Boolean {
        if (!surfaceReady) {
            tvStatus.text = "Tunggu permukaan kamera..."
            return false
        }
        return try {
            rtmpCamera?.let { cam ->
                if (!cam.isOnPreview) {
                    cam.startPreview()
                }
            }
            true
        } catch (e: Exception) {
            tvStatus.text = "Kamera error: ${e.localizedMessage}"
            false
        }
    }

    private fun toggleStream() {
        if (isStreaming) stopStream() else startStream()
    }

    private fun startStream() {
        if (!encoderPrepared) {
            prepareEncoders()
        }
        val endpoint = getStreamEndpoint()

        try {
            rtmpCamera?.let { cam ->
                if (!cam.isOnPreview) {
                    if (!startPreview()) return@let
                }
                try {
                    cam.startStream(endpoint)
                    isStreaming = true
                    btnStream.text = "Hentikan"
                    btnStream.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_red_dark))
                    tvStatus.text = if (isAudioOnly) "Streaming Audio Only..." else "Streaming LIVE..."
                    if (isAudioOnly) glView?.visibility = View.GONE
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } catch (e: IOException) {
                    tvStatus.text = "Gagal: ${e.localizedMessage}"
                }
            }
        } catch (e: Exception) {
            tvStatus.text = "Stream error: ${e.localizedMessage}"
        }
    }

    private fun stopStream() {
        try {
            rtmpCamera?.let { cam ->
                cam.stopStream()
                cam.stopPreview()
            }
        } catch (e: Exception) { }
        isStreaming = false
        glView?.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnStream.text = "Mulai Stream"
        btnStream.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark))
        tvStatus.text = "Siap"
    }

    override fun onDestroy() {
        if (isStreaming) stopStream()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        try {
            rtmpCamera?.let { cam ->
                if (cam.isOnPreview) cam.stopPreview()
            }
        } catch (e: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        updateRotateLabel()
        if (permissionsGranted && surfaceReady) {
            if (!isStreaming) {
                prepareEncoders()
                startPreview()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRotateLabel()
    }
}
