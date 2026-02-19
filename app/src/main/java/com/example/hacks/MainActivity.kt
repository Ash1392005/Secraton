package com.example.hacks

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import android.content.Intent
//import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
//import androidx.appcompat.app.AppCompatActivity
//import com.example.hacks.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val etUsername = findViewById<TextInputEditText>(R.id.etUsername)
        val btnEnter = findViewById<MaterialButton>(R.id.btnEnter)

        btnEnter.setOnClickListener {
            val username = etUsername.text.toString().trim()
            if (username.isNotEmpty()) {
                val intent = Intent(this, selection::class.java)
                intent.putExtra("USERNAME", username)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
