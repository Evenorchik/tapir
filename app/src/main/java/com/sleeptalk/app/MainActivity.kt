package com.sleeptalk.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hosts the WebView that renders the Unsleep design (assets/ui/index.html) and
 * bridges it to the native recorder, calibrator, file store and audio player.
 *  - JS calls native through the `Native` interface.
 *  - Native pushes state into JS via `UI.*` calls (evaluateJavascript).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val ru = Locale("ru")

    private var mode = "idle"
    private var calibrator: Calibrator? = null
    private var lastCalLevel = 0

    private var player: MediaPlayer? = null
    private var playerCompleted = false
    private var currentSession: Session? = null

    private var finalizePending = false
    private var recordingSessionStart = 0L
    private var pendingPerm: (() -> Unit)? = null

    private val savedReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (finalizePending) handler.postDelayed({ finalizeAnalyzing() }, 700)
            else if (mode == "idle") pushLast()
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) pendingPerm?.invoke()
        else toast(getString(R.string.need_mic))
        pendingPerm = null
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 100)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(0xFF1b1411.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Native")
        setContentView(web)
        web.loadUrl("file:///android_asset/ui/index.html")
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, savedReceiver, IntentFilter(RecorderService.BROADCAST_SAVED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        web.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        web.onPause()
        try {
            unregisterReceiver(savedReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        calibrator?.cancel()
        web.destroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (mode) {
            "result", "calibrate" -> goIdle()
            "recording", "analyzing" -> moveTaskToBack(true)
            else -> super.onBackPressed()
        }
    }

    // ---------- JS bridge ----------

    inner class Bridge {
        @JavascriptInterface fun ready() = runOnUiThread { onReady() }
        @JavascriptInterface fun start() = runOnUiThread { ensureMic { startRecording() } }
        @JavascriptInterface fun stop() = runOnUiThread { stopRecording() }
        @JavascriptInterface fun calStart() = runOnUiThread { ensureMic { startCalibration() } }
        @JavascriptInterface fun calCancel() = runOnUiThread {
            calibrator?.cancel(); calibrator = null; mode = "calibrate"
        }
        @JavascriptInterface fun openLatest() = runOnUiThread {
            SessionStore.latest(this@MainActivity)?.let { showResult(it) }
        }
        @JavascriptInterface fun togglePlay() = runOnUiThread { this@MainActivity.togglePlay() }
        @JavascriptInterface fun again() = runOnUiThread { restartPlay() }
        @JavascriptInterface fun share() = runOnUiThread { currentSession?.let { shareSession(it) } }
        @JavascriptInterface fun del() = runOnUiThread { currentSession?.let { confirmDelete(it) } }
        @JavascriptInterface fun goIdle() = runOnUiThread { this@MainActivity.goIdle() }
    }

    private fun onReady() {
        if (RecorderState.isRecording) {
            mode = "recording"
            recordingSessionStart = RecorderState.startWallMs
            jsShow("rec")
        } else {
            mode = "idle"
            pushLast()
            jsShow("idle")
        }
    }

    // ---------- recording ----------

    private fun startRecording() {
        promptBatteryIfNeeded()
        recordingSessionStart = System.currentTimeMillis()
        val sens = prefs().getInt("sensitivity", 50)
        ContextCompat.startForegroundService(
            this, Intent(this, RecorderService::class.java)
                .setAction(RecorderService.ACTION_START)
                .putExtra(RecorderService.EXTRA_SENSITIVITY, sens)
        )
        RecorderState.startWallMs = recordingSessionStart
        mode = "recording"
        jsShow("rec")
    }

    private fun stopRecording() {
        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        onRecordingEnded()
    }

    private fun onRecordingEnded() {
        if (finalizePending) return
        finalizePending = true
        mode = "analyzing"
        jsShow("anal")
        handler.postDelayed({ finalizeAnalyzing() }, 5000)
    }

    private fun finalizeAnalyzing() {
        if (!finalizePending) return
        finalizePending = false
        val latest = SessionStore.latest(this)
        if (latest != null && latest.dateMs >= recordingSessionStart - 1500) showResult(latest)
        else {
            toast(getString(R.string.silent_night))
            goIdle()
        }
    }

    private fun tick() {
        when (mode) {
            "recording" -> if (RecorderState.isRecording) pushTick() else onRecordingEnded()
            "result" -> if (player?.isPlaying == true) pushPlay()
        }
    }

    private fun pushTick() {
        val elapsed = SystemClock.elapsedRealtime() - RecorderState.startElapsedMs
        val n = RecorderState.segmentCount
        val found = if (n == 0) "пока тихо"
        else "пока найдено $n " + plural(n, "момент", "момента", "моментов")
        val j = JSONObject()
        j.put("timer", fmtTimer(elapsed))
        j.put("started", "начало в " + fmtClock(RecorderState.startWallMs))
        j.put("found", found)
        j.put("level", RecorderState.currentLevel)
        callJs("UI.tick", j)
    }

    // ---------- calibration ----------

    private fun startCalibration() {
        mode = "calibrate"
        lastCalLevel = 0
        calibrator = Calibrator(
            handler,
            onPhase = { phase, left ->
                val j = JSONObject()
                j.put("state", "running")
                j.put("phase", if (phase == Calibrator.Phase.AMBIENT) "Тишина…" else "Шепчите…")
                j.put("hint", if (phase == Calibrator.Phase.AMBIENT)
                    "Не шумите — измеряю фон комнаты" else "Шепчите так, как могли бы во сне")
                j.put("left", left)
                j.put("level", lastCalLevel)
                callJs("UI.cal", j)
            },
            onLevel = { level -> lastCalLevel = level },
            onDone = { res ->
                prefs().edit().putFloat("calThreshold", res.thresholdRms.toFloat()).apply()
                val j = JSONObject()
                j.put("state", "done")
                j.put("text", "Шёпот измерен. Буду реагировать на звуки примерно на 40% тише вашего " +
                        "шёпота — с запасом, чтобы не пропустить тихое бормотание.")
                callJs("UI.cal", j)
            },
            onError = { msg ->
                toast(msg)
                val j = JSONObject(); j.put("state", "prompt"); callJs("UI.cal", j)
            }
        )
        calibrator?.start()
    }

    // ---------- result / playback ----------

    private fun showResult(s: Session) {
        currentSession = s
        releasePlayer()
        mode = "result"
        callJs("UI.result", sessionToJson(s))
    }

    private fun goIdle() {
        releasePlayer()
        mode = "idle"
        pushLast()
        jsShow("idle")
    }

    private fun togglePlay() {
        val s = currentSession ?: return
        val p = player
        if (p != null) {
            if (p.isPlaying) { p.pause(); pushPlay(false) }
            else {
                if (playerCompleted) { p.seekTo(0); playerCompleted = false }
                p.start(); pushPlay(true)
            }
            return
        }
        try {
            player = MediaPlayer().apply {
                setDataSource(s.file.absolutePath)
                setOnCompletionListener { onPlayComplete() }
                prepare(); start()
            }
            playerCompleted = false
            pushPlay(true)
        } catch (e: Exception) {
            toast("Не удалось воспроизвести")
        }
    }

    private fun restartPlay() {
        releasePlayer()
        togglePlay()
    }

    private fun onPlayComplete() {
        playerCompleted = true
        val total = currentSession?.soundMs ?: 0
        val j = JSONObject()
        j.put("playing", false); j.put("pos", fmtClockDur(total)); j.put("left", "0:00"); j.put("frac", 1.0)
        callJs("UI.play", j)
    }

    private fun pushPlay(playingOverride: Boolean? = null) {
        val p = player ?: return
        val dur = p.duration.coerceAtLeast(1)
        val pos = p.currentPosition.coerceIn(0, dur)
        val j = JSONObject()
        j.put("playing", playingOverride ?: p.isPlaying)
        j.put("pos", fmtClockDur(pos.toLong()))
        j.put("left", fmtClockDur((dur - pos).toLong()))
        j.put("frac", pos.toDouble() / dur)
        callJs("UI.play", j)
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        playerCompleted = false
    }

    private fun shareSession(s: Session) {
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", s.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_via)))
    }

    private fun confirmDelete(s: Session) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_q)
            .setPositiveButton(R.string.delete) { _, _ ->
                releasePlayer(); SessionStore.delete(s); goIdle()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- pushes to JS ----------

    private fun pushLast() {
        val latest = SessionStore.latest(this)
        if (latest == null) {
            web.evaluateJavascript("UI.last('null')", null)
            return
        }
        val j = JSONObject()
        j.put("player", intArray(latest.player))
        j.put("dur", fmtClockDur(latest.soundMs))
        j.put("count", latest.count.toString() + " " +
                plural(latest.count, "момент", "момента", "моментов"))
        j.put("date", fmtDate(latest.dateMs))
        callJs("UI.last", j)
    }

    private fun sessionToJson(s: Session): JSONObject {
        val n = s.count
        val j = JSONObject()
        j.put("titlePre", "Этой ночью ты звучал ")
        j.put("titleNum", n.toString())
        j.put("titlePost", " " + plural(n, "раз", "раза", "раз"))
        j.put("sound", fmtClockDur(s.soundMs))
        j.put("sleep", fmtHm(s.sleepMs))
        j.put("loud", if (s.loudestMs > 0) fmtClock(s.loudestMs) else "—")
        j.put("total", fmtClockDur(s.soundMs))
        j.put("player", intArray(s.player))
        val arr = JSONArray()
        for (m in s.moments) {
            val o = JSONObject()
            o.put("t", fmtClock(m.startMs))
            o.put("d", fmtSecs(m.durMs))
            o.put("w", intArray(m.w))
            arr.put(o)
        }
        j.put("moments", arr)
        return j
    }

    private fun jsShow(name: String) {
        mode = when (name) {
            "rec" -> "recording"; "anal" -> "analyzing"; "res" -> "result"
            "cal" -> "calibrate"; else -> "idle"
        }
        web.evaluateJavascript("UI.show('$name')", null)
    }

    private fun callJs(fn: String, payload: JSONObject) {
        web.evaluateJavascript("$fn(" + JSONObject.quote(payload.toString()) + ")", null)
    }

    private fun intArray(a: IntArray): JSONArray {
        val arr = JSONArray(); for (v in a) arr.put(v); return arr
    }

    // ---------- permissions / battery ----------

    private fun ensureMic(then: () -> Unit) {
        if (hasMic()) { then(); return }
        pendingPerm = then
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun promptBatteryIfNeeded() {
        if (prefs().getBoolean("askedBattery", false)) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs().edit().putBoolean("askedBattery", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Запись всю ночь")
            .setMessage("Чтобы система не остановила запись во сне, отключите оптимизацию батареи для Unsleep.")
            .setPositiveButton("Отключить") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun prefs() = getSharedPreferences("unsleep", Context.MODE_PRIVATE)

    // ---------- formatting ----------

    private fun fmtTimer(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun fmtClockDur(ms: Long): String {
        val s = ms / 1000; return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun fmtHm(ms: Long): String {
        val m = ms / 60000; return String.format(Locale.US, "%d:%02d", m / 60, m % 60)
    }

    private fun fmtClock(ms: Long): String = SimpleDateFormat("HH:mm", ru).format(Date(ms))
    private fun fmtDate(ms: Long): String = SimpleDateFormat("d MMM", ru).format(Date(ms))

    private fun fmtSecs(ms: Long): String {
        val s = ms / 1000
        return if (s < 60) "$s сек" else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val n10 = n % 10; val n100 = n % 100
        return when {
            n10 == 1 && n100 != 11 -> one
            n10 in 2..4 && n100 !in 12..14 -> few
            else -> many
        }
    }
}
