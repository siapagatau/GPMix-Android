package com.gpaudio.gpmix.audio

/** Jembatan JNI ke engine audio C++ (Oboe). Channel: 0 = MIC, 1 = PADS, 2 = MASTER. */
object NativeEngine {
    const val P_FADER = 0
    const val P_PAN = 1
    const val P_MUTE = 2
    const val P_HPF_ON = 3
    const val P_HPF_HZ = 4
    const val P_COMP_ON = 5
    const val P_COMP_THR = 6
    const val P_COMP_RATIO = 7
    const val P_SEND = 8

    init { System.loadLibrary("gpmix") }

    /** 0 = ok, 1 = output gagal, 2 = output ok tapi mic gagal. */
    external fun start(inputDeviceId: Int): Int
    external fun stop()
    external fun setParam(ch: Int, id: Int, value: Float)
    external fun getMeter(ch: Int): Float
    external fun loadPad(idx: Int, data: FloatArray, channels: Int, rate: Int)
    external fun clearPad(idx: Int)
    external fun triggerPad(idx: Int)
    external fun stopPads()
    external fun isPadPlaying(idx: Int): Boolean
    external fun latencyMs(): Float
}
