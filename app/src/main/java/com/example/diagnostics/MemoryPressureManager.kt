package com.example.diagnostics

import android.content.ComponentCallbacks2
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

fun interface MemoryPressureListener {
    fun onTrimMemory(level: Int)
}

/**
 * Coordinates Android system low-memory and trim callbacks with thumbnail caches
 * and media buffers so large video imports cannot trigger OutOfMemory crashes.
 */
object MemoryPressureManager {

    private val listeners = CopyOnWriteArrayList<WeakReference<MemoryPressureListener>>()

    fun registerListener(listener: MemoryPressureListener) {
        pruneDead()
        if (listeners.none { it.get() === listener }) {
            listeners.add(WeakReference(listener))
        }
    }

    fun unregisterListener(listener: MemoryPressureListener) {
        listeners.removeAll { ref ->
            val target = ref.get()
            target == null || target === listener
        }
    }

    fun dispatchTrimMemory(level: Int) {
        pruneDead()
        listeners.forEach { ref ->
            runCatching { ref.get()?.onTrimMemory(level) }
        }
    }

    fun dispatchLowMemory() {
        dispatchTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
    }

    private fun pruneDead() {
        listeners.removeAll { it.get() == null }
    }
}
