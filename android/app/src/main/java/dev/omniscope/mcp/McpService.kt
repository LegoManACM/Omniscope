package dev.omniscope.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import dev.omniscope.App
import dev.omniscope.MainActivity
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

/** Keeps the MCP server (and location updates) running while the app is closed, with a notification. */
class McpService : Service() {
    private var server: McpServer? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as App
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "MCP server", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sharing your surroundings over MCP")
            .setContentText(urls().firstOrNull() ?: "Port ${McpServer.PORT}")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // E.g. restarted by the system without location permission in the foreground: give up quietly.
            Log.w(TAG, "Can't start in the foreground: $e")
            stopSelf()
            return
        }
        app.location.acquire("mcp")
        server = McpServer(app, McpServer.PORT, app.mcpToken).also {
            try {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                app.mcpRunning.value = true
            } catch (e: Exception) {
                Log.e(TAG, "MCP server failed to start", e)
                app.mcpError.value = e.message
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        val app = application as App
        server?.stop()
        app.location.release("mcp")
        app.mcpRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "McpService"
        private const val CHANNEL = "mcp"

        /** http://<ip>:8787/mcp for every IPv4 address, Tailscale (100.64.0.0/10) first. */
        fun urls(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .map { it.hostAddress!! }
                .sortedBy { if (isTailscale(it)) 0 else 1 }
                .map { "http://$it:${McpServer.PORT}/mcp" }
        }.getOrDefault(emptyList())

        fun isTailscale(ip: String): Boolean {
            val p = ip.split('.').mapNotNull { it.toIntOrNull() }
            return p.size == 4 && p[0] == 100 && p[1] in 64..127
        }
    }
}
