#pragma once
#include <oboe/Oboe.h>

#include <array>
#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

#include "dsp.h"

namespace gp {

constexpr int kBlock = 256;
constexpr int kNumChannels = 3;  // 0 = MIC, 1 = PADS, 2 = MASTER
constexpr int kNumPads = 8;

enum Param {
    P_FADER_DB = 0, P_PAN = 1, P_MUTE = 2, P_HPF_ON = 3, P_HPF_HZ = 4,
    P_COMP_ON = 5, P_COMP_THR = 6, P_COMP_RATIO = 7, P_SEND = 8
};

struct ChannelParams {
    std::atomic<float> faderDb{0.f};
    std::atomic<float> pan{0.f};
    std::atomic<bool> mute{false};
    std::atomic<bool> hpfOn{true};
    std::atomic<float> hpfHz{100.f};
    std::atomic<bool> compOn{true};
    std::atomic<float> compThr{-18.f};
    std::atomic<float> compRatio{3.f};
    std::atomic<float> send{0.15f};  // reverb send (hanya MIC)
};

struct Pad {
    std::mutex m;
    std::vector<float> L, R;
    float srcRate = 48000.f;
    double pos = 0.0;
    bool active = false;  // hanya diakses audio thread
    std::atomic<bool> trigger{false};
    std::atomic<bool> stopReq{false};
    std::atomic<bool> playing{false};
};

class Engine {
public:
    Engine();

    // 0 = ok, 1 = output gagal, 2 = output ok tapi mic gagal.
    int start(int32_t inputDeviceId);
    void stop();

    void setParam(int ch, int id, float v);
    float getMeter(int ch) const;

    void loadPad(int idx, const float* interleaved, int frames, int channels, int rate);
    void clearPad(int idx);
    void triggerPad(int idx);
    void stopPads();
    bool isPadPlaying(int idx) const;
    float latencyMs();

    // Dipanggil dari callback Oboe.
    oboe::DataCallbackResult render(float* out, int32_t numFrames);
    void handleError();

private:
    int openStreamsLocked();
    void closeStreamsLocked();
    void renderChunk(float* out, int n);
    void renderPad(Pad& pad, int n);
    void setMeter(int ch, float peak);

    std::mutex mtx_;
    std::shared_ptr<oboe::AudioStream> outStream_, inStream_;
    std::shared_ptr<oboe::AudioStreamDataCallback> dataCb_;
    std::shared_ptr<oboe::AudioStreamErrorCallback> errCb_;
    std::atomic<bool> wantRunning_{false};
    int32_t inputDeviceId_ = 0;
    int sampleRate_ = 48000;

    ChannelParams ch_[kNumChannels];
    std::atomic<float> meter_[kNumChannels];
    std::array<Pad, kNumPads> pads_;

    // State DSP (audio thread saja)
    Biquad hpf_;
    float lastHpf_ = -1.f;
    Compressor comp_;
    Reverb reverb_;
    float micGain_ = 1.f, padGain_ = 1.f, masterGain_ = 1.f, limGain_ = 1.f, limRel_ = 0.001f;
    float mic_[kBlock], mixL_[kBlock], mixR_[kBlock], padL_[kBlock], padR_[kBlock];
};

}  // namespace gp
