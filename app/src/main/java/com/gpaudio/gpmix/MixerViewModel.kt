package com.gpaudio.gpmix

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gpaudio.gpmix.audio.NativeEngine
import com.gpaudio.gpmix.audio.WavLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ChannelState(
    val name: String,
    val faderDb: Float = 0f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val hpfOn: Boolean = true,
    val hpfHz: Float = 100f,
    val compOn: Boolean = true,
    val compThr: Float = -18f,
    val compRatio: Float = 3f,
    val send: Float = 0.15f,
)

class MixerViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("gpmix", Context.MODE_PRIVATE)

    val channels = mutableStateListOf(
        ChannelState("MIC"),
        ChannelState("PADS", hpfOn = false, compOn = false),
        ChannelState("MASTER", hpfOn = false, compOn = false),
    )
    val meters = mutableStateListOf(0f, 0f, 0f)
    val padNames = mutableStateListOf("", "", "", "", "", "", "", "")

    var running by mutableStateOf(false); private set
    var status by mutableStateOf("Pakai headphone supaya tidak feedback."); private set
    var latencyMs by mutableFloatStateOf(0f); private set
    var scene by mutableIntStateOf(0); private set
    var inputs by mutableStateOf(listOf(0 to "Otomatis")); private set
    var inputIndex by mutableIntStateOf(0); private set
    var pendingPad = 0

    fun notify(msg: String) { status = msg }

    // ---------- channel ----------
    fun update(ch: Int, f: (ChannelState) -> ChannelState) {
        val n = f(channels[ch])
        channels[ch] = n
        push(ch, n)
    }

    private fun push(ch: Int, s: ChannelState) = with(NativeEngine) {
        setParam(ch, P_FADER, s.faderDb)
        setParam(ch, P_PAN, s.pan)
        setParam(ch, P_MUTE, if (s.mute) 1f else 0f)
        setParam(ch, P_HPF_ON, if (s.hpfOn) 1f else 0f)
        setParam(ch, P_HPF_HZ, s.hpfHz)
        setParam(ch, P_COMP_ON, if (s.compOn) 1f else 0f)
        setParam(ch, P_COMP_THR, s.compThr)
        setParam(ch, P_COMP_RATIO, s.compRatio)
        setParam(ch, P_SEND, s.send)
    }

    // ---------- engine ----------
    private fun refreshInputs() {
        val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val list = mutableListOf(0 to "Otomatis")
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { d ->
            val label = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Mic bawaan"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB: ${d.productName}"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset: ${d.productName}"
                else -> null
            }
            if (label != null) list.add(d.id to label)
        }
        inputs = list
        if (inputIndex >= list.size) inputIndex = 0
    }

    fun nextInput() {
        refreshInputs()
        inputIndex = (inputIndex + 1) % inputs.size
        if (running) startEngine {}
    }

    fun startEngine(onFail: () -> Unit) {
        refreshInputs()
        val dev = inputs[inputIndex].first
        viewModelScope.launch(Dispatchers.Default) {
            val code = NativeEngine.start(dev)
            running = code != 1
            latencyMs = if (running) NativeEngine.latencyMs() else 0f
            status = when (code) {
                0 -> "Berjalan. Pakai headphone!"
                2 -> "Berjalan, tapi mic gagal dibuka (cek izin atau aplikasi lain yang memakai mic)."
                else -> "Gagal membuka output audio."
            }
            if (!running) withContext(Dispatchers.Main) { onFail() }
        }
    }

    fun stopEngine() {
        NativeEngine.stop()
        running = false
        status = "Berhenti."
    }

    // ---------- pads ----------
    fun onPadPicked(uri: Uri) {
        val idx = pendingPad
        try {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        viewModelScope.launch(Dispatchers.IO) {
            if (loadPadFrom(idx, uri)) prefs.edit().putString("pad_$idx", uri.toString()).apply()
        }
    }

    private fun loadPadFrom(idx: Int, uri: Uri): Boolean = try {
        val ctx = getApplication<Application>()
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("File tidak bisa dibuka")
        require(bytes.size <= 40_000_000) { "File terlalu besar (maks 40 MB)" }
        val s = WavLoader.parse(bytes)
        NativeEngine.loadPad(idx, s.data, s.channels, s.rate)
        padNames[idx] = displayName(uri)
        true
    } catch (e: Exception) {
        status = "Pad ${idx + 1}: ${e.message}"
        false
    }

    private fun displayName(uri: Uri): String = try {
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i).substringBeforeLast('.') else null
        } ?: "Sample"
    } catch (_: Exception) { "Sample" }

    fun stopPads() = NativeEngine.stopPads()

    // ---------- scene ----------
    fun saveScene(i: Int) {
        val arr = JSONArray()
        channels.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("scene_$i", arr.toString()).apply()
        scene = i
        status = "Scene ${i + 1} disimpan."
    }

    fun recallScene(i: Int) {
        scene = i
        val str = prefs.getString("scene_$i", null)
        if (str == null) { status = "Scene ${i + 1} masih kosong. Atur mixer lalu tekan Simpan."; return }
        val arr = JSONArray(str)
        for (c in 0 until minOf(3, arr.length())) update(c) { fromJson(arr.getJSONObject(c), it) }
        status = "Scene ${i + 1} dipanggil."
    }

    private fun toJson(s: ChannelState) = JSONObject()
        .put("f", s.faderDb.toDouble()).put("p", s.pan.toDouble()).put("m", s.mute)
        .put("ho", s.hpfOn).put("hz", s.hpfHz.toDouble()).put("co", s.compOn)
        .put("ct", s.compThr.toDouble()).put("cr", s.compRatio.toDouble()).put("s", s.send.toDouble())

    private fun fromJson(o: JSONObject, b: ChannelState) = b.copy(
        faderDb = o.optDouble("f", b.faderDb.toDouble()).toFloat(),
        pan = o.optDouble("p", b.pan.toDouble()).toFloat(),
        mute = o.optBoolean("m", b.mute),
        hpfOn = o.optBoolean("ho", b.hpfOn),
        hpfHz = o.optDouble("hz", b.hpfHz.toDouble()).toFloat(),
        compOn = o.optBoolean("co", b.compOn),
        compThr = o.optDouble("ct", b.compThr.toDouble()).toFloat(),
        compRatio = o.optDouble("cr", b.compRatio.toDouble()).toFloat(),
        send = o.optDouble("s", b.send.toDouble()).toFloat(),
    )

    init {
        channels.indices.forEach { push(it, channels[it]) }
        refreshInputs()
        for (i in 0 until 8) {
            prefs.getString("pad_$i", null)?.let { u ->
                viewModelScope.launch(Dispatchers.IO) { loadPadFrom(i, Uri.parse(u)) }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(50)
                for (i in 0..2) meters[i] = if (running) NativeEngine.getMeter(i) else 0f
            }
        }
    }
}
