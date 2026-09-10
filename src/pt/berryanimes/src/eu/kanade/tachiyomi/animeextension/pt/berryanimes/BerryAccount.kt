package eu.kanade.tachiyomi.animeextension.pt.berryanimes

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicBoolean

internal class BerryAccount(private val client: OkHttpClient, private val headers: Headers) {
    private val sso = "https://sso.berryanimes.com"
    private val main = "https://berryanimes.com"
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    @Volatile private var verification: Document? = null
    private var resendAfter = 0L

    private fun submit(document: Document, form: Element, fields: Map<String, String>): Document {
        val page = document.location().toHttpUrl()
        val target = page.resolve(form.attr("action")) ?: error("Formulário indisponível")
        check(target.host == "sso.berryanimes.com" && target.isHttps && form.attr("method").equals("POST", true)) {
            "O formulário de autenticação mudou"
        }
        check(form.select("input[type=hidden]").any { it.attr("name").startsWith("\$ACTION_ID_") }) {
            "O formulário de autenticação mudou"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            form.select("input[type=hidden][name]").forEach { addFormDataPart(it.attr("name"), it.attr("value")) }
            fields.forEach { (name, value) -> addFormDataPart(name, value) }
        }.build()
        val authHeaders = headers.newBuilder().set("Origin", sso).set("Referer", page.toString()).build()
        return client.newCall(POST(target.toString(), authHeaders, body)).execute().use {
            check(it.isSuccessful) { "Autenticação recusada (HTTP ${it.code})" }
            it.asJsoup()
        }
    }

    private fun acceptResponse(document: Document): Boolean {
        if (document.selectFirst("form:has(input[name=code])") != null) {
            verification = document
            return false
        }
        check(document.select("form input[name=password]").isEmpty()) {
            "Login não confirmado. Confira suas credenciais."
        }
        check(connected()) { "Não foi possível confirmar a sessão Berry Animes" }
        verification = null
        CookieManager.getInstance().flush()
        return true
    }

    private fun connected(): Boolean = client.newCall(GET("$main/api/user/achievements/unread", headers)).execute().use {
        when (it.code) {
            200 -> it.request.url.host == "berryanimes.com" && it.header("Content-Type").orEmpty().contains("application/json")
            401 -> false
            else -> error("Não foi possível consultar a sessão (HTTP ${it.code})")
        }
    }

    private fun login(email: String, password: String): String {
        verification = null
        val loginUrl = "$sso/login"
        val document = client.newCall(GET(loginUrl, headers)).execute().use {
            check(it.isSuccessful) { "Não foi possível abrir o SSO" }
            it.asJsoup()
        }
        val form = document.selectFirst("form:has(input[name=email]):has(input[name=password])")
            ?: error("Formulário de login indisponível")
        val result = submit(document, form, mapOf("email" to email, "password" to password))
        if (acceptResponse(result)) return "Conectado"
        resendAfter = SystemClock.elapsedRealtime() + 60_000
        return "Digite o código enviado por e-mail"
    }

    private fun verify(code: String): String {
        check(code.length == 6 && code.all { it in '0'..'9' }) { "Digite os 6 dígitos do código" }
        val document = verification ?: error("Entre novamente para solicitar um código")
        val form = document.selectFirst("form:has(input[name=code])") ?: error("Verificação indisponível")
        val result = submit(document, form, mapOf("code" to code))
        check(acceptResponse(result)) { "Código não confirmado: confira o código mais recente ou solicite outro se expirou" }
        return "Conectado"
    }

    private fun resend(): String {
        val remaining = (resendAfter - SystemClock.elapsedRealtime() + 999) / 1000
        check(remaining <= 0) { "Aguarde $remaining segundos para reenviar" }
        val document = verification ?: error("Entre novamente para solicitar um código")
        val form = document.select("form").firstOrNull {
            it.selectFirst("input[name=code]") == null && it.selectFirst("input[name=mode]") != null
        } ?: error("Reenvio indisponível")
        val result = submit(document, form, emptyMap())
        check(result.selectFirst("form:has(input[name=code])") != null) { "Não foi possível reenviar o código" }
        verification = result
        resendAfter = SystemClock.elapsedRealtime() + 60_000
        return "Consulte seu e-mail e use o código mais recente"
    }

    private fun logout() {
        verification = null
        try {
            // The public ProfileMenu links directly to the SSO's /logout route.
            client.newCall(GET("$sso/logout", headers)).execute().close()
        } finally {
            val cookies = CookieManager.getInstance()
            for (host in listOf(main, sso)) {
                val names = cookies.getCookie(host).orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
                for (name in names) {
                    cookies.setCookie(host, "$name=; Path=/; Max-Age=0; Secure; HttpOnly")
                    cookies.setCookie(host, "$name=; Domain=.berryanimes.com; Path=/; Max-Age=0; Secure; HttpOnly")
                }
            }
            cookies.flush()
        }
    }

    fun addPreferences(screen: PreferenceScreen) {
        val context = screen.context
        val status = EditTextPreference(context).apply {
            key = "berry_account_status"
            title = "Conta Berry Animes"
            summary = "Use Ações da conta para verificar a sessão"
            setEnabled(false)
        }
        var showVerification: (() -> Unit)? = null
        fun runTask(showCode: Boolean = false, notify: Boolean = true, action: () -> String) {
            if (!busy.compareAndSet(false, true)) return
            status.summary = "Aguarde…"
            Thread {
                val message = try {
                    action()
                } catch (e: IllegalStateException) {
                    e.message ?: "Não foi possível completar a operação"
                } catch (_: Exception) {
                    "Falha de conexão com Berry Animes"
                }
                handler.post {
                    busy.set(false)
                    status.summary = message
                    if (notify) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    if (showCode && verification != null) showVerification?.invoke()
                }
            }.start()
        }
        showVerification = {
            val code = EditText(context).apply {
                hint = "Código de 6 dígitos"
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(6))
            }
            val dialog = AlertDialog.Builder(context)
                .setTitle("Verificar login")
                .setMessage("Digite o código enviado por e-mail. Ele expira em 15 minutos.")
                .setView(code)
                .setPositiveButton("Verificar") { _, _ ->
                    val value = code.text.toString().trim()
                    code.text.clear()
                    runTask(showCode = true) { verify(value) }
                }
                .setNeutralButton("Reenviar código") { _, _ -> runTask(showCode = true) { resend() } }
                .setNegativeButton("Depois", null).create()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.setOnDismissListener { code.text.clear() }
            dialog.show()
        }
        screen.addPreference(status)
        screen.addPreference(
            ListPreference(context).apply {
                key = "berry_account_actions"
                title = "Ações da conta"
                summary = "E-mail e senha; somente a sessão fica salva no aplicativo"
                entries = arrayOf("Entrar", "Informar código de verificação", "Criar conta", "Verificar sessão", "Sair / limpar sessão")
                entryValues = arrayOf("login", "verify", "register", "status", "logout")
                setOnPreferenceChangeListener { _, value ->
                    if (busy.get()) return@setOnPreferenceChangeListener false
                    if (value == "verify") {
                        if (verification != null) showVerification?.invoke() else Toast.makeText(context, "Use Entrar para solicitar um código", Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    if (value == "register") {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$sso/register")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "Instale um navegador para abrir o cadastro oficial", Toast.LENGTH_LONG).show()
                        }
                        return@setOnPreferenceChangeListener false
                    }
                    if (value == "status") {
                        runTask { if (connected()) "Conectado" else "Desconectado" }
                        return@setOnPreferenceChangeListener false
                    }
                    if (value == "logout") {
                        runTask {
                            logout()
                            if (connected()) "A sessão continua ativa; tente sair novamente" else "Desconectado"
                        }
                        return@setOnPreferenceChangeListener false
                    }
                    val email = EditText(context).apply {
                        hint = "E-mail"
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    }
                    val password = EditText(context).apply {
                        hint = "Senha"
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    val layout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        val padding = (16 * resources.displayMetrics.density).toInt()
                        setPadding(padding, padding, padding, padding)
                        addView(email)
                        addView(password)
                    }
                    val dialog = AlertDialog.Builder(context).setTitle("Entrar no Berry Animes")
                        .setView(layout)
                        .setPositiveButton("Entrar") { _, _ ->
                            val address = email.text.toString().trim()
                            val secret = password.text.toString()
                            password.text.clear()
                            if (address.isNotEmpty() && secret.isNotEmpty()) {
                                runTask(showCode = true) {
                                    login(address, secret)
                                }
                            } else {
                                Toast.makeText(context, "Preencha e-mail e senha", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("Cancelar", null).create()
                    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    dialog.setOnDismissListener { password.text.clear() }
                    dialog.show()
                    false
                }
            },
        )
        screen.addPreference(
            EditTextPreference(context).apply {
                key = "berry_development_notice"
                title = "Reprodução"
                summary = "HLS autenticado. Quando o site exigir anúncios, use o player oficial. A senha e o código não são armazenados."
                setEnabled(false)
            },
        )
        runTask(notify = false) { if (connected()) "Conectado" else "Desconectado" }
    }
}
