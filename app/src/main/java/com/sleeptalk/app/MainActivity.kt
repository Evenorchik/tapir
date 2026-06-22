package com.sleeptalk.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.sleeptalk.app.databinding.ActivityMainBinding
import com.sleeptalk.app.databinding.ItemMomentBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Screen { IDLE, RECORDING, ANALYZING, CALIBRATE, RESULT }
    private enum class CalState { PROMPT, RUNNING, DONE }

    private lateinit var b: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val ru = Locale("ru")

    private var screen = Screen.IDLE
    private val anims = mutableListOf<ValueAnimator>()
    private val liveBars = ArrayDeque<Int>()

    private var recordingSessionStart = 0L
    private var finalizePending = false

    private var calibrator: Calibrator? = null
    private var calState = CalState.PROMPT

    private var player: MediaPlayer? = null
    private var playerCompleted = false
    private var currentSession: Session? = null
    private var currentPlayer = IntArray(0)

    private var pendingAfterPermission: (() -> Unit)? = null

    private val savedReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (finalizePending) handler.postDelayed({ finalizeAnalyzing() }, 700)
            else if (screen == Screen.IDLE) populateIdle()
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            pendingAfterPermission?.invoke()
        } else {
            Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
        }
        pendingAfterPermission = null
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.idleOrb.setOnClickListener { requestMic { startRecording() } }
        b.idleCalibrate.setOnClickListener { openCalibration() }
        b.idleLast.setOnClickListener { currentIdleSession?.let { showResult(it) } }

        b.recStop.setOnClickListener { stopRecording() }

        b.calClose.setOnClickListener { closeCalibration() }
        b.calAction.setOnClickListener { onCalAction() }

        b.resClose.setOnClickListener { goIdle() }
        b.resPlay.setOnClickListener { togglePlay() }
        b.resAgain.setOnClickListener { restartPlay() }
        b.resShare.setOnClickListener { currentSession?.let { shareSession(it) } }
        b.resDelete.setOnClickListener { currentSession?.let { confirmDelete(it) } }

        b.resPlayerWave.setPlayedColors(0xFFf3b074.toInt(), 0x47f0a868)
        b.idleSpark.setBarColor(0x80f0a868.toInt())
        b.recWave.setBarColor(0xFFf0a868.toInt())
        b.calWave.setBarColor(0xFFf6c89a.toInt())

        show(Screen.IDLE)
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, savedReceiver, IntentFilter(RecorderService.BROADCAST_SAVED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (RecorderState.isRecording && screen == Screen.IDLE) {
            recordingSessionStart = RecorderState.startWallMs
            show(Screen.RECORDING)
        } else if (screen == Screen.IDLE) {
            populateIdle()
        }
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        try {
            unregisterReceiver(savedReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        calibrator?.cancel()
    }

    // ---------- screen switching ----------

    private fun show(s: Screen) {
        screen = s
        b.idleScreen.visibility = if (s == Screen.IDLE) View.VISIBLE else View.GONE
        b.recScreen.visibility = if (s == Screen.RECORDING) View.VISIBLE else View.GONE
        b.analScreen.visibility = if (s == Screen.ANALYZING) View.VISIBLE else View.GONE
        b.calScreen.visibility = if (s == Screen.CALIBRATE) View.VISIBLE else View.GONE
        b.resScreen.visibility = if (s == Screen.RESULT) View.VISIBLE else View.GONE

        window.statusBarColor = ContextCompat.getColor(
            this, when (s) {
                Screen.RECORDING -> R.color.bg_dark2
                Screen.RESULT -> R.color.bg_result_top
                else -> R.color.bg_idle
            }
        )

        clearAnims()
        when (s) {
            Screen.IDLE -> startBreathing()
            Screen.RECORDING -> startRecordingAnims()
            else -> {}
        }
    }

    private fun goIdle() {
        releasePlayer()
        populateIdle()
        show(Screen.IDLE)
    }

    // ---------- recording ----------

    private fun startRecording() {
        promptBatteryIfNeeded()
        recordingSessionStart = System.currentTimeMillis()
        liveBars.clear()
        val sens = prefs().getInt("sensitivity", 50)
        val i = Intent(this, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START)
            .putExtra(RecorderService.EXTRA_SENSITIVITY, sens)
        ContextCompat.startForegroundService(this, i)
        // RecorderState flips on the service thread; reflect immediately.
        RecorderState.startWallMs = recordingSessionStart
        show(Screen.RECORDING)
    }

    private fun stopRecording() {
        startService(
            Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
        )
        onRecordingEnded()
    }

    private fun onRecordingEnded() {
        if (finalizePending) return
        finalizePending = true
        show(Screen.ANALYZING)
        handler.postDelayed({ finalizeAnalyzing() }, 5000)
    }

    private fun finalizeAnalyzing() {
        if (!finalizePending) return
        finalizePending = false
        val latest = SessionStore.latest(this)
        if (latest != null && latest.dateMs >= recordingSessionStart - 1500) {
            showResult(latest)
        } else {
            Toast.makeText(this, R.string.silent_night, Toast.LENGTH_LONG).show()
            goIdle()
        }
    }

    private fun tick() {
        when (screen) {
            Screen.RECORDING -> {
                if (RecorderState.isRecording) updateRecordingUi() else onRecordingEnded()
            }
            Screen.RESULT -> updatePlaybackUi()
            else -> {}
        }
    }

    private fun updateRecordingUi() {
        val elapsed = SystemClock.elapsedRealtime() - RecorderState.startElapsedMs
        b.recTimer.text = fmtTimer(elapsed)
        b.recStarted.text = "начало в " + fmtClock(RecorderState.startWallMs)
        val n = RecorderState.segmentCount
        b.recFound.text = if (n == 0) "пока тихо"
        else "пока найдено " + n + " " + plural(n, "момент", "момента", "моментов")

        liveBars.addLast(RecorderState.currentLevel)
        while (liveBars.size > 40) liveBars.removeFirst()
        b.recWave.setBars(liveBars.toIntArray())
    }

    // ---------- calibration ----------

    private fun openCalibration() {
        calState = CalState.PROMPT
        renderCal()
        show(Screen.CALIBRATE)
    }

    private fun closeCalibration() {
        calibrator?.cancel()
        calibrator = null
        goIdle()
    }

    private fun onCalAction() {
        when (calState) {
            CalState.PROMPT -> requestMic { startCalibration() }
            CalState.RUNNING -> { calibrator?.cancel(); calibrator = null; calState = CalState.PROMPT; renderCal() }
            CalState.DONE -> closeCalibration()
        }
    }

    private fun startCalibration() {
        calState = CalState.RUNNING
        renderCal()
        calibrator = Calibrator(
            handler,
            onPhase = { phase, left ->
                b.calPhase.text = if (phase == Calibrator.Phase.AMBIENT) "Тишина…" else "Шепчите…"
                b.calHint.text = if (phase == Calibrator.Phase.AMBIENT)
                    "Не шумите — измеряю фон комнаты" else "Шепчите так, как могли бы во сне"
                b.calCountdown.text = left.toString()
            },
            onLevel = { level ->
                liveBars.addLast(level)
                while (liveBars.size > 40) liveBars.removeFirst()
                b.calWave.setBars(liveBars.toIntArray())
            },
            onDone = { res ->
                prefs().edit().putFloat("calThreshold", res.thresholdRms.toFloat()).apply()
                calState = CalState.DONE
                renderCal()
                b.calResultText.text =
                    "Шёпот измерен. Буду реагировать на звуки примерно на 40% тише вашего шёпота — " +
                            "с запасом, чтобы не пропустить тихое бормотание."
            },
            onError = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                calState = CalState.PROMPT
                renderCal()
            }
        )
        liveBars.clear()
        calibrator?.start()
    }

    private fun renderCal() {
        when (calState) {
            CalState.PROMPT -> {
                b.calPhase.text = "Тест шёпотом"
                b.calHint.text = "Держите телефон в 50 см от лица. Я измерю тишину в комнате, " +
                        "затем ваш шёпот — и подберу чувствительность с запасом."
                b.calCountdown.visibility = View.GONE
                b.calWave.visibility = View.GONE
                b.calResult.visibility = View.GONE
                b.calAction.text = "Начать тест"
            }
            CalState.RUNNING -> {
                b.calCountdown.visibility = View.VISIBLE
                b.calWave.visibility = View.VISIBLE
                b.calResult.visibility = View.GONE
                b.calAction.text = "Отмена"
            }
            CalState.DONE -> {
                b.calPhase.text = "Готово"
                b.calHint.text = "Чувствительность подстроена под вашу комнату."
                b.calCountdown.visibility = View.GONE
                b.calWave.visibility = View.GONE
                b.calResult.visibility = View.VISIBLE
                b.calAction.text = "Готово"
            }
        }
    }

    // ---------- result ----------

    private var currentIdleSession: Session? = null

    private fun populateIdle() {
        val latest = SessionStore.latest(this)
        currentIdleSession = latest
        if (latest == null) {
            b.idleLast.visibility = View.GONE
            return
        }
        b.idleLast.visibility = View.VISIBLE
        b.idleSpark.setBars(latest.player)
        b.idleLastDur.text = fmtClockDur(latest.soundMs)
        b.idleLastCount.text = latest.count.toString() + " " +
                plural(latest.count, "момент", "момента", "моментов")
        b.idleLastDate.text = fmtDate(latest.dateMs)
    }

    private fun showResult(s: Session) {
        currentSession = s
        currentPlayer = s.player
        releasePlayer()

        val n = s.count
        val title = "Этой ночью ты звучал $n " + plural(n, "раз", "раза", "раз")
        val span = SpannableString(title)
        val numStart = title.indexOf(n.toString(), 20.coerceAtMost(title.length - 1))
        if (numStart >= 0) {
            val end = numStart + n.toString().length
            span.setSpan(ForegroundColorSpan(0xFFf3b074.toInt()), numStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(StyleSpan(Typeface.ITALIC), numStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        b.resTitle.text = span

        b.resSound.text = fmtClockDur(s.soundMs)
        b.resSleep.text = fmtHm(s.sleepMs)
        b.resLoudest.text = if (s.loudestMs > 0) fmtClock(s.loudestMs) else "—"

        b.resPlayerTotal.text = fmtClockDur(s.soundMs)
        b.resPlayerWave.setBars(s.player, 0f)
        b.resPlayerPos.text = "0:00"
        b.resPlayerLeft.text = "осталось " + fmtClockDur(s.soundMs)
        b.resPlayIcon.text = "▶"

        b.resMoments.removeAllViews()
        for (m in s.moments) {
            val row = ItemMomentBinding.inflate(layoutInflater, b.resMoments, false)
            row.momentTime.text = fmtClock(m.startMs)
            row.momentDur.text = fmtSecs(m.durMs)
            row.momentWave.setBarColor(0x8cf0a868.toInt())
            row.momentWave.setBars(m.w)
            b.resMoments.addView(row.root)
        }

        show(Screen.RESULT)
    }

    private fun togglePlay() {
        val s = currentSession ?: return
        val p = player
        if (p != null) {
            if (p.isPlaying) {
                p.pause()
                b.resPlayIcon.text = "▶"
            } else {
                if (playerCompleted) { p.seekTo(0); playerCompleted = false }
                p.start()
                b.resPlayIcon.text = "❚❚"
            }
            return
        }
        try {
            player = MediaPlayer().apply {
                setDataSource(s.file.absolutePath)
                setOnCompletionListener { onPlayComplete() }
                prepare()
                start()
            }
            playerCompleted = false
            b.resPlayIcon.text = "❚❚"
            Toast.makeText(this, R.string.playing, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось воспроизвести", Toast.LENGTH_LONG).show()
        }
    }

    private fun restartPlay() {
        releasePlayer()
        togglePlay()
    }

    private fun onPlayComplete() {
        playerCompleted = true
        b.resPlayIcon.text = "▶"
        b.resPlayerWave.setBars(currentPlayer, 1f)
        b.resPlayerPos.text = fmtClockDur(currentSession?.soundMs ?: 0)
        b.resPlayerLeft.text = "осталось 0:00"
    }

    private fun updatePlaybackUi() {
        val p = player ?: return
        if (!p.isPlaying) return
        val dur = p.duration
        val pos = p.currentPosition
        if (dur <= 0) return
        b.resPlayerWave.setBars(currentPlayer, pos.toFloat() / dur)
        b.resPlayerPos.text = fmtClockDur(pos.toLong())
        b.resPlayerLeft.text = "осталось " + fmtClockDur((dur - pos).toLong())
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
                releasePlayer()
                SessionStore.delete(s)
                goIdle()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- permissions / battery ----------

    private fun requestMic(then: () -> Unit) {
        if (hasMic()) { then(); return }
        pendingAfterPermission = then
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

    // ---------- animations ----------

    private fun clearAnims() {
        for (a in anims) a.cancel()
        anims.clear()
        b.idleGlow.scaleX = 1f; b.idleGlow.scaleY = 1f; b.idleGlow.alpha = 1f
        b.recDot.alpha = 1f
        b.recRipple1.scaleX = 1f; b.recRipple1.scaleY = 1f; b.recRipple1.alpha = 1f
        b.recRipple2.scaleX = 1f; b.recRipple2.scaleY = 1f; b.recRipple2.alpha = 1f
    }

    private fun startBreathing() {
        val a = ObjectAnimator.ofPropertyValuesHolder(
            b.idleGlow,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.13f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.13f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.72f, 1f)
        )
        a.duration = 5500
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        a.start()
        anims.add(a)
    }

    private fun startRecordingAnims() {
        val blink = ObjectAnimator.ofFloat(b.recDot, View.ALPHA, 1f, 0.15f)
        blink.duration = 1900
        blink.repeatCount = ValueAnimator.INFINITE
        blink.repeatMode = ValueAnimator.REVERSE
        blink.start()
        anims.add(blink)
        ripple(b.recRipple1, 0)
        ripple(b.recRipple2, 1900)
    }

    private fun ripple(view: View, delay: Long) {
        view.scaleX = 0.45f; view.scaleY = 0.45f
        val a = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.45f, 1.35f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.45f, 1.35f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0f)
        )
        a.duration = 3800
        a.startDelay = delay
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.RESTART
        a.start()
        anims.add(a)
    }

    // ---------- formatting ----------

    private fun prefs() = getSharedPreferences("unsleep", Context.MODE_PRIVATE)

    private fun fmtTimer(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun fmtClockDur(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun fmtHm(ms: Long): String {
        val m = ms / 60000
        return String.format(Locale.US, "%d:%02d", m / 60, m % 60)
    }

    private fun fmtClock(ms: Long): String =
        SimpleDateFormat("HH:mm", ru).format(Date(ms))

    private fun fmtDate(ms: Long): String =
        SimpleDateFormat("d MMM", ru).format(Date(ms))

    private fun fmtSecs(ms: Long): String {
        val s = ms / 1000
        return if (s < 60) "$s сек" else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val n10 = n % 10
        val n100 = n % 100
        return when {
            n10 == 1 && n100 != 11 -> one
            n10 in 2..4 && n100 !in 12..14 -> few
            else -> many
        }
    }
}
