package com.nicos.pitchkit.tuner

internal class HighPassFilter(cutoffHz: Double, sampleRate: Int) {
    // One-pole high-pass: alpha = RC / (RC + dt) = 1 / (1 + 2π·fc/fs)
    private val alpha = (1.0 / (1.0 + 2.0 * Math.PI * cutoffHz / sampleRate)).toFloat()
    private var prevIn = 0f
    private var prevOut = 0f

    fun process(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        for (i in input.indices) {
            val x = input[i]
            val y = alpha * (prevOut + x - prevIn)
            prevIn = x
            prevOut = y
            out[i] = y
        }
        return out
    }

    fun reset() {
        prevIn = 0f; prevOut = 0f
    }
}