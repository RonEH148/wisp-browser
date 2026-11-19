package com.phlox.tvwebbrowser.sofa

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs in the main process (like WireGuard's own VpnService) and starts or stops the tunnel there
 * when the browser runs in the private-mode process. See [SofaVpn].
 */
class SofaVpnControlService : Service() {
    companion object {
        const val ACTION_CONNECT = "com.phlox.tvwebbrowser.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.phlox.tvwebbrowser.vpn.DISCONNECT"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        scope.launch {
            try {
                when (action) {
                    ACTION_CONNECT -> SofaVpn.connect(applicationContext)
                    ACTION_DISCONNECT -> SofaVpn.disconnect(applicationContext)
                }
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
}
