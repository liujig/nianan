package com.nianan.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val radioGroup = findViewById<RadioGroup>(R.id.radioGroup)
        val editPort = findViewById<EditText>(R.id.editPort)
        val btnConfirm = findViewById<Button>(R.id.btnConfirm)

        val prefs = getSharedPreferences("nianan_config", MODE_PRIVATE)
        val saved = prefs.getString("framework", null)
        if (saved != null) {
            // 已配置过，直接进主页
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        btnConfirm.setOnClickListener {
            val port = editPort.text.toString().trim()
            if (port.isEmpty()) {
                Toast.makeText(this, "请输入端口号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val framework = when (radioGroup.checkedRadioButtonId) {
                R.id.radioHermes -> "hermes"
                R.id.radioOpenclaw -> "openclaw"
                R.id.radioCustom -> "custom"
                else -> ""
            }
            if (framework.isEmpty()) {
                Toast.makeText(this, "请选择框架", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("framework", framework)
                .putString("port", port)
                .apply()

            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
