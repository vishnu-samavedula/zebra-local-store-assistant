package com.example.zebralocalai.inference

import java.io.File

data class P1BModel(val file: File, private val expectedBytes: Long = SPEC.bytes) {
  val isRunnable: Boolean get() = file.isFile && file.length() == expectedBytes

  companion object {
    val SPEC =
      ModelBundle.Companion.FileSpec(
        name = "LFM2.5-350M-Warehouse-Stable-Q4_K.gguf",
        bytes = 229_314_496,
        sha256 = "79863ccf4cd66b5d7532d4ac3f2cb9e8de65164ebd2b8251574fadf363ed1a15",
      )

    fun from(directory: File) = P1BModel(File(directory, SPEC.name))

    fun baseline(file: File) = P1BModel(file, BASELINE_Q4_K_M_BYTES)

    private const val BASELINE_Q4_K_M_BYTES = 229_312_224L
  }
}
