package eu.kanade.tachiyomi.animeextension.pt.tomato

import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.hcaptcha.sdk.HCaptcha
import com.hcaptcha.sdk.HCaptchaConfig
import com.hcaptcha.sdk.HCaptchaTheme
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Native counterpart of TomatoSignIn from com.tomatos.clientapp. */
class TomatoLoginActivity : FragmentActivity() {
    private lateinit var username: EditText
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var action: Button
    private lateinit var changeMode: Button
    private lateinit var status: TextView
    private var signUpMode = false
    private val apiHost: String by lazy {
        intent.getStringExtra(EXTRA_API_HOST)
            ?.takeIf { it == PROD_API_HOST || it == EDGE_API_HOST }
            ?: PROD_API_HOST
    }

    @Suppress("DEPRECATION")
    private val resultReceiver: ResultReceiver? by lazy {
        intent.getParcelableExtra(EXTRA_RESULT_RECEIVER)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Conta Tomato"
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                username = EditText(context).apply {
                    hint = "Nome de usuário"
                    visibility = android.view.View.GONE
                    inputType = InputType.TYPE_CLASS_TEXT
                }
                email = EditText(context).apply {
                    hint = "E-mail"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                }
                password = EditText(context).apply {
                    hint = "Senha"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                action = Button(context).apply {
                    text = "Entrar"
                    setOnClickListener { beginLogin() }
                }
                changeMode = Button(context).apply {
                    text = "Criar conta"
                    setOnClickListener { toggleMode() }
                }
                status = TextView(context).apply {
                    gravity = Gravity.CENTER
                    text = "Use sua conta oficial Tomato."
                }
                addView(username, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(email, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(password, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(action, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(changeMode, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(status, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            },
        )
    }

    private fun beginLogin() {
        if (signUpMode) {
            beginSignUp()
            return
        }
        val enteredEmail = email.text.toString().trim()
        val enteredPassword = password.text.toString()
        if (enteredEmail.isEmpty() || enteredPassword.isEmpty()) {
            status.text = "Informe e-mail e senha."
            return
        }
        action.isEnabled = false
        status.text = "Confirme o hCaptcha para entrar..."
        HCaptcha.getClient(this).verifyWithHCaptcha(
            HCaptchaConfig.builder().siteKey(HCAPTCHA_SITE_KEY).theme(HCaptchaTheme.DARK).build(),
        ).addOnSuccessListener { response ->
            val verification = response.tokenResult
            Log.d(TAG, "TOMATO_DEBUG AUTH human_challenge=completed")
            login(enteredEmail, enteredPassword, verification)
        }.addOnFailureListener { error ->
            Log.d(TAG, "TOMATO_DEBUG AUTH human_challenge=failure type=${error.javaClass.simpleName}")
            action.isEnabled = true
            status.text = "Não foi possível concluir o hCaptcha."
        }
    }

    private fun toggleMode() {
        signUpMode = !signUpMode
        username.visibility = if (signUpMode) android.view.View.VISIBLE else android.view.View.GONE
        action.text = if (signUpMode) "Criar conta" else "Entrar"
        changeMode.text = if (signUpMode) "Voltar para entrar" else "Criar conta"
        status.text = if (signUpMode) "Crie sua conta oficial Tomato." else "Use sua conta oficial Tomato."
    }

    private fun beginSignUp() {
        val enteredUsername = username.text.toString().trim()
        val enteredEmail = email.text.toString().trim()
        val enteredPassword = password.text.toString()
        when {
            enteredUsername.length !in 3..24 -> {
                status.text = "O nome de usuário deve ter entre 3 e 24 caracteres."
                return
            }
            enteredEmail.isEmpty() -> {
                status.text = "Informe o e-mail."
                return
            }
            enteredPassword.length !in 5..127 -> {
                status.text = "A senha deve ter entre 5 e 127 caracteres."
                return
            }
        }
        action.isEnabled = false
        status.text = "Confirme o hCaptcha para criar a conta..."
        HCaptcha.getClient(this).verifyWithHCaptcha(
            HCaptchaConfig.builder().siteKey(HCAPTCHA_SITE_KEY).theme(HCaptchaTheme.DARK).build(),
        ).addOnSuccessListener { response ->
            val verification = response.tokenResult
            Log.d(TAG, "TOMATO_DEBUG SIGNUP human_challenge=completed")
            signUp(enteredUsername, enteredEmail, enteredPassword, verification)
        }.addOnFailureListener { error ->
            Log.d(TAG, "TOMATO_DEBUG SIGNUP human_challenge=failure type=${error.javaClass.simpleName}")
            action.isEnabled = true
            status.text = "Não foi possível concluir o hCaptcha."
        }
    }

    private fun login(email: String, password: String, verification: String) {
        Executors.newSingleThreadExecutor().execute {
            val result = runCatching {
                val payload = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                    put("verification", verification)
                    // TomatoSignIn writes this device value under the literal
                    // "fingerprint" key (not "model").
                    put("fingerprint", deviceFingerprint)
                }.toString()
                Log.d(
                    TAG,
                    "TOMATO_DEBUG AUTH request=start method=POST path=/login/ contentType=application/json fingerprint=$deviceFingerprint",
                )
                val connection = (URL(loginEndpoint).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                    val httpStatus = connection.responseCode
                    val rawBody = (if (httpStatus >= 400) connection.errorStream else connection.inputStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val body = runCatching { JSONObject(rawBody) }.getOrNull()
                    val apiStatus = body?.optString("status_code")?.toIntOrNull()
                    val message = body?.optString("message")?.safeLogMessage()
                    val token = body?.optString("token")?.trim()?.takeIf(String::isNotBlank)
                    val contentType = connection.contentType?.substringBefore(';') ?: "none"
                    val responseId = connection.getHeaderField("x-request-id")
                        ?: connection.getHeaderField("x-amzn-requestid")
                        ?: "none"
                    val bodyKind = if (body != null) "json" else "non-json"
                    val safeBody = message ?: rawBody.safeHttpErrorSummary()
                    Log.d(
                        TAG,
                        "TOMATO_DEBUG AUTH login HTTP=$httpStatus apiStatus=$apiStatus contentType=$contentType bodyLength=${rawBody.length} bodyKind=$bodyKind responseId=$responseId message=${safeBody ?: "none"} tokenPresent=${token != null}",
                    )
                    if (httpStatus !in 200..299 || apiStatus != 4) throw LoginException(apiStatus, message, httpStatus)
                    token ?: throw LoginException(apiStatus, "Resposta de login sem sessão", httpStatus)
                } finally {
                    connection.disconnect()
                }
            }
            runOnUiThread {
                action.isEnabled = true
                result.onSuccess { token ->
                    val receiver = resultReceiver
                    if (receiver == null) {
                        status.text = "Não foi possível entregar a sessão ao Anikku."
                        return@onSuccess
                    }
                    receiver.send(
                        RESULT_LOGIN_SUCCESS,
                        Bundle().apply { putString(EXTRA_SESSION_TOKEN, token) },
                    )
                    Log.d(TAG, "TOMATO_DEBUG AUTH session_received=true")
                    status.text = "Conectado"
                    this.password.text?.clear()
                    setResult(RESULT_OK)
                    finish()
                }.onFailure { error ->
                    Log.e(TAG, "TOMATO_DEBUG AUTH login failure type=${error.javaClass.simpleName} message=${error.message.safeLogMessage() ?: "none"}")
                    status.text = when (error) {
                        is LoginException -> error.userMessage()
                        is IOException -> "Falha de conexão ao entrar. Tente novamente."
                        else -> "Falha interna ao iniciar o login. Consulte TOMATO_DEBUG."
                    }
                }
            }
        }
    }

    /** Native counterpart of TomatoSignUp.registerRequest. */
    private fun signUp(username: String, email: String, password: String, verification: String) {
        Executors.newSingleThreadExecutor().execute {
            val result = runCatching {
                val payload = JSONObject().apply {
                    put("username", username)
                    put("email", email)
                    put("password", password)
                    put("verification", verification)
                    put("fingerprint", deviceFingerprint)
                }.toString()
                Log.d(
                    TAG,
                    "TOMATO_DEBUG SIGNUP request=start method=POST path=/register/ contentType=application/json fingerprint=$deviceFingerprint",
                )
                val connection = (URL(signUpEndpoint).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                    val httpStatus = connection.responseCode
                    val rawBody = (if (httpStatus >= 400) connection.errorStream else connection.inputStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val body = runCatching { JSONObject(rawBody) }.getOrNull()
                    val apiStatus = body?.optString("status_code")?.toIntOrNull()
                    val message = body?.optString("message")?.safeLogMessage()
                    val token = body?.optString("token")?.trim()?.takeIf(String::isNotBlank)
                    Log.d(TAG, "TOMATO_DEBUG SIGNUP HTTP=$httpStatus apiStatus=$apiStatus message=${message ?: "none"} tokenPresent=${token != null}")
                    if (httpStatus !in 200..299 || apiStatus != 4) throw SignUpException(apiStatus, message, httpStatus)
                    token ?: throw SignUpException(apiStatus, "Resposta de cadastro sem sessão", httpStatus)
                } finally {
                    connection.disconnect()
                }
            }
            runOnUiThread {
                action.isEnabled = true
                result.onSuccess { token ->
                    val receiver = resultReceiver
                    if (receiver == null) {
                        status.text = "Não foi possível entregar a sessão ao Anikku."
                        return@onSuccess
                    }
                    receiver.send(
                        RESULT_LOGIN_SUCCESS,
                        Bundle().apply { putString(EXTRA_SESSION_TOKEN, token) },
                    )
                    Log.d(TAG, "TOMATO_DEBUG SIGNUP session_received=true")
                    this@TomatoLoginActivity.password.text?.clear()
                    status.text = "Conta criada e conectada"
                    setResult(RESULT_OK)
                    finish()
                }.onFailure { error ->
                    Log.e(TAG, "TOMATO_DEBUG SIGNUP failure type=${error.javaClass.simpleName} message=${error.message.safeLogMessage() ?: "none"}")
                    status.text = when (error) {
                        is SignUpException -> error.userMessage()
                        is IOException -> "Falha de conexão ao criar a conta. Tente novamente."
                        else -> "Falha interna ao iniciar o cadastro. Consulte TOMATO_DEBUG."
                    }
                }
            }
        }
    }

    private class LoginException(
        private val apiStatus: Int?,
        message: String?,
        private val httpStatus: Int,
    ) : IOException(message) {
        fun userMessage() = when (apiStatus) {
            1 -> "Credenciais inválidas."
            30 -> "hCaptcha inválido ou expirado."
            null -> if (httpStatus >= 500) "Erro temporário da API Tomato (HTTP $httpStatus). Tente novamente." else message?.takeIf(String::isNotBlank) ?: "Erro da API Tomato (HTTP $httpStatus)."
            else -> message?.takeIf(String::isNotBlank) ?: "Erro da API Tomato (HTTP $httpStatus)."
        }
    }

    private class SignUpException(
        private val apiStatus: Int?,
        message: String?,
        private val httpStatus: Int,
    ) : IOException(message) {
        fun userMessage() = when (apiStatus) {
            8 -> "Não foi possível criar a conta."
            9 -> "Já existe uma conta com esses dados."
            10 -> "Dados de cadastro inválidos."
            11, 12 -> "Nome de usuário inválido."
            30 -> "hCaptcha inválido ou expirado."
            else -> message?.takeIf(String::isNotBlank) ?: "Erro da API Tomato (HTTP $httpStatus)."
        }
    }

    private fun String?.safeLogMessage() = this
        ?.replace(Regex("[\\r\\n]+"), " ")
        ?.take(200)

    private fun String.safeHttpErrorSummary(): String? = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace(Regex("\\s+"), " ")
        ?.take(120)

    private val loginEndpoint get() = "$apiHost/login/"
    private val signUpEndpoint get() = "$apiHost/register/"

    companion object {
        const val EXTRA_RESULT_RECEIVER = "tomato_login_result_receiver"
        const val EXTRA_SESSION_TOKEN = "tomato_login_session_token"
        const val EXTRA_API_HOST = "tomato_login_api_host"
        const val RESULT_LOGIN_SUCCESS = 1
        private const val PROD_API_HOST = "https://prod-api.tomatoanimes.com"
        private const val EDGE_API_HOST = "https://edge.betomato.com"
        private const val HCAPTCHA_SITE_KEY = "d0706611-1d89-4b8c-af79-3caf0f14feba"
        private const val TAG = "TomatoLogin"
        private const val HTTP_TIMEOUT_MS = 10_000
        private val deviceFingerprint get() = "${Build.VERSION.RELEASE}/${Build.MANUFACTURER}/${Build.MODEL}".replace(Regex("\\s"), "-")
    }
}
