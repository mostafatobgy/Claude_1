package com.callrecorder.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: RecordingsAdapter
    private lateinit var recordFab: ExtendedFloatingActionButton
    private lateinit var autoSwitch: MaterialSwitch
    private var player: MediaPlayer? = null
    private var playingFile: File? = null

    /** The service reports start success/failure/stop here so the UI reflects the real state. */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            setRecordingUi(intent.getBooleanExtra(CallRecorderService.EXTRA_RECORDING, false))
            intent.getStringExtra(CallRecorderService.EXTRA_ERROR)?.let {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
            }
            reload()
        }
    }

    private val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] != true ||
                result[Manifest.permission.READ_PHONE_STATE] != true
            ) {
                Toast.makeText(this, R.string.need_permissions, Toast.LENGTH_LONG).show()
            }
            reload()
            refreshButtons()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        recordFab = findViewById(R.id.recordFab)
        autoSwitch = findViewById(R.id.autoSwitch)

        adapter = RecordingsAdapter(emptyList(), ::play, ::share, ::confirmDelete)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        autoSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasCorePermissions()) {
                autoSwitch.isChecked = false
                requestPermissions()
            } else {
                Prefs.setAutoRecord(this, checked)
            }
        }

        recordFab.setOnClickListener { toggleManualRecording() }

        if (!Prefs.consentAccepted(this)) showConsentDialog() else requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        autoSwitch.isChecked = Prefs.autoRecord(this)
        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(CallRecorderService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        reload()
        refreshButtons()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(stateReceiver) }
    }

    override fun onStop() {
        super.onStop()
        stopPlayback()
    }

    /** First-run screen: the user must accept that they are responsible for recording lawfully. */
    private fun showConsentDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.consent_message)
            .setCancelable(false)
            .setPositiveButton(R.string.consent_agree) { _, _ ->
                Prefs.setConsentAccepted(this)
                // The whole point of the app is to capture calls, so auto-record starts on.
                Prefs.setAutoRecord(this, true)
                autoSwitch.isChecked = true
                requestPermissions()
            }
            .setNegativeButton(R.string.consent_exit) { _, _ -> finish() }
            .show()
    }

    private fun hasCorePermissions(): Boolean =
        hasMicPermission() &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val missing = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) permissionLauncher.launch(permissions) else refreshButtons()
    }

    private fun toggleManualRecording() {
        if (CallRecorderService.isRecording) {
            CallRecorderService.stop(this)
            Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()
        } else {
            // Manual recording only needs the microphone, not the phone-state permission.
            if (!hasMicPermission()) {
                Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
                requestPermissions()
                return
            }
            CallRecorderService.start(this, null)
            Toast.makeText(this, R.string.recording, Toast.LENGTH_SHORT).show()
            // Reflect the "recording" state immediately so a second tap stops it, rather than
            // starting again — the service confirms/corrects this via its state broadcast.
            setRecordingUi(true)
        }
    }

    private fun refreshButtons() = setRecordingUi(CallRecorderService.isRecording)

    private fun setRecordingUi(recording: Boolean) {
        if (recording) {
            recordFab.setText(R.string.stop_recording)
            recordFab.setIconResource(R.drawable.ic_stop)
        } else {
            recordFab.setText(R.string.record_now)
            recordFab.setIconResource(R.drawable.ic_mic)
        }
    }

    private fun reload() {
        val unknown = getString(R.string.unknown_number)
        val items = Storage.list(this).map { rec ->
            val number = rec.number
            val title = Contacts.nameFor(this, number) ?: number ?: unknown
            RecordingItem(rec, title, "${rec.whenText}  ·  ${formatSize(rec.sizeBytes)}")
        }
        adapter.submit(items)
        empty.visibility = if (items.isEmpty()) TextView.VISIBLE else TextView.GONE
    }

    /** Tap to play; tap the same row again to stop. Falls back to an external player on error. */
    private fun play(r: Recording) {
        if (playingFile == r.file) { // same row tapped again -> toggle off
            stopPlayback()
            return
        }
        stopPlayback()

        if (!r.file.exists() || r.file.length() == 0L) {
            Toast.makeText(this, R.string.empty_recording, Toast.LENGTH_LONG).show()
            return
        }

        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(r.file.absolutePath)
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, _, _ ->
                    stopPlayback()
                    openExternally(r) // let a full media player try the file
                    true
                }
                prepare()
                start()
            }
            playingFile = r.file
            Toast.makeText(this, R.string.playing, Toast.LENGTH_SHORT).show()
            refreshButtons()
        } catch (_: Exception) {
            stopPlayback()
            openExternally(r)
        }
    }

    private fun stopPlayback() {
        player?.run { runCatching { if (isPlaying) stop(); release() } }
        player = null
        playingFile = null
    }

    /** Open the file in whatever audio player the phone has, via a shareable content URI. */
    private fun openExternally(r: Recording) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", r.file)
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "audio/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.play_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(r: Recording) {
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", r.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun confirmDelete(r: Recording) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(Contacts.nameFor(this, r.number) ?: r.number ?: getString(R.string.unknown_number))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (playingFile == r.file) stopPlayback()
                r.file.delete()
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
