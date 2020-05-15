package com.agora.videocall.utils.capture

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class ScreenBroadcastReceiver(var mSender: ScreenCapture) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action.equals(ScreenCapture.ASSISTANT_ACTIVITY_CREATED_INTENT, ignoreCase = true)) {
            val screenCapture = mSender
            if (ScreenCapture.mScreenCaptureActivity != null) {
                val screenActivity = ScreenCapture.mScreenCaptureActivity
                ScreenCapture.mScreenCaptureActivity!!.mScreenCapture = screenCapture
                if (screenActivity?.mScreenCapture?.mMediaProjectManager == null) {
                    screenActivity?.mScreenCapture?.mMediaProjectManager = screenActivity?.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                }
                screenActivity.startActivityForResult(
                        screenActivity.mScreenCapture?.mMediaProjectManager!!.createScreenCaptureIntent(),
                        ScreenCapture.MEDIA_PROJECTION_REQUEST_CODE)
            }
        }
    }

}