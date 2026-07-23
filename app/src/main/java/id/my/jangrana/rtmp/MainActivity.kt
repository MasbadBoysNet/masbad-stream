package id.my.jangrana.rtmp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
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
    private lateinit var btnStream: Button
    private lateinit var btnLogout: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvZoomLabel: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var secretGate: View
    private lateinit var etSecret: EditText
    private lateinit var btnUnlock: Button
    private lateinit var tvSecretError: TextView
    private lateinit var controls: View

    private var rtmpCamera: RtmpCamera2? = null
    private var isStreaming = false
    private var isAudioOnly = false
    private var isFrontCamera = true
    private var isMirror = true
    private var surfaceReady = false
    private var permissionsGranted = false
    private var currentPath = 1

    private val secretPassword = "mbg"
    private val baseRtmpUrl = "rtmp://stream.jangrana.my.id:1935/"

    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )
    private val permReqCode = 100

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
        secretGate = findViewById(R.id.secretGate)
        etSecret = findViewById(R.id.etSecret)
        btnUnlock = findViewById(R.id.btnUnlock)
        tvSecretError = findViewById(R.id.tvSecretError)
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

        try {
            glView?.setZOrderOnTop(true)
        } catch (e: Exception) {
            tvStatus.text = "GL init: ${e.localizedMessage}"
        }

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
                if (permissionsGranted) startPreview()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }
        })

        btnUnlock.setOnClickListener {
            val pass = etSecret.text.toString().trim()
            if (pass == secretPassword) {
                secretGate.visibility = View.GONE
                controls.visibility = View.VISIBLE
                checkPermissions()
            } else {
                tvSecretError.text = "Password salah"
                etSecret.selectAll()
            }
        }

        etSecret.setOnEditorActionListener { _, _, _ ->
            btnUnlock.performClick()
            true
        }

        updateRotateLabel()

        secretGate.visibility = View.VISIBLE
        controls.visibility = View.GONE

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
            btnAudioOnly.text = if (isAudioOnly) "Audio: ON" else "Audio: OFF"

            try {
                rtmpCamera?.let { cam ->
                    if (isStreaming) {
                        if (isAudioOnly) {
                            cam.stopPreview()
                            glView?.visibility = View.GONE
                            tvStatus.text = "Streaming Audio Only..."
                        } else {
                            glView?.visibility = View.VISIBLE
                            if (surfaceReady) cam.startPreview()
                            tvStatus.text = "Streaming LIVE..."
                        }
                    }
                }
            } catch (e: Exception) {
                tvStatus.text = "Audio error: ${e.localizedMessage}"
            }
        }

        btnLogout.setOnClickListener {
            if (isStreaming) stopStream()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        btnRotate.setOnClickListener {
            val cur = resources.configuration.orientation
            if (cur == Configuration.ORIENTATION_LANDSCAPE) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
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

    private fun getStreamEndpoint(): String {
        return "${baseRtmpUrl}stream$currentPath"
    }

    private fun checkPermissions() {
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            permissionsGranted = true
            if (surfaceReady) startPreview()
        } else {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), permReqCode)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        if (requestCode == permReqCode) {
            if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
                permissionsGranted = true
                if (surfaceReady) startPreview()
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
        val endpoint = getStreamEndpoint()

        try {
            rtmpCamera?.let { cam ->
                if (!cam.isOnPreview) {
                    if (!startPreview()) return@let
                    Handler(Looper.getMainLooper()).postDelayed({
                        doStream(cam, endpoint)
                    }, 500)
                } else {
                    doStream(cam, endpoint)
                }
            }
        } catch (e: Exception) {
            tvStatus.text = "Stream error: ${e.localizedMessage}"
        }
    }

    private fun doStream(cam: RtmpCamera2, endpoint: String) {
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
        if (permissionsGranted && surfaceReady) startPreview()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRotateLabel()
    }
}
