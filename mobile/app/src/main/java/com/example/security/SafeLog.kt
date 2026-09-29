package com.example.security

import android.util.Log
import com.example.BuildConfig

/**
 * Keeps production diagnostics operational without writing exception details or user data.
 * Callers must provide static, non-sensitive messages only.
 */
object SafeLog {
    fun debug(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    fun info(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.i(tag, message)
        }
    }

    fun warning(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, diagnosticMessage(message, error))
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, diagnosticMessage(message, error))
    }

    private fun diagnosticMessage(message: String, error: Throwable?): String {
        if (!BuildConfig.DEBUG || error == null) {
            return message
        }
        return "$message (${error.javaClass.simpleName})"
    }
}
