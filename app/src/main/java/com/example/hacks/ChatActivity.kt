package com.example.hacks

import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.*
import java.security.spec.X509EncodedKeySpec
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
    var timerStarted: Boolean = false
)

class ChatActivity : AppCompatActivity(), SensorEventListener {

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var out: PrintWriter? = null
    private var reader: BufferedReader? = null
    
    private var currentTimerSeconds = 0
    
    // Shake-to-Wipe Sensors
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastShakeTime: Long = 0

    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: FloatingActionButton
    private lateinit var tvStatus: TextView
    private lateinit var statusDot: View

    private var sessionId: String? = null // This is the Host IP
    private var isCreator: Boolean = false
    private var username: String? = null

    private var myKeyPair: KeyPair? = null
    private var peerPublicKey: PublicKey? = null
    private var aesKey: SecretKey? = null

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    
    private var isBiometricPromptShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots and screen recording
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chat)

        sessionId = intent.getStringExtra("SESSION_ID")
        isCreator = intent.getBooleanExtra("IS_CREATOR", false)
        username = intent.getStringExtra("USERNAME")

        findViewById<TextView>(R.id.tvSessionId).text = sessionId
        tvStatus = findViewById(R.id.tvStatus)
        statusDot = findViewById(R.id.statusDot)
        rvMessages = findViewById(R.id.rvMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)

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

        // Initialize Shake Sensor
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        generateRsaKeys()
        
        if (isCreator) {
            startServer()
            Toast.makeText(this, "Your IP: $sessionId\nShare this with the Joiner", Toast.LENGTH_LONG).show()
        } else {
            connectToServer()
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
        if (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            return
        }

        if (isBiometricPromptShowing) return

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    isBiometricPromptShowing = false
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        panicWipe()
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isBiometricPromptShowing = false
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Secure Re-entry")
            .setSubtitle("Authenticate to view chat")
            .setNegativeButtonText("Close Chat")
            .build()

        isBiometricPromptShowing = true
        biometricPrompt.authenticate(promptInfo)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val acceleration = Math.sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH
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

    private fun generateRsaKeys() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        myKeyPair = kpg.genKeyPair()
    }

    private fun startServer() {
        thread {
            try {
                serverSocket = ServerSocket(8888)
                runOnUiThread { tvStatus.text = "Listening on port 8888..." }
                clientSocket = serverSocket?.accept()
                setupCommunication()
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "Server Error: ${e.message}" }
            }
        }
    }

    private fun connectToServer() {
        thread {
            try {
                clientSocket = Socket(sessionId, 8888)
                setupCommunication()
            } catch (e: Exception) {
                runOnUiThread { 
                    tvStatus.text = "Host Unreachable" 
                    Toast.makeText(this, "Make sure Host is ready!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupCommunication() {
        try {
            out = PrintWriter(clientSocket?.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(clientSocket?.getInputStream()))
            runOnUiThread {
                tvStatus.text = "Securing..."
                statusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, android.R.color.holo_orange_light)
                )
            }
            if (!isCreator) sendPublicKey()
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                handleIncomingSignal(line!!)
            }
        } catch (e: Exception) {
            runOnUiThread { tvStatus.text = "Disconnected" }
        }
    }

    private fun handleIncomingSignal(data: String) {
        try {
            val json = JSONObject(data)
            when (json.optString("type")) {
                "public_key" -> handlePeerPublicKey(json.getString("key"))
                "aes_key" -> handleEncryptedAesKey(json.getString("key"))
                "chat" -> handleEncryptedMessage(json.getString("message"), json.optInt("timer", 0))
            }
        } catch (e: Exception) {
            Log.e("Chat", "Parse error: ${e.message}")
        }
    }

    private fun sendPublicKey() {
        val pubKeyStr = Base64.encodeToString(myKeyPair?.public?.encoded, Base64.NO_WRAP)
        val json = JSONObject()
        json.put("type", "public_key")
        json.put("key", pubKeyStr)
        out?.println(json.toString())
    }

    private fun handlePeerPublicKey(keyStr: String) {
        val keyBytes = Base64.decode(keyStr, Base64.NO_WRAP)
        val spec = X509EncodedKeySpec(keyBytes)
        val kf = KeyFactory.getInstance("RSA")
        peerPublicKey = kf.generatePublic(spec)
        if (isCreator) generateAndSendAesKey() else sendPublicKey()
    }

    private fun generateAndSendAesKey() {
        try {
            val kg = KeyGenerator.getInstance("AES")
            kg.init(128)
            aesKey = kg.generateKey()
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, peerPublicKey)
            val encryptedAesKey = cipher.doFinal(aesKey?.encoded)
            val json = JSONObject()
            json.put("type", "aes_key")
            json.put("key", Base64.encodeToString(encryptedAesKey, Base64.NO_WRAP))
            out?.println(json.toString())
            setSecuredStatus()
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "Key Exchange Failed", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun handleEncryptedAesKey(encKeyStr: String) {
        try {
            val encKeyBytes = Base64.decode(encKeyStr, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.DECRYPT_MODE, myKeyPair?.private)
            val aesKeyBytes = cipher.doFinal(encKeyBytes)
            aesKey = SecretKeySpec(aesKeyBytes, "AES")
            setSecuredStatus()
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "Security Handshake Failed", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun setSecuredStatus() {
        runOnUiThread { 
            tvStatus.text = "Secured (Direct)" 
            statusDot.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, android.R.color.holo_green_light)
            )
        }
    }

    private fun ratchetKey() {
        try {
            if (aesKey == null) return
            val sha = MessageDigest.getInstance("SHA-256")
            val newKeyBytesFull = sha.digest(aesKey!!.encoded)
            val newKeyBytes128 = newKeyBytesFull.copyOfRange(0, 16)
            aesKey = SecretKeySpec(newKeyBytes128, "AES")
        } catch (e: Exception) {
            Log.e("Chat", "Ratchet Failed", e)
        }
    }

    private fun sendMessage(text: String, timer: Int) {
        try {
            if (aesKey == null) return
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey)
            val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            val encStr = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            ratchetKey()
            val json = JSONObject()
            json.put("type", "chat")
            json.put("message", encStr)
            json.put("timer", timer)
            thread { out?.println(json.toString()) }
            addMessage(text, true, timer)
        } catch (e: Exception) {
            Toast.makeText(this, "Encryption error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleEncryptedMessage(encStr: String, timer: Int) {
        try {
            if (aesKey == null) return
            val encBytes = Base64.decode(encStr, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.DECRYPT_MODE, aesKey)
            val decrypted = cipher.doFinal(encBytes)
            val text = String(decrypted, Charsets.UTF_8)
            ratchetKey()
            runOnUiThread { addMessage(text, false, timer) }
        } catch (e: Exception) {
            Log.e("Chat", "Decrypt error")
        }
    }

    private fun addMessage(text: String, isMe: Boolean, timer: Int) {
        messages.add(ChatMessage(text, if (isMe) username ?: "Me" else "Peer", isMe, timer))
        adapter.notifyItemInserted(messages.size - 1)
        rvMessages.scrollToPosition(messages.size - 1)
    }

    private fun panicWipe() {
        messages.clear()
        adapter.notifyDataSetChanged()
        aesKey = null
        peerPublicKey = null
        thread {
            clientSocket?.close()
            serverSocket?.close()
        }
        Toast.makeText(this, "Session Wiped", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        thread {
            clientSocket?.close()
            serverSocket?.close()
        }
    }
}
