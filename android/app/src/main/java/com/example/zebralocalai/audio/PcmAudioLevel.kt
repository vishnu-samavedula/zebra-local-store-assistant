package com.example.zebralocalai.audio

import kotlin.math.sqrt

/** Root-mean-square amplitude of signed, little-endian 16-bit microphone samples. */
object PcmAudioLevel {
  fun normalized(bytes: ByteArray, count: Int): Float {
    val length = count.coerceIn(0, bytes.size) and -2
    if (length == 0) return 0f
    var squares = 0.0
    for (i in 0 until length step 2) {
      val sample = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
      squares += sample.toDouble() * sample
    }
    return (sqrt(squares / (length / 2)) / 32768.0).toFloat().coerceIn(0f, 1f)
  }
}
