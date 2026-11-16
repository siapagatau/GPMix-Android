#include "engine.h"

#include <algorithm>

namespace gp {

namespace {
constexpr float kSmooth = 0.002f;
constexpr float kCeil = 0.966f;  // ~ -0.3 dBFS

class DataCb : public oboe::AudioStreamDataCallback {
public:
    explicit DataCb(Engine* e) : e_(e) {}
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t n) override {
        return e_->render(static_cast<float*>(data), n);
    }
private:
    Engine* e_;
};

class ErrCb : public oboe::AudioStreamErrorCallback {
public:
    explicit ErrCb(Engine* e) : e_(e) {}
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override { e_->handleError(); }
private:
    Engine* e_;
};

inline void panGains(float pan, bool constPower, float& gl, float& gr) {
    pan = std::clamp(pan, -1.f, 1.f);
    if (constPower) {  // pan law -3 dB
        const float a = (pan + 1.f) * 0.25f * kPi;
        gl = std::cos(a);
        gr = std::sin(a);
    } else {  // balance
        gl = pan > 0.f ? 1.f - pan : 1.f;
        gr = pan < 0.f ? 1.f + pan : 1.f;
    }
}
}  // namespace

Engine::Engine() {
    for (auto& m : meter_) m.store(0.f);
    dataCb_ = std::make_shared<DataCb>(this);
    errCb_ = std::make_shared<ErrCb>(this);
    for (int i = 1; i < kNumChannels; ++i) {
        ch_[i].hpfOn.store(false);
        ch_[i].compOn.store(false);
    }
    std::fill_n(mic_, kBlock, 0.f);
}

int Engine::start(int32_t inputDeviceId) {
    std::lock_guard<std::mutex> lk(mtx_);
    inputDeviceId_ = inputDeviceId;
    wantRunning_.store(true);
    return openStreamsLocked();
}

void Engine::stop() {
    std::lock_guard<std::mutex> lk(mtx_);
    wantRunning_.store(false);
    closeStreamsLocked();
}

void Engine::handleError() {
    std::unique_lock<std::mutex> lk(mtx_, std::try_to_lock);
    if (!lk.owns_lock() || !wantRunning_.load()) return;
    openStreamsLocked();
}

void Engine::closeStreamsLocked() {
    // Output ditutup dulu: setelah close() callback pasti sudah selesai.
    if (outStream_) { outStream_->requestStop(); outStream_->close(); outStream_.reset(); }
    if (inStream_) { inStream_->requestStop(); inStream_->close(); inStream_.reset(); }
}

int Engine::openStreamsLocked() {
    closeStreamsLocked();

    oboe::AudioStreamBuilder ob;
    ob.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Media)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setDataCallback(dataCb_)
        ->setErrorCallback(errCb_);
    if (ob.openStream(outStream_) != oboe::Result::OK) {
        outStream_.reset();
        return 1;
    }
    sampleRate_ = outStream_->getSampleRate();
    outStream_->setBufferSizeInFrames(outStream_->getFramesPerBurst() * 2);

    // Input: coba Unprocessed+Exclusive, lalu fallback VoiceRecognition+Shared.
    int rc = 2;
    for (int attempt = 0; attempt < 2 && rc != 0; ++attempt) {
        oboe::AudioStreamBuilder ib;
        ib.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(attempt == 0 ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRate(sampleRate_)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setInputPreset(attempt == 0 ? oboe::InputPreset::Unprocessed
                                          : oboe::InputPreset::VoiceRecognition)
            ->setDeviceId(inputDeviceId_)
            ->setFormatConversionAllowed(true)
            ->setChannelConversionAllowed(true)
            ->setErrorCallback(errCb_);
        if (ib.openStream(inStream_) == oboe::Result::OK) rc = 0;
        else inStream_.reset();
    }

    // Siapkan DSP sebelum stream jalan.
    const float sr = (float)sampleRate_;
    hpf_.reset();
    lastHpf_ = -1.f;
    comp_.setup(sr, 5.f, 120.f);
    reverb_.init(sr);
    limGain_ = 1.f;
    limRel_ = 1.f - std::exp(-1.f / (sr * 0.1f));

    if (inStream_) inStream_->requestStart();
    outStream_->requestStart();
    return rc;
}

oboe::DataCallbackResult Engine::render(float* out, int32_t numFrames) {
    int32_t done = 0;
    while (done < numFrames) {
        const int n = std::min<int32_t>(kBlock, numFrames - done);
        renderChunk(out + done * 2, n);
        done += n;
    }
    return oboe::DataCallbackResult::Continue;
}

void Engine::setMeter(int ch, float peak) {
    const float prev = meter_[ch].load(std::memory_order_relaxed);
    meter_[ch].store(std::max(peak, prev * 0.95f), std::memory_order_relaxed);
}

void Engine::renderPad(Pad& pad, int n) {
    std::unique_lock<std::mutex> lk(pad.m, std::try_to_lock);
    if (!lk.owns_lock()) return;
    if (pad.stopReq.exchange(false)) { pad.active = false; pad.playing.store(false); }
    if (pad.trigger.exchange(false) && !pad.L.empty()) {
        pad.pos = 0.0;
        pad.active = true;
        pad.playing.store(true);
    }
    if (!pad.active) return;
    const int frames = (int)pad.L.size();
    const double step = pad.srcRate / (double)sampleRate_;
    for (int i = 0; i < n; ++i) {
        const int i0 = (int)pad.pos;
        if (i0 >= frames - 1) { pad.active = false; pad.playing.store(false); break; }
        const float f = (float)(pad.pos - i0);
        padL_[i] += pad.L[i0] + (pad.L[i0 + 1] - pad.L[i0]) * f;
        padR_[i] += pad.R[i0] + (pad.R[i0 + 1] - pad.R[i0]) * f;
        pad.pos += step;
    }
}

void Engine::renderChunk(float* out, int n) {
    const float sr = (float)sampleRate_;
    std::fill_n(mixL_, n, 0.f);
    std::fill_n(mixR_, n, 0.f);

    // ---- MIC (ch 0) ----
    std::fill_n(mic_, n, 0.f);
    if (inStream_) inStream_->read(mic_, n, 0);  // non-blocking; sisa tetap 0
    {
        const ChannelParams& p = ch_[0];
        const float hz = p.hpfHz.load();
        if (hz != lastHpf_) { hpf_.setHighpass(sr, hz); lastHpf_ = hz; }
        const bool hpfOn = p.hpfOn.load(), compOn = p.compOn.load();
        const float thr = p.compThr.load();
        const float ratio = std::max(1.f, p.compRatio.load());
        const float makeup = dbToLin(-thr * (1.f - 1.f / ratio) * 0.5f);
        const float send = p.send.load();
        const float target = p.mute.load() ? 0.f : dbToLin(p.faderDb.load());
        float gl, gr;
        panGains(p.pan.load(), true, gl, gr);
        float pk = 0.f;
        for (int i = 0; i < n; ++i) {
            float x = mic_[i];
            if (hpfOn) x = hpf_.process(x);
            if (compOn) x = comp_.process(x, thr, ratio, makeup);
            micGain_ += (target - micGain_) * kSmooth;
            x *= micGain_;
            pk = std::max(pk, std::fabs(x));
            float rl, rr;
            reverb_.process(x * send, rl, rr);
            mixL_[i] += x * gl + rl;
            mixR_[i] += x * gr + rr;
        }
        setMeter(0, pk);
    }

    // ---- PADS (ch 1) ----
    std::fill_n(padL_, n, 0.f);
    std::fill_n(padR_, n, 0.f);
    for (auto& pad : pads_) renderPad(pad, n);
    {
        const ChannelParams& p = ch_[1];
        const float target = p.mute.load() ? 0.f : dbToLin(p.faderDb.load());
        float gl, gr;
        panGains(p.pan.load(), false, gl, gr);
        float pk = 0.f;
        for (int i = 0; i < n; ++i) {
            padGain_ += (target - padGain_) * kSmooth;
            const float l = padL_[i] * padGain_, r = padR_[i] * padGain_;
            pk = std::max(pk, std::max(std::fabs(l), std::fabs(r)));
            mixL_[i] += l * gl;
            mixR_[i] += r * gr;
        }
        setMeter(1, pk);
    }

    // ---- MASTER (ch 2) + limiter ----
    {
        const ChannelParams& p = ch_[2];
        const float target = p.mute.load() ? 0.f : dbToLin(p.faderDb.load());
        float pk = 0.f;
        for (int i = 0; i < n; ++i) {
            masterGain_ += (target - masterGain_) * kSmooth;
            float l = mixL_[i] * masterGain_, r = mixR_[i] * masterGain_;
            const float peak = std::max(std::fabs(l), std::fabs(r));
            const float need = peak > kCeil ? kCeil / peak : 1.f;
            limGain_ = std::min(limGain_ + (1.f - limGain_) * limRel_, need);
            l = std::clamp(l * limGain_, -1.f, 1.f);
            r = std::clamp(r * limGain_, -1.f, 1.f);
            out[i * 2] = l;
            out[i * 2 + 1] = r;
            pk = std::max(pk, std::max(std::fabs(l), std::fabs(r)));
        }
        setMeter(2, pk);
    }
}

void Engine::setParam(int ch, int id, float v) {
    if (ch < 0 || ch >= kNumChannels) return;
    ChannelParams& p = ch_[ch];
    switch (id) {
        case P_FADER_DB: p.faderDb.store(std::clamp(v, -60.f, 6.f)); break;
        case P_PAN: p.pan.store(std::clamp(v, -1.f, 1.f)); break;
        case P_MUTE: p.mute.store(v > 0.5f); break;
        case P_HPF_ON: p.hpfOn.store(v > 0.5f); break;
        case P_HPF_HZ: p.hpfHz.store(std::clamp(v, 20.f, 1000.f)); break;
        case P_COMP_ON: p.compOn.store(v > 0.5f); break;
        case P_COMP_THR: p.compThr.store(std::clamp(v, -60.f, 0.f)); break;
        case P_COMP_RATIO: p.compRatio.store(std::clamp(v, 1.f, 20.f)); break;
        case P_SEND: p.send.store(std::clamp(v, 0.f, 1.f)); break;
        default: break;
    }
}

float Engine::getMeter(int ch) const {
    return (ch >= 0 && ch < kNumChannels) ? meter_[ch].load(std::memory_order_relaxed) : 0.f;
}

void Engine::loadPad(int idx, const float* d, int frames, int channels, int rate) {
    if (idx < 0 || idx >= kNumPads || frames <= 0 || channels < 1 || rate < 8000) return;
    Pad& pad = pads_[idx];
    std::lock_guard<std::mutex> lk(pad.m);
    pad.L.resize(frames);
    pad.R.resize(frames);
    for (int i = 0; i < frames; ++i) {
        const float l = d[i * channels];
        pad.L[i] = l;
        pad.R[i] = channels > 1 ? d[i * channels + 1] : l;
    }
    pad.srcRate = (float)rate;
    pad.pos = 0.0;
    pad.active = false;
    pad.playing.store(false);
}

void Engine::clearPad(int idx) {
    if (idx < 0 || idx >= kNumPads) return;
    Pad& pad = pads_[idx];
    std::lock_guard<std::mutex> lk(pad.m);
    pad.L.clear();
    pad.R.clear();
    pad.active = false;
    pad.playing.store(false);
}

void Engine::triggerPad(int idx) {
    if (idx >= 0 && idx < kNumPads) pads_[idx].trigger.store(true);
}

void Engine::stopPads() {
    for (auto& p : pads_) p.stopReq.store(true);
}

bool Engine::isPadPlaying(int idx) const {
    return idx >= 0 && idx < kNumPads && pads_[idx].playing.load();
}

float Engine::latencyMs() {
    std::lock_guard<std::mutex> lk(mtx_);
    if (!outStream_) return 0.f;
    const float outFrames = (float)outStream_->getBufferSizeInFrames();
    const float inFrames = inStream_ ? (float)inStream_->getFramesPerBurst() * 2.f : 0.f;
    return (outFrames + inFrames) * 1000.f / (float)sampleRate_;
}

}  // namespace gp
