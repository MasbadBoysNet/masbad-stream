package id.my.jangrana.rtmp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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

class RegisterActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirm: EditText
    private lateinit var btnRegister: Button
    private lateinit var tvError: TextView

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        etConfirm = findViewById(R.id.etConfirm)
        btnRegister = findViewById(R.id.btnRegister)
        tvError = findViewById(R.id.tvError)
        val btnLogin = findViewById<TextView>(R.id.btnLogin)

        btnRegister.setOnClickListener { doRegister() }
        btnLogin.setOnClickListener {
            finish()
        }
    }

    private fun doRegister() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString().trim()
        val confirm = etConfirm.text.toString().trim()

        if (username.length < 3) {
            tvError.text = "Username minimal 3 karakter"
            return
        }
        if (password.length < 4) {
            tvError.text = "Password minimal 4 karakter"
            return
        }
        if (password != confirm) {
            tvError.text = "Password tidak cocok"
            return
        }

        btnRegister.isEnabled = false
        tvError.text = ""

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply {
                    put("username", username)
                    put("password", password)
                }
                val body = json.toString().toRequestBody(jsonMedia)
                val request = Request.Builder()
                    .url("https://masbad.jangrana.my.id/api/register.php")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val bodyStr = response.body?.string() ?: ""

                withContext(Dispatchers.Main) {
                    btnRegister.isEnabled = true
                    if (response.isSuccessful) {
                        val obj = JSONObject(bodyStr)
                        if (obj.optBoolean("ok", false)) {
                            val streamKey = obj.getString("stream_key")
                            val rtmpUrl = obj.getString("rtmp_url")
                            val intent = Intent(this@RegisterActivity, MainActivity::class.java)
                            intent.putExtra("stream_key", streamKey)
                            intent.putExtra("rtmp_url", rtmpUrl)
                            intent.putExtra("username", obj.optString("username", username))
                            startActivity(intent)
                            finish()
                        } else {
                            tvError.text = obj.optString("error", "Gagal daftar")
                        }
                    } else {
                        val obj = try { JSONObject(bodyStr) } catch (e: Exception) { null }
                        tvError.text = obj?.optString("error", "Server error (${response.code})")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnRegister.isEnabled = true
                    tvError.text = "Network error: ${e.localizedMessage}"
                }
            }
        }
    }
}
