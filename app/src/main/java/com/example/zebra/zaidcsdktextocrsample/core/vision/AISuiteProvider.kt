package com.example.zebra.zaidcsdktextocrsample.core.vision

import android.content.Context
import java.io.Closeable

class AISuiteProvider(private val context: Context) : Closeable {

    override fun close() {
    }

    companion object {
        private const val TAG = "AISuiteProvider"
    }
}