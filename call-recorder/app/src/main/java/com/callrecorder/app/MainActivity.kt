package com.callrecorder.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

class MainActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: RecordingsAdapter
    private lateinit var recordFab: ExtendedFloatingActionButton
    private lateinit var autoSwitch: MaterialSwitch
    private var player: MediaPlayer? = null

    private val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_PHONE_STATE)
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

        autoSwitch.isChecked = Prefs.autoRecord(this)
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
        reload()
        refreshButtons()
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
                requestPermissions()
            }
            .setNegativeButton(R.string.consent_exit) { _, _ -> finish() }
            .show()
    }

    private fun hasCorePermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
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
            recordFab.postDelayed({ reload(); refreshButtons() }, 400)
        } else {
            if (!hasCorePermissions()) {
                requestPermissions()
                return
            }
            CallRecorderService.start(this, null)
            Toast.makeText(this, R.string.recording, Toast.LENGTH_SHORT).show()
            refreshButtons()
        }
    }

    private fun refreshButtons() {
        if (CallRecorderService.isRecording) {
            recordFab.setText(R.string.stop_recording)
            recordFab.setIconResource(R.drawable.ic_stop)
        } else {
            recordFab.setText(R.string.record_now)
            recordFab.setIconResource(R.drawable.ic_mic)
        }
    }

    private fun reload() {
        val items = Storage.list(this)
        adapter.submit(items)
        empty.visibility = if (items.isEmpty()) TextView.VISIBLE else TextView.GONE
    }

    private fun play(r: Recording) {
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(r.file.absolutePath)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            Toast.makeText(this, R.string.playing, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.play_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        player?.run { runCatching { if (isPlaying) stop(); release() } }
        player = null
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
            .setMessage(r.title)
            .setPositiveButton(R.string.delete) { _, _ ->
                r.file.delete()
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
