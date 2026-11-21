package br.h10plus.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.health.connect.client.PermissionController
import org.json.JSONArray
import java.io.File

/**
 * Casca nativa: mostra o site do Monitor H10 (que continua se atualizando sozinho pelo GitHub Pages)
 * e oferece, via ponte JavaScript, o que o navegador não consegue: SDK da Polar, gravação interna do H10,
 * tela sempre ligada e compartilhamento de arquivos.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var bridge: H10Bridge
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        bridge.onPermissionsResult(res.values.all { it })
    }

    private val hcPermLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        bridge.health.onPermissionResult(granted)
    }

    fun requestHealthPermissions(perms: Set<String>) = runOnUiThread {
        try { hcPermLauncher.launch(perms) } catch (e: Exception) { bridge.emitError("Health Connect: ${e.message}") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.setBackgroundColor(0xFF0D1117.toInt())

        bridge = H10Bridge(this, web)
        web.addJavascriptInterface(bridge, "H10Native")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == APP_HOST && (url.path ?: "").startsWith(APP_PATH)) return false
                openExternal(url)
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    filePicker.launch("*/*"); true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null; false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // o site trata o "voltar" (fecha telas sobrepostas); se não houver nada aberto, sai do app
                web.evaluateJavascript("(window.__h10back && window.__h10back()) ? 1 : 0") { r ->
                    if (r != "1") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(APP_URL)
        intent?.data?.let { bridge.onDeepLink(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { bridge.onDeepLink(it) }
    }

    fun requestBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) bridge.onPermissionsResult(true) else permLauncher.launch(missing.toTypedArray())
    }

    fun setKeepScreenOn(on: Boolean) = runOnUiThread {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun setImmersiveMode(on: Boolean) = runOnUiThread {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Recebe [{name, mime, content}] e abre o menu de compartilhar do Android. */
    fun shareFiles(json: String) = runOnUiThread {
        try {
            val arr = JSONArray(json)
            val dir = File(cacheDir, "export").apply { mkdirs() }
            val uris = ArrayList<Uri>()
            var mime = "text/plain"
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.getString("name").replace(Regex("[^A-Za-z0-9._-]"), "_")
                val f = File(dir, name)
                f.writeText(o.getString("content"))
                uris.add(FileProvider.getUriForFile(this, "$packageName.files", f))
                mime = o.optString("mime", mime).ifEmpty { mime }
            }
            if (uris.isEmpty()) return@runOnUiThread
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uris[0]) }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Exportar"))
        } catch (e: Exception) {
            bridge.emitError("Falha ao exportar: ${e.message}")
        }
    }

    fun openExternal(uri: Uri) = runOnUiThread {
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) { }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        bridge.shutdown()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val APP_HOST = "henriquearaujocfp.github.io"
        const val APP_PATH = "/H10-plus"
        const val APP_URL = "https://henriquearaujocfp.github.io/H10-plus/?app=1"
    }
}
