package com.example.zebralocalai.inference

import java.io.File

data class P1BModel(val file: File) {
  val isRunnable: Boolean get() = file.isFile && file.length() == SPEC.bytes

  companion object {
    val SPEC =
      ModelBundle.Companion.FileSpec(
        name = "LFM2.5-350M-P1B-SchemaFree-v4-Q8_0.gguf",
        bytes = 379_219_904,
        sha256 = "649d93199edcbddb1dffe5013aaac6b7ffeede602208194bba2c175579bcc947",
      )

    fun from(directory: File) = P1BModel(File(directory, SPEC.name))
  }
}
