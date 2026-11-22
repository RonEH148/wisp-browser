package com.phlox.tvwebbrowser.sofa

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader

/**
 * WireGuard VPN built on the official wireguard-android tunnel library.
 * "Browser only" mode routes just Lumen's traffic; otherwise the whole TV uses the tunnel.
 */
object SofaVpn {
    enum class Status { NOT_CONFIGURED, DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    val status = ObservableValue(Status.DISCONNECTED)
    var lastError: String? = null
        private set

    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile private var stopping = false
    @Volatile private var upSince = 0L
    private var backend: GoBackend? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = "${com.phlox.tvwebbrowser.Brand.NAME}"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.UP) {
                setStatus(Status.CONNECTED)
                return
            }
            //stopped by Android right after it came up, not by us: almost always another VPN app
            //(Android allows one VPN at a time; "Always-on VPN" in another app takes it back at once)
            val early = !stopping && android.os.SystemClock.elapsedRealtime() - upSince < 20_000
            if (early) {
                lastError = "Android stopped the VPN right after it started. Only one VPN can run at a time: " +
                        "if another VPN app (for example WireGuard) is on or set to \"Always-on VPN\" " +
                        "(Android Settings → Connections → More connection settings → VPN), turn that off."
                setStatus(Status.ERROR)
            } else setStatus(Status.DISCONNECTED)
        }
    }

    private fun setStatus(value: Status) {
        uiHandler.post { status.value = value }
        if (value == Status.ERROR) WispLog.error("vpn", "VPN error: ${lastError ?: "no details"}")
        else WispLog.event("vpn", "VPN " + value.name.lowercase(java.util.Locale.ROOT))
        //the private-mode process (Wisp always starts there) reads the tunnel's state from here
        try {
            sharedState()?.edit()?.putString("status", value.name)?.putString("error", lastError ?: "")?.apply()
        } catch (e: Exception) {
        }
    }

    // ---------------- one tunnel for both processes ----------------
    // Private mode runs in its own process (":incognito"). WireGuard's VpnService always runs in the
    // main process, so the tunnel must be started there too: from any other process the request is
    // passed to SofaVpnControlService. Before, a tunnel started from private mode never came up.

    private var appContext: Context? = null

    @Suppress("DEPRECATION")
    private fun sharedState(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences("sofa_vpn_state", Context.MODE_MULTI_PROCESS)

    fun isMainProcess(context: Context): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.app.Application.getProcessName()
        } else {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        }
        return name == null || name == context.packageName
    }

    private fun sendToMainProcess(context: Context, action: String) {
        val app = context.applicationContext
        try {
            app.startService(Intent(app, SofaVpnControlService::class.java).setAction(action))
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            uiHandler.post { status.value = Status.ERROR }
        }
    }

    /** Is any VPN up on this device right now (the tunnel dies with the main process). */
    private fun vpnNetworkUp(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (e: Exception) { false }

    private fun backend(context: Context): GoBackend =
        backend ?: GoBackend(context.applicationContext).also { backend = it }

    fun hasConfig(): Boolean = AppContext.provideConfig().sofaVpnConfigEncrypted.isNotEmpty()

    fun refreshStatus() {
        val app = AppContext.get()
        appContext = app
        if (!hasConfig()) {
            status.value = Status.NOT_CONFIGURED
            return
        }
        if (!isMainProcess(app)) {
            //the tunnel lives in the main process: take its state from there
            val shared = sharedState()
            val saved = shared?.getString("status", null)?.let { n -> Status.values().firstOrNull { it.name == n } }
            var value = saved ?: Status.DISCONNECTED
            if (value == Status.CONNECTED && !vpnNetworkUp(app)) value = Status.DISCONNECTED
            if (value == Status.NOT_CONFIGURED) value = Status.DISCONNECTED
            lastError = shared?.getString("error", null)?.ifEmpty { null }
            if (status.value != value) status.value = value
        } else if (status.value == Status.NOT_CONFIGURED) {
            status.value = Status.DISCONNECTED
        }
    }

    /** Validates and stores a wg-quick style configuration. Returns an error message or null. */
    fun importConfig(text: String): String? {
        val clean = text.replace("\r", "").trim()
        try {
            Config.parse(BufferedReader(StringReader(clean)))
        } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        }
        val enc = SofaCrypto.encrypt(clean) ?: return "Android keystore is not available"
        AppContext.provideConfig().sofaVpnConfigEncrypted = enc
        refreshStatus()
        return null
    }

    fun removeConfig(context: Context) {
        disconnectAsync(context)
        AppContext.provideConfig().sofaVpnConfigEncrypted = ""
        status.value = Status.NOT_CONFIGURED
    }

    /** Name of the server (endpoint host) for display, if a config exists. */
    fun endpointLabel(): String? {
        val text = storedConfigText() ?: return null
        return Regex("(?im)^\\s*Endpoint\\s*=\\s*(\\S+)").find(text)?.groupValues?.get(1)
    }

    private fun storedConfigText(): String? {
        val enc = AppContext.provideConfig().sofaVpnConfigEncrypted
        if (enc.isEmpty()) return null
        return SofaCrypto.decrypt(enc)
    }

    /**
     * Adds the app filter required by "browser only" mode to the [Interface] section, and DNS
     * servers when the configuration has none: without them Android can't look up any name
     * through the tunnel and every page fails with "address could not be found".
     */
    private fun withRouting(text: String, packageName: String, browserOnly: Boolean): String {
        val lines = text.lines().filterNot {
            val l = it.trim().lowercase()
            l.startsWith("includedapplications") || l.startsWith("excludedapplications")
        }.toMutableList()
        val idx = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (idx >= 0) {
            if (browserOnly) lines.add(idx + 1, "IncludedApplications = $packageName")
            val hasDns = lines.any { it.trim().lowercase().replace(" ", "").startsWith("dns=") }
            //Quad9 (no Google): used only when the VPN provider's file names no DNS server
            if (!hasDns) lines.add(idx + 1, "DNS = 9.9.9.9, 149.112.112.112")
        }
        return lines.joinToString("\n")
    }

    /** Intent that asks the user to allow VPN connections, or null if already allowed. */
    fun permissionIntent(context: Context): Intent? = VpnService.prepare(context)

    suspend fun connect(context: Context): Boolean = withContext(Dispatchers.IO) {
        appContext = context.applicationContext
        if (!isMainProcess(context)) {
            uiHandler.post { status.value = Status.CONNECTING }
            sendToMainProcess(context, SofaVpnControlService.ACTION_CONNECT)
            return@withContext true
        }
        val text = storedConfigText()
        if (text == null) {
            setStatus(Status.NOT_CONFIGURED)
            return@withContext false
        }
        setStatus(Status.CONNECTING)
        try {
            val routed = withRouting(text, context.packageName, AppContext.provideConfig().sofaVpnBrowserOnly)
            val config = Config.parse(BufferedReader(StringReader(routed)))
            stopping = false
            upSince = android.os.SystemClock.elapsedRealtime()
            val state = backend(context).setState(tunnel, Tunnel.State.UP, config)
            lastError = null
            setStatus(if (state == Tunnel.State.UP) Status.CONNECTED else Status.DISCONNECTED)
            state == Tunnel.State.UP
        } catch (e: Exception) {
            e.printStackTrace()
            lastError = e.message ?: e.javaClass.simpleName
            setStatus(Status.ERROR)
            false
        }
    }

    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) {
        appContext = context.applicationContext
        if (!isMainProcess(context)) {
            sendToMainProcess(context, SofaVpnControlService.ACTION_DISCONNECT)
            uiHandler.post { status.value = if (hasConfig()) Status.DISCONNECTED else Status.NOT_CONFIGURED }
            return@withContext
        }
        stopping = true
        try {
            backend(context).setState(tunnel, Tunnel.State.DOWN, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        setStatus(if (hasConfig()) Status.DISCONNECTED else Status.NOT_CONFIGURED)
    }

    private fun disconnectAsync(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!isMainProcess(app)) {
            sendToMainProcess(app, SofaVpnControlService.ACTION_DISCONNECT)
            return
        }
        stopping = true
        Thread {
            try {
                backend(app).setState(tunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}
