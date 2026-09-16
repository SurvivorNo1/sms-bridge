package io.github.survivorno1.smsbridge

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var sw: Switch
    private lateinit var dot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvSecret: TextView
    private lateinit var tvAddr: TextView
    private lateinit var tvTest: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var syncing = false

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sw = findViewById(R.id.sw)
        dot = findViewById(R.id.dot)
        tvStatus = findViewById(R.id.tvStatus)
        tvHint = findViewById(R.id.tvHint)
        tvSecret = findViewById(R.id.tvSecret)
        tvAddr = findViewById(R.id.tvAddr)
        tvTest = findViewById(R.id.tvTest)
        Capture.init(this)
        findViewById<TextView>(R.id.tvVersion).text =
            "v${Doc.VERSION} · MIT · 源码 github.com/SurvivorNo1/sms-bridge"

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
            ui.postDelayed({ render() }, 400)
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("secret", Prefs.secret(this)))
            Toast.makeText(this, "密钥已复制", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnRegen).setOnClickListener {
            Prefs.regenerate(this)
            Toast.makeText(this, "已换新密钥，旧的立即作废", Toast.LENGTH_SHORT).show()
            render()
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { selfTest() }

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
        val running = BridgeService.running
        syncing = true
        sw.isChecked = running
        syncing = false

        (dot.background.mutate() as GradientDrawable)
            .setColor(getColor(if (running) R.color.ok else R.color.off))
        tvStatus.text = if (running) "运行中" else "已停止"

        val err = BridgeService.lastError
        tvHint.text = when {
            err != null -> "⚠ $err"
            !hasSmsPermission() -> "⚠ 未授予「读取 / 接收短信」权限，打开开关前请先允许。"
            else -> "开着时通知栏常驻；只对同一 WiFi / 热点内、持有密钥的设备开放。"
        }
        tvHint.setTextColor(getColor(if (err != null || !hasSmsPermission()) R.color.warn else R.color.text2))

        tvSecret.text = Prefs.secret(this)
        val ips = HttpServer.lanIps()
        tvAddr.text = if (ips.isEmpty()) "未连接 WiFi / 热点（服务只在 WiFi 网卡上生效）"
        else ips.joinToString("\n") { "http://$it:${Prefs.port(this)}" }
    }

    private fun selfTest() {
        if (!hasSmsPermission()) { requestPerms(); return }
        val now = System.currentTimeMillis()
        val list = try {
            InboxSource(contentResolver).range(now - 24 * 3600_000, now, 5)
        } catch (e: Exception) {
            tvTest.text = "读取失败：${e.message}"
            tvTest.setTextColor(getColor(R.color.warn))
            return
        }
        tvTest.setTextColor(getColor(R.color.text))
        if (list.isEmpty()) {
            tvTest.text = "权限正常，最近 24 小时没有短信。"
            return
        }
        tvTest.text = list.joinToString("\n") {
            val body = it.body.replace('\n', ' ')
            "${dfmt.format(Date(it.ts))}  ${it.from}  ${if (body.length > 24) body.take(24) + "…" else body}"
        }
    }

    private fun hasSmsPermission() =
        checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val need = ArrayList<String>()
        if (!hasSmsPermission()) { need.add(Manifest.permission.READ_SMS); need.add(Manifest.permission.RECEIVE_SMS) }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }
}
