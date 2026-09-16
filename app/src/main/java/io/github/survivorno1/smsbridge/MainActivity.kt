package io.github.survivorno1.smsbridge

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var sw: Switch
    private lateinit var tvSecret: TextView
    private lateinit var tvAddr: TextView
    private lateinit var tvCount: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var syncing = false

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sw = findViewById(R.id.sw)
        tvSecret = findViewById(R.id.tvSecret)
        tvAddr = findViewById(R.id.tvAddr)
        tvCount = findViewById(R.id.tvCount)
        Store.init(this)

        sw.setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            if (on) {
                if (!hasSmsPermission()) {
                    requestPerms()
                    syncing = true; sw.isChecked = false; syncing = false
                    return@setOnCheckedChangeListener
                }
                BridgeService.start(this)
            } else {
                BridgeService.stop(this)
            }
            ui.postDelayed({ render() }, 500)
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("secret", Prefs.secret(this)))
            Toast.makeText(this, "密钥已复制", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnRegen).setOnClickListener {
            Prefs.regenerate(this)
            Toast.makeText(this, "已换新密钥，旧的立即作废", Toast.LENGTH_SHORT).show()
            render()
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            Store.clear()
            Store.init(this)
            render()
        }

        requestPerms()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    private fun render() {
        syncing = true
        sw.isChecked = BridgeService.running
        syncing = false
        tvSecret.text = Prefs.secret(this)
        val ips = HttpServer.lanIps()
        tvAddr.text = if (ips.isEmpty()) "未连接 WiFi / 热点（服务只在 WiFi 网卡上生效）"
        else ips.joinToString("\n") { "http://$it:${Prefs.port(this)}" }
        tvCount.text = "缓存 ${Store.size()} 条（24 小时内，关开关即清空）" +
            if (!hasSmsPermission()) "\n⚠ 未授予「接收短信」权限" else ""
    }

    private fun hasSmsPermission() =
        checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val need = ArrayList<String>()
        if (!hasSmsPermission()) need.add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }
}
