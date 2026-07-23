package id.my.jangrana.rtmp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
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
    private lateinit var etUrl: EditText
    private lateinit var etKey: EditText
    private lateinit var btnCamera: Button
    private lateinit var btnAudioOnly: Button
    private lateinit var btnStream: Button
    private lateinit var tvStatus: TextView
    private lateinit var btnLogout: Button

    private var rtmpCamera: RtmpCamera2? = null
    private var isStreaming = false
    private var isAudioOnly = false
    private var isFrontCamera = true
    private var surfaceReady = false
    private var permissionsGranted = false

    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )
    private val permReqCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glView = findViewById(R.id.glView)
        etUrl = findViewById(R.id.etUrl)
        etKey = findViewById(R.id.etKey)
        btnCamera = findViewById(R.id.btnCamera)
        btnAudioOnly = findViewById(R.id.btnAudioOnly)
        btnStream = findViewById(R.id.btnStream)
        tvStatus = findViewById(R.id.tvStatus)
        btnLogout = findViewById(R.id.btnLogout)

        val rtmpUrl = intent.getStringExtra("rtmp_url") ?: ""
        val streamKey = intent.getStringExtra("stream_key") ?: ""

        if (rtmpUrl.isNotEmpty() && streamKey.isNotEmpty()) {
            val baseUrl = rtmpUrl.substringBeforeLast("/")
            val key = rtmpUrl.substringAfterLast("/")
            etUrl.setText(baseUrl + "/")
            etKey.setText(key)
            etUrl.isEnabled = false
            etKey.isEnabled = false
        }

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

        btnStream.setOnClickListener { toggleStream() }
        btnCamera.setOnClickListener { switchCamera() }
        btnAudioOnly.setOnClickListener { toggleAudioOnly() }
        btnLogout.setOnClickListener {
            if (isStreaming) stopStream()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        checkPermissions()
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
        if (isStreaming) {
            stopStream()
        } else {
            startStream()
        }
    }

    private fun startStream() {
        val url = etUrl.text.toString().trim()
        val key = etKey.text.toString().trim()
        if (url.isEmpty() || key.isEmpty()) {
            tvStatus.text = "Isi server URL dan stream key"
            return
        }
        val endpoint = if (url.endsWith("/")) url + key else "$url/$key"

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
            btnStream.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            tvStatus.text = if (isAudioOnly) "Streaming Audio Only..." else "Streaming LIVE..."
            if (isAudioOnly) glView?.visibility = android.view.View.GONE
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
        glView?.visibility = android.view.View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnStream.text = "Mulai Stream"
        btnStream.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
        tvStatus.text = "Siap"
    }

    private fun switchCamera() {
        try {
            rtmpCamera?.let { cam ->
                if (cam.isStreaming || cam.isOnPreview) {
                    cam.switchCamera()
                    isFrontCamera = !isFrontCamera
                }
            }
        } catch (e: Exception) {
            tvStatus.text = "Gagal balik kamera: ${e.localizedMessage}"
        }
    }

    private fun toggleAudioOnly() {
        isAudioOnly = !isAudioOnly
        btnAudioOnly.text = if (isAudioOnly) "Audio Only: ON" else "Audio Only: OFF"

        try {
            rtmpCamera?.let { cam ->
                if (isStreaming) {
                    if (isAudioOnly) {
                        cam.stopPreview()
                        glView?.visibility = android.view.View.GONE
                        tvStatus.text = "Streaming Audio Only..."
                    } else {
                        glView?.visibility = android.view.View.VISIBLE
                        cam.startPreview()
                        tvStatus.text = "Streaming..."
                    }
                }
            }
        } catch (e: Exception) {
            tvStatus.text = "Audio error: ${e.localizedMessage}"
        }
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
        if (permissionsGranted && surfaceReady) startPreview()
    }
}
