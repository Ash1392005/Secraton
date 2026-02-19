package com.example.hacks

//import android.os.Bundle
import androidx.activity.enableEdgeToEdge
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.view.ViewCompat
//import androidx.core.view.WindowInsetsCompat

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.util.UUID

class selection : AppCompatActivity() {

    private lateinit var layoutModeSelect: LinearLayout
    private lateinit var layoutModeCreate: LinearLayout
    private lateinit var layoutModeJoin: LinearLayout
    private lateinit var tvUsername: TextView
    private lateinit var etSessionId: TextInputEditText

    private var currentMode = "SELECT"
    private var username: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_selection)

        username = intent.getStringExtra("USERNAME")

        layoutModeSelect = findViewById(R.id.layoutModeSelect)
        layoutModeCreate = findViewById(R.id.layoutModeCreate)
        layoutModeJoin = findViewById(R.id.layoutModeJoin)
        tvUsername = findViewById(R.id.tvUsername)
        etSessionId = findViewById(R.id.etSessionId)

        tvUsername.text = username ?: "Guest"

        val cardCreateSession = findViewById<CardView>(R.id.cardCreateSession)
        val cardJoinSession = findViewById<CardView>(R.id.cardJoinSession)
        val btnCreateSession = findViewById<MaterialButton>(R.id.btnCreateSession)
        val btnJoinSession = findViewById<MaterialButton>(R.id.btnJoinSession)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)

        cardCreateSession.setOnClickListener {
            switchMode("CREATE")
        }

        cardJoinSession.setOnClickListener {
            switchMode("JOIN")
        }

        btnBack.setOnClickListener {
            if (currentMode == "SELECT") {
                finish()
            } else {
                switchMode("SELECT")
            }
        }

        btnCreateSession.setOnClickListener {
            val ipAddress = getLocalIpAddress()
            if (ipAddress != null) {
                startChat(ipAddress, true)
            } else {
                Toast.makeText(this, "Connect to Wi-Fi first!", Toast.LENGTH_SHORT).show()
            }
        }

        btnJoinSession.setOnClickListener {
            val sessionId = etSessionId.text.toString().trim()
            if (sessionId.isNotEmpty()) {
                startChat(sessionId, false)
            } else {
                Toast.makeText(this, "Enter the Host IP", Toast.LENGTH_SHORT).show()
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress
                        // Filter for common home Wi-Fi patterns
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun switchMode(mode: String) {
        currentMode = mode
        when (mode) {
            "SELECT" -> {
                layoutModeSelect.visibility = View.VISIBLE
                layoutModeCreate.visibility = View.GONE
                layoutModeJoin.visibility = View.GONE
            }
            "CREATE" -> {
                layoutModeSelect.visibility = View.GONE
                layoutModeCreate.visibility = View.VISIBLE
                layoutModeJoin.visibility = View.GONE
            }
            "JOIN" -> {
                layoutModeSelect.visibility = View.GONE
                layoutModeCreate.visibility = View.GONE
                layoutModeJoin.visibility = View.VISIBLE
            }
        }
    }

    private fun startChat(sessionId: String, isCreator: Boolean) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra("SESSION_ID", sessionId)
        intent.putExtra("IS_CREATOR", isCreator)
        intent.putExtra("USERNAME", username)
        startActivity(intent)
    }
}