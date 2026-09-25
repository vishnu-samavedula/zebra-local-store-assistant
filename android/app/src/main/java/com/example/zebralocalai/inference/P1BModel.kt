package com.example.zebralocalai.inference

import java.io.File

data class P1BModel(val file: File) {
  val isRunnable: Boolean get() = file.isFile && file.length() == SPEC.bytes

  companion object {
    val SPEC =
      ModelBundle.Companion.FileSpec(
        name = "LFM2.5-350M-P1B-SchemaFree-v4-Q4_K.gguf",
        bytes = 229_314_496,
        sha256 = "4e422546677f4a7c4280625a787f66348d37029e047bee5708dc6930a2f45b84",
      )

    fun from(directory: File) = P1BModel(File(directory, SPEC.name))
  }
}
