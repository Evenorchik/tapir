package com.sleeptalk.app

import android.Manifest
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
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.sleeptalk.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var adapter: SessionAdapter
    private var player: MediaPlayer? = null

    private val savedReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = refreshList()
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            startRecording()
        } else {
            Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateLiveUi()
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = SessionAdapter(
            onPlay = { playSession(it) },
            onShare = { shareSession(it) },
            onDelete = { confirmDelete(it) }
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        val savedSens = prefs().getInt("sensitivity", 50)
        b.sensitivity.value = savedSens.toFloat()
        b.sensitivityLabel.text = getString(R.string.sensitivity_fmt, savedSens)
        b.sensitivity.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            b.sensitivityLabel.text = getString(R.string.sensitivity_fmt, v)
            prefs().edit().putInt("sensitivity", v).apply()
            if (RecorderState.isRecording) {
                startService(
                    Intent(this, RecorderService::class.java)
                        .setAction(RecorderService.ACTION_SET_SENSITIVITY)
                        .putExtra(RecorderService.EXTRA_SENSITIVITY, v)
                )
            }
        }

        b.toggle.setOnClickListener { onToggle() }
        b.battery.setOnClickListener { requestIgnoreBattery() }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, savedReceiver, IntentFilter(RecorderService.BROADCAST_SAVED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handler.post(ticker)
        refreshList()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        try {
            unregisterReceiver(savedReceiver)
        } catch (_: Exception) {
        }
    }

    private fun onToggle() {
        if (RecorderState.isRecording) {
            startService(
                Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
            )
        } else {
            if (!hasMicPermission()) {
                val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                permLauncher.launch(perms.toTypedArray())
                return
            }
            startRecording()
        }
    }

    private fun startRecording() {
        val i = Intent(this, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START)
            .putExtra(RecorderService.EXTRA_SENSITIVITY, b.sensitivity.value.toInt())
        ContextCompat.startForegroundService(this, i)
    }

    private fun updateLiveUi() {
        val rec = RecorderState.isRecording
        b.toggle.setText(if (rec) R.string.stop else R.string.start)
        if (rec) {
            val elapsed = SystemClock.elapsedRealtime() - RecorderState.startElapsedMs
            b.timer.text = formatHms(elapsed)
            b.captured.text = getString(
                R.string.captured_fmt, formatHms(RecorderState.capturedMs), RecorderState.segmentCount
            )
            val level = (RecorderState.currentRms / 30.0).coerceIn(0.0, 100.0).toInt()
            b.level.progress = level
            val above = RecorderState.currentRms > RecorderState.threshold && RecorderState.threshold > 0
            b.status.setText(if (above) R.string.sound_detected else R.string.listening)
        } else {
            b.timer.text = "00:00:00"
            b.level.progress = 0
            b.captured.setText(R.string.idle)
            b.status.setText(R.string.idle_status)
        }
    }

    private fun refreshList() {
        val sessions = SessionStore.list(this)
        adapter.submit(sessions)
        b.empty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun playSession(s: Session) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(s.file.absolutePath)
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepare()
                start()
            }
            Toast.makeText(this, R.string.playing, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.play_error, e.message), Toast.LENGTH_LONG).show()
        }
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
            .setMessage(s.file.name)
            .setPositiveButton(R.string.delete) { _, _ ->
                SessionStore.delete(s)
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, R.string.battery_already, Toast.LENGTH_SHORT).show()
            return
        }
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

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun prefs() = getSharedPreferences("sleeptalk", Context.MODE_PRIVATE)

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
