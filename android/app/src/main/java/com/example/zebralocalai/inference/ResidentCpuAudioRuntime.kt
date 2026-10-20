package com.example.zebralocalai.inference

import android.content.Context
import android.os.SystemClock
import android.util.Log

/** Retains the CPU model across screen lifetimes, for as long as Android keeps this app process. */
internal object ResidentCpuAudioRuntime {
  private var instance: CpuAudioRuntime? = null

  @Synchronized
  fun get(context: Context): CpuAudioRuntime = instance ?: CpuAudioRuntime(context.applicationContext).also {
    instance = it
  }

  fun warmup(context: Context) {
    val runtime = get(context)
    val reusedProcess = runtime.serverInstance() != null
    val start = SystemClock.elapsedRealtime()
    runtime.warmup()
    Log.i("ResidentCpuRuntime", "ready reusedProcess=$reusedProcess waitMs=${SystemClock.elapsedRealtime() - start} " +
      "serverId=${System.identityHashCode(runtime.serverInstance())}")
  }
}
