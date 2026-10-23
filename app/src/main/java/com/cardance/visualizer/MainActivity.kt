package com.cardance.visualizer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cardance.visualizer.audio.AudioTap
import com.cardance.visualizer.dance.DanceMode
import com.cardance.visualizer.render.StageView

/**
 * Full-screen dance visualiser for Android car head units.
 *
 * Flow:
 * 1. Acquire `RECORD_AUDIO`, then tap the **mixed output** (`audioSessionId = 0`),
 *    so anything the head unit plays is visualised - including music streamed
 *    from a phone over Bluetooth.
 * 2. If that tap either fails to initialise or stays silent, fall back to
 *    **local playback**: pick a track with the system document picker, play it
 *    with [MediaPlayer] and attach the tap to that session. This path always
 *    works and is the reliable way to demo sync.
 *
 * Interaction: tap cycles the dance mode, long-press opens the track picker.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var stage: StageView
    private lateinit var tap: AudioTap
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tap = AudioTap()
        tap.onError = { msg ->
            runOnUiThread {
                Toast.makeText(this, "音频采集失败：$msg", Toast.LENGTH_LONG).show()
            }
        }

        stage = StageView(this).apply {
            audioTap = tap
            danceMode = DanceMode.RANDOM
            // 手势统一交给 GestureDetector：单击切律动模式 / 双击开关演示模式 / 长按选音乐
            val detector = GestureDetector(
                this@MainActivity,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        cycleMode()
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        toggleDemoMode()
                        return true
                    }

                    override fun onLongPress(e: MotionEvent) {
                        openTrackPicker()
                    }
                }
            )
            setOnTouchListener { v, event ->
                detector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                true
            }
            isClickable = true
        }
        setContentView(stage)
        startTap()
    }

    private fun toggleDemoMode() {
        stage.demoMode = !stage.demoMode
        Toast.makeText(
            this,
            if (stage.demoMode) "演示模式：合成节拍，无需音频" else "演示模式已关闭，回到音频跟随",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun cycleMode() {
        stage.danceMode = if (stage.danceMode == DanceMode.RANDOM) {
            DanceMode.GENERATED
        } else {
            DanceMode.RANDOM
        }
        Toast.makeText(this, "律动模式：${stage.danceMode.label}", Toast.LENGTH_SHORT).show()
    }

    private fun startTap() {
        if (!hasRecordPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_PERMISSION
            )
            return
        }
        if (!tap.start(SESSION_MIXED_OUTPUT)) {
            Toast.makeText(
                this,
                "本车机不支持混音采集，请长按选择本地音乐",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            if (!tap.start(SESSION_MIXED_OUTPUT)) {
                Toast.makeText(
                    this,
                    "本车机不支持混音采集，请长按选择本地音乐",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openTrackPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
        }
        try {
            startActivityForResult(intent, REQ_TRACK)
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Legacy onActivityResult is kept for a single picker flow.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TRACK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@MainActivity, uri)
            setOnPreparedListener { mp ->
                mp.start()
                // Attach to this player's session: guaranteed to receive its audio.
                tap.start(mp.audioSessionId)
            }
            prepareAsync()
        }
        stage.sourceLabel = "本地播放"
    }

    override fun onResume() {
        super.onResume()
        if (hasRecordPermission() && !tap.running && mediaPlayer == null) {
            tap.start(SESSION_MIXED_OUTPUT)
        }
    }

    override fun onDestroy() {
        tap.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    companion object {
        private const val REQ_PERMISSION = 1001
        private const val REQ_TRACK = 1002
        /** 0 = capture the device's mixed output (Bluetooth / radio / anything). */
        private const val SESSION_MIXED_OUTPUT = 0
    }
}
