package com.example.hacks

import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

data class ChatMessage(
    val text: String, 
    val sender: String, 
    val isMe: Boolean, 
    val expiryTime: Int = 0,
    var timerStarted: Boolean = false,
    val isAudio: Boolean = false,
    val audioPath: String? = null
)

class ChatActivity : AppCompatActivity(), SensorEventListener {

    // Network for Host
    private var serverSocket: ServerSocket? = null
    private val clientHandlers = Collections.synchronizedList(mutableListOf<ClientHandler>())
    
    // Network for Client
    private var clientSocket: Socket? = null
    private var clientOutput: DataOutputStream? = null
    private var clientInput: DataInputStream? = null
    
    private var currentTimerSeconds = 0
    
    // Shake-to-Wipe Sensors
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastShakeTime: Long = 0

    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: FloatingActionButton
    private lateinit var btnRecord: ImageButton
    private lateinit var tvStatus: TextView
    private lateinit var statusDot: View

    private var sessionId: String? = null
    private var isCreator: Boolean = false
    private var isGroup: Boolean = false
    private var username: String? = null

    private var myKeyPair: KeyPair? = null
    private var aesKey: SecretKey? = null // Shared Group Key

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    
    private var isBiometricPromptShowing = false
    
    // Audio variables
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chat)

        sessionId = intent.getStringExtra("SESSION_ID")
        isCreator = intent.getBooleanExtra("IS_CREATOR", false)
        isGroup = intent.getBooleanExtra("IS_GROUP", false)
        username = intent.getStringExtra("USERNAME")
        
        findViewById<TextView>(R.id.tvSessionId).text = sessionId
        findViewById<TextView>(R.id.tvChatWith).text = if (isGroup) "Group Session" else "Private Session"
        tvStatus = findViewById(R.id.tvStatus)
        statusDot = findViewById(R.id.statusDot)
        rvMessages = findViewById(R.id.rvMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        btnRecord = findViewById(R.id.btnRecord)

        adapter = ChatAdapter(messages, username ?: "Me")
        rvMessages.layoutManager = LinearLayoutManager(this)
        rvMessages.adapter = adapter

        findViewById<ImageButton>(R.id.btnChatBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnPanic).setOnClickListener { panicWipe() }
        
        val decoyLayout = findViewById<View>(R.id.decoyLayout)
        findViewById<ImageButton>(R.id.btnDecoy).setOnClickListener {
            decoyLayout.visibility = View.VISIBLE
        }
        decoyLayout.setOnClickListener {
            decoyLayout.visibility = View.GONE
        }

        val btnTimer = findViewById<ImageButton>(R.id.btnTimer)
        btnTimer.setOnClickListener {
            currentTimerSeconds = when (currentTimerSeconds) {
                0 -> 5
                5 -> 10
                10 -> 30
                else -> 0
            }
            val timerText = if (currentTimerSeconds == 0) "Timer: OFF" else "Timer: ${currentTimerSeconds}s"
            Toast.makeText(this, timerText, Toast.LENGTH_SHORT).show()
            btnTimer.imageTintList = if (currentTimerSeconds == 0) 
                ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.darker_gray))
                else ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_orange_light))
        }

        btnRecord.setOnLongClickListener {
            startRecording()
            true
        }
        
        btnRecord.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP && isRecording) {
                stopRecording()
            }
            false
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        generateRsaKeys()
        if (isCreator) {
            generateGroupAesKey()
            startServer()
        } else {
            startClient()
        }

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                if (aesKey != null) {
                    sendMessage(text, currentTimerSeconds)
                    etMessage.text.clear()
                } else {
                    Toast.makeText(this, "Wait for secure handshake...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 100)
            return
        }

        try {
            audioFile = File(cacheDir, "temp_audio.m4a")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            btnRecord.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_red_light))
            Toast.makeText(this, "Recording...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            btnRecord.imageTintList = ColorStateList.valueOf(Color.parseColor("#94A3B8"))
            
            if (audioFile != null && audioFile!!.exists()) {
                sendAudio(audioFile!!)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendAudio(file: File) {
        thread {
            try {
                val bytes = file.readBytes()
                if (aesKey == null) return@thread
                
                val cipher = Cipher.getInstance("AES")
                cipher.init(Cipher.ENCRYPT_MODE, aesKey)
                val encrypted = cipher.doFinal(bytes)
                val encStr = Base64.encodeToString(encrypted, Base64.NO_WRAP)
                
                val json = JSONObject()
                json.put("type", "audio")
                json.put("sender", username)
                json.put("message", encStr)
                json.put("timer", currentTimerSeconds)
                
                sendData(json.toString())
                
                val displayFile = File(cacheDir, "sent_${System.currentTimeMillis()}.m4a")
                file.copyTo(displayFile)

                runOnUiThread {
                    addMessage("[Audio Message]", true, currentTimerSeconds, username ?: "Me", true, displayFile.absolutePath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleEncryptedAudio(encStr: String, timer: Int, sender: String) {
        try {
            if (aesKey == null) return
            val encBytes = Base64.decode(encStr, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.DECRYPT_MODE, aesKey)
            val decrypted = cipher.doFinal(encBytes)
            
            val audioFile = File(cacheDir, "received_${System.currentTimeMillis()}.m4a")
            audioFile.writeBytes(decrypted)
            
            runOnUiThread { 
                addMessage("[Audio Message]", false, timer, sender, true, audioFile.absolutePath) 
            }
        } catch (e: Exception) {
            Log.e("Chat", "Decrypt audio error")
        }
    }

    private fun startServer() {
        thread {
            try {
                serverSocket = ServerSocket(8888)
                runOnUiThread { 
                    tvStatus.text = if (isGroup) "Server Live (Group)" else "Waiting for peer..."
                    statusDot.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this, android.R.color.holo_green_light)
                    )
                }
                while (serverSocket != null && !serverSocket!!.isClosed) {
                    val socket = serverSocket!!.accept()
                    if (!isGroup && clientHandlers.size >= 1) {
                        socket.close()
                        continue
                    }
                    val handler = ClientHandler(socket)
                    clientHandlers.add(handler)
                    handler.start()
                    if (!isGroup) {
                        runOnUiThread { tvStatus.text = "Peer Connected (Private)" }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startClient() {
        thread {
            try {
                runOnUiThread { tvStatus.text = "Joining..." }
                clientSocket = Socket(sessionId, 8888)
                clientOutput = DataOutputStream(clientSocket?.getOutputStream())
                clientInput = DataInputStream(clientSocket?.getInputStream())

                runOnUiThread { 
                    tvStatus.text = "Connected"
                    statusDot.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this, android.R.color.holo_green_light)
                    )
                }

                sendPublicKeyToHost()

                while (clientSocket != null && !clientSocket!!.isClosed) {
                    val payload = clientInput?.readUTF() ?: break
                    runOnUiThread { handleIncomingSignal(payload, null) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { 
                    tvStatus.text = "Offline"
                    statusDot.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this, android.R.color.darker_gray)
                    )
                }
            }
        }
    }

    inner class ClientHandler(val socket: Socket) : Thread() {
        val output = DataOutputStream(socket.getOutputStream())
        val input = DataInputStream(socket.getInputStream())
        var peerPublicKey: PublicKey? = null

        override fun run() {
            try {
                while (!socket.isClosed) {
                    val payload = input.readUTF()
                    runOnUiThread { handleIncomingSignal(payload, this) }
                }
            } catch (e: Exception) {
                clientHandlers.remove(this)
            }
        }

        fun send(data: String) {
            thread {
                try {
                    output.writeUTF(data)
                    output.flush()
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun sendData(payload: String) {
        if (isCreator) {
            synchronized(clientHandlers) {
                clientHandlers.forEach { it.send(payload) }
            }
        } else {
            thread {
                try {
                    clientOutput?.writeUTF(payload)
                    clientOutput?.flush()
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun handleIncomingSignal(data: String, sender: ClientHandler?) {
        try {
            val json = JSONObject(data)
            when (json.optString("type")) {
                "public_key" -> {
                    if (isCreator && sender != null) {
                        handlePeerPublicKey(json.getString("key"), sender)
                    }
                }
                "aes_key" -> {
                    if (!isCreator) handleEncryptedAesKey(json.getString("key"))
                }
                "chat" -> {
                    val senderName = json.optString("sender")
                    if (senderName != username) {
                        handleEncryptedMessage(json.getString("message"), json.optInt("timer", 0), senderName)
                        if (isCreator) {
                            synchronized(clientHandlers) {
                                clientHandlers.filter { it != sender }.forEach { it.send(data) }
                            }
                        }
                    }
                }
                "audio" -> {
                    val senderName = json.optString("sender")
                    if (senderName != username) {
                        handleEncryptedAudio(json.getString("message"), json.optInt("timer", 0), senderName)
                        if (isCreator) {
                            synchronized(clientHandlers) {
                                clientHandlers.filter { it != sender }.forEach { it.send(data) }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Chat", "Parse error: ${e.message}")
        }
    }

    private fun generateRsaKeys() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        myKeyPair = kpg.genKeyPair()
    }

    private fun generateGroupAesKey() {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(128)
        aesKey = kg.generateKey()
    }

    private fun sendPublicKeyToHost() {
        val pubKeyStr = Base64.encodeToString(myKeyPair?.public?.encoded, Base64.NO_WRAP)
        val json = JSONObject()
        json.put("type", "public_key")
        json.put("sender", username)
        json.put("key", pubKeyStr)
        sendData(json.toString())
    }

    private fun handlePeerPublicKey(keyStr: String, sender: ClientHandler) {
        try {
            val keyBytes = Base64.decode(keyStr, Base64.NO_WRAP)
            val spec = X509EncodedKeySpec(keyBytes)
            val kf = KeyFactory.getInstance("RSA")
            sender.peerPublicKey = kf.generatePublic(spec)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, sender.peerPublicKey)
            val encryptedAesKey = cipher.doFinal(aesKey?.encoded)
            val json = JSONObject()
            json.put("type", "aes_key")
            json.put("key", Base64.encodeToString(encryptedAesKey, Base64.NO_WRAP))
            sender.send(json.toString())
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun handleEncryptedAesKey(encKeyStr: String) {
        try {
            val encKeyBytes = Base64.decode(encKeyStr, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.DECRYPT_MODE, myKeyPair?.private)
            val aesKeyBytes = cipher.doFinal(encKeyBytes)
            aesKey = SecretKeySpec(aesKeyBytes, "AES")
            runOnUiThread { Toast.makeText(this, "Secure Session Ready", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "Handshake Failed", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun sendMessage(text: String, timer: Int) {
        try {
            if (aesKey == null) return
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey)
            val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            val encStr = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            val json = JSONObject()
            json.put("type", "chat")
            json.put("sender", username)
            json.put("message", encStr)
            json.put("timer", timer)
            sendData(json.toString())
            addMessage(text, true, timer, username ?: "Me")
        } catch (e: Exception) {
            Toast.makeText(this, "Encryption error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleEncryptedMessage(encStr: String, timer: Int, sender: String) {
        try {
            if (aesKey == null) return
            val encBytes = Base64.decode(encStr, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.DECRYPT_MODE, aesKey)
            val decrypted = cipher.doFinal(encBytes)
            val text = String(decrypted, Charsets.UTF_8)
            runOnUiThread { addMessage(text, false, timer, sender) }
        } catch (e: Exception) {
            Log.e("Chat", "Decrypt error")
        }
    }

    private fun addMessage(text: String, isMe: Boolean, timer: Int, sender: String, isAudio: Boolean = false, audioPath: String? = null) {
        messages.add(ChatMessage(text, sender, isMe, timer, false, isAudio, audioPath))
        adapter.notifyItemInserted(messages.size - 1)
        rvMessages.scrollToPosition(messages.size - 1)
    }

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        showBiometricAuth()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    private fun showBiometricAuth() {
        val biometricManager = androidx.biometric.BiometricManager.from(this)
        if (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) return
        if (isBiometricPromptShowing) return
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                isBiometricPromptShowing = false
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) panicWipe()
            }
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                isBiometricPromptShowing = false
            }
        })
        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("Secure Re-entry").setSubtitle("Authenticate to view chat").setNegativeButtonText("Close Chat").build()
        isBiometricPromptShowing = true
        biometricPrompt.authenticate(promptInfo)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val acceleration = Math.sqrt((event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]).toDouble()) - SensorManager.GRAVITY_EARTH
            if (acceleration > 13.0) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > 2000) {
                    lastShakeTime = now
                    runOnUiThread { panicWipe() }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun panicWipe() {
        messages.clear()
        adapter.notifyDataSetChanged()
        aesKey = null
        closeConnections()
        Toast.makeText(this, "Session Wiped", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun closeConnections() {
        try {
            serverSocket?.close()
            clientSocket?.close()
            synchronized(clientHandlers) {
                clientHandlers.forEach { it.socket.close() }
                clientHandlers.clear()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeConnections()
    }
}
