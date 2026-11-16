#pragma once
#include <algorithm>
#include <cmath>
#include <vector>

namespace gp {

constexpr float kPi = 3.14159265358979f;

inline float dbToLin(float db) { return std::pow(10.f, db / 20.f); }
inline float linToDb(float lin) { return 20.f * std::log10(lin + 1e-9f); }

// High-pass biquad (RBJ), transposed direct form II.
struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
    void setHighpass(float fs, float fc, float q = 0.70710678f) {
        fc = std::min(fc, fs * 0.45f);
        const float w0 = 2.f * kPi * fc / fs;
        const float c = std::cos(w0), alpha = std::sin(w0) / (2.f * q);
        const float a0 = 1.f + alpha;
        b0 = ((1.f + c) * 0.5f) / a0;
        b1 = -(1.f + c) / a0;
        b2 = b0;
        a1 = (-2.f * c) / a0;
        a2 = (1.f - alpha) / a0;
    }
    void reset() { z1 = z2 = 0.f; }
    inline float process(float x) {
        const float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
};

// Compressor feed-forward sederhana (detektor peak, attack/release).
struct Compressor {
    float env = 0.f, attC = 0.f, relC = 0.f;
    void setup(float fs, float attMs, float relMs) {
        attC = std::exp(-1.f / (fs * attMs * 0.001f));
        relC = std::exp(-1.f / (fs * relMs * 0.001f));
        env = 0.f;
    }
    inline float process(float x, float thrDb, float ratio, float makeupLin) {
        const float a = std::fabs(x);
        env = (a > env) ? attC * env + (1.f - attC) * a : relC * env + (1.f - relC) * a;
        const float over = linToDb(env) - thrDb;
        const float g = over > 0.f ? dbToLin(-over * (1.f - 1.f / ratio)) : 1.f;
        return x * g * makeupLin;
    }
};

// Reverb gaya Freeverb (4 comb + 2 allpass per sisi).
struct Comb {
    std::vector<float> buf;
    int idx = 0;
    float store = 0.f, fb = 0.84f, damp = 0.3f;
    void init(int n) { buf.assign(std::max(n, 1), 0.f); idx = 0; store = 0.f; }
    inline float process(float in) {
        const float out = buf[idx];
        store = out * (1.f - damp) + store * damp;
        if (std::fabs(store) < 1e-15f) store = 0.f;
        buf[idx] = in + store * fb;
        if (++idx >= (int)buf.size()) idx = 0;
        return out;
    }
};

struct Allpass {
    std::vector<float> buf;
    int idx = 0;
    void init(int n) { buf.assign(std::max(n, 1), 0.f); idx = 0; }
    inline float process(float in) {
        const float b = buf[idx];
        const float out = -in + b;
        buf[idx] = in + b * 0.5f;
        if (++idx >= (int)buf.size()) idx = 0;
        return out;
    }
};

struct Reverb {
    Comb cl[4], cr[4];
    Allpass al[2], ar[2];
    void init(float fs) {
        static const int combLen[4] = {1116, 1188, 1277, 1356};
        static const int apLen[2] = {556, 441};
        const float k = fs / 44100.f;
        for (int i = 0; i < 4; ++i) {
            cl[i].init((int)(combLen[i] * k));
            cr[i].init((int)((combLen[i] + 23) * k));
        }
        for (int i = 0; i < 2; ++i) {
            al[i].init((int)(apLen[i] * k));
            ar[i].init((int)((apLen[i] + 23) * k));
        }
    }
    inline void process(float in, float& l, float& r) {
        in *= 0.5f;
        float a = 0.f, b = 0.f;
        for (int i = 0; i < 4; ++i) { a += cl[i].process(in); b += cr[i].process(in); }
        for (int i = 0; i < 2; ++i) { a = al[i].process(a); b = ar[i].process(b); }
        l = a * 0.3f;
        r = b * 0.3f;
    }
};

}  // namespace gp
