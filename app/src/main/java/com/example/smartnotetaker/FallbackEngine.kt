package com.example.smartnotetaker

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

object FallbackEngine {
    fun initializeEngine(modelFile: File): Engine {
        return try {
            Log.i("SmartNoteTaker", "Trying GPU Backend...")
            val engine = Engine(EngineConfig(modelFile.absolutePath, backend = Backend.GPU()))
            engine.initialize()
            engine
        } catch (e: Exception) {
            Log.e("SmartNoteTaker", "GPU Backend failed, falling back to CPU", e)
            val engine = Engine(EngineConfig(modelFile.absolutePath, backend = Backend.CPU()))
            engine.initialize()
            engine
        }
    }
}
