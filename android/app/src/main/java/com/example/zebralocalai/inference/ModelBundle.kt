package com.example.zebralocalai.inference

import java.io.File

data class ModelBundle(
  val main: File,
  val projector: File,
  val vocoder: File?,
  val tokenizer: File?,
) {
  val isRunnable: Boolean
    get() =
      specs.values.all { spec ->
        val file = File(main.parentFile, spec.name)
        file.isFile && file.length() == spec.bytes
      }

  companion object {
    private const val MAIN = "LFM2.5-Audio-1.5B-Q4_0.gguf"
    private const val PROJECTOR = "mmproj-LFM2.5-Audio-1.5B-Q4_0.gguf"
    private const val VOCODER = "vocoder-LFM2.5-Audio-1.5B-Q4_0.gguf"
    private const val TOKENIZER = "tokenizer-LFM2.5-Audio-1.5B-Q4_0.gguf"

    data class FileSpec(val name: String, val bytes: Long, val sha256: String)

    val specs =
      listOf(
          FileSpec(MAIN, 695750880, "3583bee853be20331ca342b0593fefd8acc43fb61a41ec6f1a1dc7465823e0d8"),
          FileSpec(PROJECTOR, 219511136, "6b483682c263b100f8cc8022d61507e446b1d320b9febc328e7960f72d03f7ea"),
          FileSpec(VOCODER, 108986560, "423cfcb054f41b69a5706226c243abc96d2531c3aff1121f7a2ed17149b79c95"),
          FileSpec(TOKENIZER, 50546112, "01ec6afe4578bb1e02a4d43d87e7e5827d6b3d94d2d36912ee931b9c3050f1c1"),
        )
        .associateBy(FileSpec::name)

    val expectedNames = specs.keys

    fun from(directory: File): ModelBundle =
      ModelBundle(
        main = File(directory, MAIN),
        projector = File(directory, PROJECTOR),
        vocoder = File(directory, VOCODER).takeIf(File::isFile),
        tokenizer = File(directory, TOKENIZER).takeIf(File::isFile),
      )
  }
}
