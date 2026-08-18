package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * BroadcastReceiver listening for TelephonyManager.ACTION_PHONE_STATE_CHANGED.
 * When a call ends (EXTRA_STATE_IDLE), it automatically relaunches MainActivity
 * with FLAG_ACTIVITY_NEW_TASK and FLAG_ACTIVITY_CLEAR_TOP to keep the user locked
 * inside the elderly kiosk launcher.
 */
class CallStateReceiver : BroadcastReceiver() {

  companion object {
    private const val TAG = "CallStateReceiver"
  }

  override fun onReceive(context: Context, intent: Intent?) {
    if (intent == null) return

    val action = intent.action
    if (TelephonyManager.ACTION_PHONE_STATE_CHANGED == action) {
      val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
      Log.d(TAG, "TelephonyManager state changed: $state")

      if (TelephonyManager.EXTRA_STATE_IDLE == state) {
        Log.d(TAG, "Call ended (EXTRA_STATE_IDLE). Relaunching MainActivity into Kiosk mode.")
        val kioskIntent = Intent(context, MainActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
          addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
          putExtra(MainActivity.EXTRA_FROM_CALL_STATE, true)
        }
        context.startActivity(kioskIntent)
      }
    }
  }
}
