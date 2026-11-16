# GP Mix untuk Android (MVP 0.1)

Mixer digital untuk streamer: mic masuk, diproses, dicampur dengan sample pad, keluar ke headphone / output USB.
Engine audio native (C++ + Oboe, latency rendah), UI Jetpack Compose.

## Yang sudah ada
- Channel **MIC**: HPF, compressor, reverb send, pan, fader, mute, meter
- Channel **PADS**: 8 sample pad (WAV 16/24/32-bit & float), pan/balance, fader, mute
- **MASTER**: fader, mute, limiter -0.3 dBFS
- 3 **scene** (simpan / panggil seluruh mixer)
- Pilih input: mic bawaan, USB, headset
- Foreground service (jalan terus saat layar mati)

## Belum ada (roadmap)
Efek lain dari versi macOS (EQ, de-esser, Dynamic EQ, dll.), Retrospective Record, Web Remote, MIDI,
channel dinamis (saat ini 3 strip tetap), multi-Master, penangkapan suara aplikasi lain
(AudioPlaybackCapture), virtual cable / VST / AU (tidak ada di Android).

## Build lokal
Butuh JDK 17, Android SDK + NDK, Gradle 8.9+:

    gradle assembleDebug        # APK: app/build/outputs/apk/debug/

(Tambahkan Gradle wrapper dengan `gradle wrapper` bila ingin memakai `./gradlew`.)

## Build di GitHub Actions
1. Buat repo, push seluruh isi folder ini ke branch `main`.
2. Tab **Actions** -> *Build Android APK* berjalan otomatis. Unduh APK di **Artifacts** (`GPMix-APK`).
3. Release bertanda tangan (opsional): tambahkan secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
   `KEY_ALIAS`, `KEY_PASSWORD`. Push tag `v0.1.0` untuk menerbitkan GitHub Release.

## Catatan pemakaian
Pakai **headphone** — mic + speaker = feedback. Latency yang tampil adalah perkiraan.
