package com.ets2controller.network

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean

class BeaconReceiver(
    private val context: Context,
    private val onServerDiscovered: (String) -> Unit
) {
    private val isListening = AtomicBoolean(false)
    private var listenerThread: Thread? = null
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun startListening(port: Int = 5006) {
        if (isListening.get()) return
        isListening.set(true)

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("ETS2BeaconLock")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        listenerThread = Thread({
            try {
                socket = DatagramSocket(port).apply {
                    broadcast = true
                    soTimeout = 3000
                }

                val buffer = ByteArray(256)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isListening.get()) {
                    try {
                        socket?.receive(packet)
                        val message = String(packet.data, 0, packet.length).trim()
                        if (message.contains("ETS2_CTRL_BEACON")) {
                            val serverIp = packet.address.hostAddress
                            if (!serverIp.isNullOrEmpty()) {
                                onServerDiscovered(serverIp)
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Timeout allows checking isListening flag
                    } catch (_: Exception) {
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopListening()
            }
        }, "BeaconReceiverThread").apply {
            start()
        }
    }

    fun stopListening() {
        isListening.set(false)
        try {
            socket?.close()
            socket = null
        } catch (_: Exception) {}

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }
}

