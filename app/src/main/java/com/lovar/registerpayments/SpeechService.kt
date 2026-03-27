package com.lovar.registerpayments

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import java.util.Locale

class SpeechService : Service() {

    companion object {
        private const val CHANNEL_ID = "lovar_listen_channel"
        private const val NOTIF_ID = 9991
        private val HOTWORDS = listOf("lovar", "hola lovar", "hey lovar", "oye lovar")
    }

    private var sr: SpeechRecognizer? = null
    private lateinit var intentRec: Intent
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Escuchando (inactivo)"))
        initRecognizer()
        startListeningLoop()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "Escucha continua", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lovar - escucha activa")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        intentRec = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("es", "AR"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // short timeout preferences
        }
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { updateNotif("Escuchando...") }
            override fun onBeginningOfSpeech() { updateNotif("Hablando...") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { /* seguirá reiniciando en onResults/onError */ }
            override fun onError(error: Int) {
                updateNotif("Error $error, reiniciando")
                restartListeningWithDelay()
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty()
                checkHotwordAndNotify(text)
                // reiniciar escucha inmediatamente
                restartListeningWithDelay()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (partial.isNotBlank()) checkHotwordAndNotify(partial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun checkHotwordAndNotify(text: String) {
        val t = text.lowercase(Locale.getDefault())
        for (w in HOTWORDS) {
            if (t.contains(w)) {
                // enviar broadcast para que MainActivity active escucha completa
                val b = Intent("com.lovar.HOTWORD_DETECTED")
                b.putExtra("detected_text", text)
                sendBroadcast(b)
                updateNotif("Hotword detectada")
                return
            }
        }
    }

    private fun updateNotif(txt: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(txt))
    }

    private fun startListeningLoop() {
        if (running) return
        running = true
        try {
            sr?.startListening(intentRec)
        } catch (_: Throwable) {
            restartListeningWithDelay()
        }
    }

    private fun restartListeningWithDelay(delayMs: Long = 400) {
        try { sr?.cancel() } catch (_: Throwable) {}
        handler.postDelayed({
            try { sr?.startListening(intentRec) } catch (_: Throwable) { handler.postDelayed({ startListeningLoop() }, 1000) }
        }, delayMs)
    }

    override fun onDestroy() {
        running = false
        try { sr?.cancel(); sr?.destroy() } catch (_: Throwable) {}
        sr = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // mantener servicio
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

