package eu.kanade.tachiyomi.animeextension.pt.tomato

import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.text.InputType
import android.util.Patterns
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
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
            if (verification.isBlank()) {
                action.isEnabled = true
                status.text = "O hCaptcha não retornou um token válido. Tente novamente."
                return@addOnSuccessListener
            }
            login(enteredEmail, enteredPassword, verification)
        }.addOnFailureListener { error ->
            action.isEnabled = true
            status.text = error.hCaptchaMessage()
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
            !Patterns.EMAIL_ADDRESS.matcher(enteredEmail).matches() -> {
                status.text = "Informe um e-mail válido."
                return
            }
            enteredPassword.length !in 6..127 -> {
                status.text = "A senha deve ter entre 6 e 127 caracteres."
                return
            }
        }
        action.isEnabled = false
        status.text = "Confirme o hCaptcha para criar a conta..."
        HCaptcha.getClient(this).verifyWithHCaptcha(
            HCaptchaConfig.builder().siteKey(HCAPTCHA_SITE_KEY).theme(HCaptchaTheme.DARK).build(),
        ).addOnSuccessListener { response ->
            val verification = response.tokenResult
            if (verification.isBlank()) {
                action.isEnabled = true
                status.text = "O hCaptcha não retornou um token válido. Tente novamente."
                return@addOnSuccessListener
            }
            signUp(enteredUsername, enteredEmail, enteredPassword, verification)
        }.addOnFailureListener { error ->
            action.isEnabled = true
            status.text = error.hCaptchaMessage()
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
                val connection = (URL(loginEndpoint).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                    val httpStatus = connection.responseCode
                    val rawBody = (if (httpStatus >= 400) connection.errorStream else connection.inputStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val body = runCatching { JSONObject(rawBody) }.getOrNull()
                    val apiStatus = body?.optString("status_code")?.toIntOrNull()
                    val message = body?.apiMessage()
                    val token = body?.optString("token")?.trim()?.takeIf(String::isNotBlank)
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
                    status.text = "Conectado"
                    this.password.text?.clear()
                    setResult(RESULT_OK)
                    finish()
                }.onFailure { error ->
                    status.text = when (error) {
                        is LoginException -> error.userMessage()
                        else -> error.connectionMessage()
                    }
                }
            }
        }
    }

    /** Native counterpart of TomatoSignUp.registerRequest. */
    private fun signUp(
        username: String,
        email: String,
        password: String,
        verification: String,
    ) {
        Executors.newSingleThreadExecutor().execute {
            val result = runCatching {
                val payload = JSONObject().apply {
                    put("username", username)
                    put("email", email)
                    put("password", password)
                    put("verification", verification)
                    put("fingerprint", deviceFingerprint)
                }.toString()
                val connection = (URL(signUpEndpoint).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                    val httpStatus = connection.responseCode
                    val rawBody = (if (httpStatus >= 400) connection.errorStream else connection.inputStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val body = runCatching { JSONObject(rawBody) }.getOrNull()
                    val apiStatus = body?.optString("status_code")?.toIntOrNull()
                    val message = body?.apiMessage()
                    val token = body?.optString("token")?.trim()?.takeIf(String::isNotBlank)
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
                    this@TomatoLoginActivity.password.text?.clear()
                    status.text = "Conta criada e conectada"
                    setResult(RESULT_OK)
                    finish()
                }.onFailure { error ->
                    status.text = when (error) {
                        is SignUpException -> error.userMessage()
                        else -> error.connectionMessage()
                    }
                }
            }
        }
    }

    private inner class LoginException(
        private val apiStatus: Int?,
        message: String?,
        private val httpStatus: Int,
    ) : IOException(message) {
        fun userMessage() = when {
            apiStatus == 31 || httpStatus == 429 -> RATE_LIMIT_MESSAGE
            message.isMaintenanceMessage() -> MAINTENANCE_MESSAGE
            httpStatus in 500..599 -> SERVER_UNAVAILABLE_MESSAGE
            apiStatus == 1 || httpStatus == 401 || httpStatus == 403 -> "E-mail ou senha inválidos."
            apiStatus == 30 -> "hCaptcha inválido ou expirado."
            else -> message.usefulApiMessage() ?: UNEXPECTED_ERROR_MESSAGE
        }
    }

    private inner class SignUpException(
        private val apiStatus: Int?,
        message: String?,
        private val httpStatus: Int,
    ) : IOException(message) {
        fun userMessage() = when {
            apiStatus == 31 || httpStatus == 429 -> RATE_LIMIT_MESSAGE
            message.isMaintenanceMessage() -> MAINTENANCE_MESSAGE
            httpStatus in 500..599 -> SERVER_UNAVAILABLE_MESSAGE
            apiStatus == 5 -> message.usefulApiMessage() ?: "A senha deve ter entre 6 e 127 caracteres."
            apiStatus == 9 -> "Já existe uma conta com esses dados."
            apiStatus == 10 -> "Dados de cadastro inválidos."
            apiStatus == 11 || apiStatus == 12 -> "Nome de usuário inválido."
            apiStatus == 30 -> "hCaptcha inválido ou expirado."
            else -> message.usefulApiMessage() ?: UNEXPECTED_ERROR_MESSAGE
        }
    }

    private fun String?.cleanApiMessage() = this
        ?.replace(Regex("[\\r\\n]+"), " ")
        ?.trim()
        ?.take(300)

    private fun JSONObject.apiMessage(): String? = sequenceOf("message", "status", "error", "detail")
        .mapNotNull { opt(it) as? String }
        .mapNotNull { it.cleanApiMessage()?.takeIf(String::isNotBlank) }
        .firstOrNull()

    private fun String?.usefulApiMessage(): String? = cleanApiMessage()
        ?.takeUnless { it.equals("Unable to complete registration.", ignoreCase = true) }

    private fun String?.isMaintenanceMessage(): Boolean = this?.let {
        it.contains("maintenance", ignoreCase = true) || it.contains("manuten", ignoreCase = true)
    } == true

    private fun Throwable.connectionMessage() = when (this) {
        is SocketTimeoutException, is ConnectException -> SERVER_UNAVAILABLE_MESSAGE
        is UnknownHostException, is IOException -> CONNECTION_ERROR_MESSAGE
        else -> UNEXPECTED_ERROR_MESSAGE
    }

    private fun com.hcaptcha.sdk.HCaptchaException.hCaptchaMessage() = when (statusCode) {
        15 -> "O hCaptcha expirou. Tente novamente."
        31 -> RATE_LIMIT_MESSAGE
        else -> "Não foi possível concluir o hCaptcha. Tente novamente."
    }

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
        private const val HTTP_TIMEOUT_MS = 10_000
        private const val SERVER_UNAVAILABLE_MESSAGE =
            "O servidor da Tomato está indisponível no momento. Tente novamente mais tarde."
        private const val CONNECTION_ERROR_MESSAGE =
            "Não foi possível conectar ao servidor da Tomato. Verifique sua internet ou tente novamente mais tarde."
        private const val RATE_LIMIT_MESSAGE =
            "A Tomato bloqueou temporariamente novas tentativas neste dispositivo/IP. Aguarde um tempo antes de tentar novamente."
        private const val MAINTENANCE_MESSAGE = "A Tomato está em manutenção. Tente novamente mais tarde."
        private const val UNEXPECTED_ERROR_MESSAGE = "A Tomato retornou um erro inesperado. Tente novamente mais tarde."
        private val deviceFingerprint get() = "${Build.VERSION.RELEASE}/${Build.MANUFACTURER}/${Build.MODEL}".replace(Regex("\\s"), "-")
    }
}
