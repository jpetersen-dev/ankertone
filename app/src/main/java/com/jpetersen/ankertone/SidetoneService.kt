package com.jpetersen.ankertone

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SidetoneService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var loopJob: Job? = null

    private lateinit var audioManager: AudioManager
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var scoReceiver: BroadcastReceiver? = null

    companion object {
        const val TAG = "AnkerToneService"
        const val CHANNEL_ID = "ankertone_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SET_GAIN = "ACTION_SET_GAIN"
        const val EXTRA_GAIN = "EXTRA_GAIN"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _currentGain = MutableStateFlow(1.0f)
        val currentGain = _currentGain.asStateFlow()

        private val _btStatus = MutableStateFlow("Desconectado")
        val btStatus = _btStatus.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Retorno activado"))
                startSidetone()
            }
            ACTION_STOP -> {
                stopSidetone()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SET_GAIN -> {
                val gain = intent.getFloatExtra(EXTRA_GAIN, 1.0f)
                _currentGain.value = gain
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSidetone() {
        if (_isRunning.value) return
        _isRunning.value = true

        setupBluetoothSco()
    }

    private fun setupBluetoothSco() {
        _btStatus.value = "Conectando microfono Bluetooth..."

        // Register SCO state receiver
        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Log.d(TAG, "Bluetooth SCO conectado con exito")
                        _btStatus.value = "Audifonos Bluetooth Conectados"
                        startAudioEngine()
                    }
                    AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                        _btStatus.value = "Estableciendo enlace de audio..."
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        _btStatus.value = "Bluetooth SCO desconectado"
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        _btStatus.value = "Error al enlazar microfono Bluetooth"
                    }
                }
            }
        }

        registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))

        // Request Bluetooth SCO mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true

        // Fallback: Si el broadcast tarda o ya estaba activo, inicia el motor tras breve pausa
        serviceScope.launch {
            kotlinx.coroutines.delay(1000)
            if (loopJob == null && _isRunning.value) {
                startAudioEngine()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioEngine() {
        if (loopJob != null) return

        val sampleRate = 16000 // Tipico y optimizado para ancho de banda Bluetooth SCO
        val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
        val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBufIn = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
        val minBufOut = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)
        val bufferSize = maxOf(minBufIn, minBufOut, 1024)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfigIn,
                audioFormat,
                bufferSize
            )

            // Buscar y asignar explicitamente el dispositivo de entrada Bluetooth SCO si esta en API >= 23
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                val btDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (btDevice != null) {
                    audioRecord?.preferredDevice = btDevice
                    Log.d(TAG, "Asignado preferredDevice entrada: ${btDevice.productName}")
                }
            }

            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                sampleRate,
                channelConfigOut,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM
            )

            // Asignar salida hacia Bluetooth SCO
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devicesOut = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val btOut = devicesOut.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (btOut != null) {
                    audioTrack?.preferredDevice = btOut
                }
            }

            audioRecord?.startRecording()
            audioTrack?.play()

            loopJob = serviceScope.launch(Dispatchers.IO) {
                val audioBuffer = ShortArray(256) // Chunk pequeno para minima latencia (~16ms)
                while (isActive && _isRunning.value) {
                    val readSamples = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readSamples > 0) {
                        val gain = _currentGain.value
                        if (gain != 1.0f) {
                            for (i in 0 until readSamples) {
                                val amplified = (audioBuffer[i] * gain).roundToInt()
                                audioBuffer[i] = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            }
                        }
                        audioTrack?.write(audioBuffer, 0, readSamples)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando AudioEngine", e)
            _btStatus.value = "Error de audio: ${e.message}"
        }
    }

    private fun stopSidetone() {
        _isRunning.value = false
        loopJob?.cancel()
        loopJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo AudioRecord", e)
        }
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo AudioTrack", e)
        }
        audioTrack = null

        try {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.e(TAG, "Error liberando SCO", e)
        }

        scoReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Receiver ya desregistrado", e)
            }
            scoReceiver = null
        }

        _btStatus.value = "Desconectado"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AnkerTone Audio Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene el retorno de audio en tiempo real"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, SidetoneService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AnkerTone - Retorno de Microfono")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopSidetone()
        super.onDestroy()
    }
}
