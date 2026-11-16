package com.csolver.mobile

/**
 * JNI bridge to llama.cpp. The model is loaded from an app-private GGUF file.
 * No network API is called by this class.
 */
object LlamaBridge {
    init {
        System.loadLibrary("csolver")
    }

    external fun loadModel(modelPath: String, threads: Int): String
    external fun generate(prompt: String, maxTokens: Int): String
    external fun unload()
}
