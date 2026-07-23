package id.my.jangrana.rtmp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        tvError = findViewById(R.id.tvError)
        val btnRegister = findViewById<TextView>(R.id.btnRegister)

        btnLogin.setOnClickListener { doLogin() }
        btnRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun doLogin() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (username.isEmpty() || password.isEmpty()) {
            tvError.text = "Isi username dan password"
            return
        }

        btnLogin.isEnabled = false
        tvError.text = ""

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply {
                    put("username", username)
                    put("password", password)
                }
                val body = json.toString().toRequestBody(jsonMedia)
                val request = Request.Builder()
                    .url("https://masbad.jangrana.my.id/api/login.php")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val bodyStr = response.body?.string() ?: ""

                withContext(Dispatchers.Main) {
                    btnLogin.isEnabled = true
                    if (response.isSuccessful) {
                        val obj = JSONObject(bodyStr)
                        if (obj.optBoolean("ok", false)) {
                            val streamKey = obj.getString("stream_key")
                            val rtmpUrl = obj.getString("rtmp_url")
                            val intent = Intent(this@LoginActivity, MainActivity::class.java)
                            intent.putExtra("stream_key", streamKey)
                            intent.putExtra("rtmp_url", rtmpUrl)
                            intent.putExtra("username", obj.optString("username", username))
                            startActivity(intent)
                            finish()
                        } else {
                            tvError.text = obj.optString("error", "Gagal login")
                        }
                    } else {
                        val obj = try { JSONObject(bodyStr) } catch (e: Exception) { null }
                        tvError.text = obj?.optString("error", "Server error (${response.code})")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnLogin.isEnabled = true
                    tvError.text = "Network error: ${e.localizedMessage}"
                }
            }
        }
    }
}
