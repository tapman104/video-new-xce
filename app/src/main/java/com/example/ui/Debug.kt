package com.example.ui

import android.util.Log
import com.example.BuildConfig

object VoraLog {
    fun gesture(msg: String) { if (BuildConfig.DEBUG) Log.d("Vora.Gesture", msg) }
    fun player(msg: String)  { if (BuildConfig.DEBUG) Log.d("Vora.Player",  msg) }
    fun effect(msg: String)  { if (BuildConfig.DEBUG) Log.d("Vora.Effect",  msg) }
    fun vm(msg: String)      { if (BuildConfig.DEBUG) Log.d("Vora.VM",      msg) }
}
