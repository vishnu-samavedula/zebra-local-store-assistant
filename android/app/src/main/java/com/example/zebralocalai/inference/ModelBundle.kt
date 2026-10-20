package com.example.zebralocalai.inference

import java.io.File

data class ModelBundle(
  val main: File,
  val projector: File,
  val vocoder: File,
  val tokenizer: File,
) {
  val isRunnable: Boolean
    get() =
      specs.values.all { spec ->
        val file = File(main.parentFile, spec.name)
        file.isFile && file.length() == spec.bytes
      }

  companion object {
    private const val MAIN = "LFM2.5-Audio-600M-Warehouse-Q8_0.gguf"
    private const val PROJECTOR = "mmproj-LFM2.5-Audio-600M-MB-mini-Q8_0.gguf"
    private const val VOCODER = "vocoder-LFM2.5-Audio-600M-MB-mini-Q8_0.gguf"
    private const val TOKENIZER = "tokenizer-LFM2.5-Audio-600M-MB-mini-Q8_0.gguf"

    data class FileSpec(val name: String, val bytes: Long, val sha256: String)

    val specs =
      listOf(
          FileSpec(MAIN, 379_216_064, "73440d7716a271a7ccbf6f3028b2659b9059a319ccd90b3c7593f2b84446deba"),
          FileSpec(PROJECTOR, 243_828_576, "e25a273584801b6fa7d0fe828c0000054ea2c1dc380621db05292d85fc6a2e7b"),
          FileSpec(VOCODER, 108_986_560, "423cfcb054f41b69a5706226c243abc96d2531c3aff1121f7a2ed17149b79c95"),
          FileSpec(TOKENIZER, 50_546_112, "01ec6afe4578bb1e02a4d43d87e7e5827d6b3d94d2d36912ee931b9c3050f1c1"),
        )
        .associateBy(FileSpec::name)

    val expectedNames = specs.keys

    fun from(directory: File): ModelBundle =
      ModelBundle(
        main = File(directory, MAIN),
        projector = File(directory, PROJECTOR),
        vocoder = File(directory, VOCODER),
        tokenizer = File(directory, TOKENIZER),
      )
  }
}
