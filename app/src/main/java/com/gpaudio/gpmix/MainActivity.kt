package com.gpaudio.gpmix

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import com.gpaudio.gpmix.audio.AudioService
import com.gpaudio.gpmix.ui.MixerScreen

class MainActivity : ComponentActivity() {
    private val vm: MixerViewModel by viewModels()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMic()) startMixer() else vm.notify("Izin mikrofon ditolak. Aktifkan di Pengaturan > Aplikasi > GP Mix.")
        }

    private val padPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.onPadPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MixerScreen(
                    vm = vm,
                    onToggle = { if (vm.running) stopMixer() else if (hasMic()) startMixer() else requestPerms() },
                    onPickPad = { i -> vm.pendingPad = i; padPicker.launch(arrayOf("audio/*")) },
                )
            }
        }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(p.toTypedArray())
    }

    private fun startMixer() {
        ContextCompat.startForegroundService(this, Intent(this, AudioService::class.java))
        vm.startEngine { stopService(Intent(this, AudioService::class.java)) }
    }

    private fun stopMixer() {
        vm.stopEngine()
        stopService(Intent(this, AudioService::class.java))
    }
}
