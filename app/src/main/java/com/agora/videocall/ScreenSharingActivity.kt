package com.agora.videocall

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.DisplayMetrics
import com.agora.videocall.utils.GLRender
import com.agora.videocall.utils.ImgTexFrame
import com.agora.videocall.utils.SinkConnector
import com.agora.videocall.utils.capture.ScreenCapture
import io.agora.rtc.Constants
import io.agora.rtc.IRtcEngineEventHandler
import io.agora.rtc.RtcEngine
import io.agora.rtc.video.AgoraVideoFrame
import io.agora.rtc.video.VideoEncoderConfiguration

class ScreenSharingActivity : Activity() {
    private var mScreenCapture: ScreenCapture? = null
    private var mScreenGLRender: GLRender? = null
    private var mRtcEngine: RtcEngine? = null
    private val mIsLandSpace = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_sharing)
    }

    private fun initModules() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        if (mScreenGLRender == null) {
            mScreenGLRender = GLRender()
        }

        if (mScreenCapture == null) {
            mScreenCapture = ScreenCapture(applicationContext, mScreenGLRender, metrics.densityDpi)
        }

        mScreenCapture!!.mImgTexSrcConnector!!.connect(object : SinkConnector<ImgTexFrame>() {
            override fun onFormatChanged(obj: Any?) {}
            override fun onFrameAvailable(frame: ImgTexFrame) {
                if (mRtcEngine == null) {
                    return
                }
                val vf = AgoraVideoFrame()
                vf.format = AgoraVideoFrame.FORMAT_TEXTURE_OES
                vf.timeStamp = frame.pts
                vf.stride = frame.mFormat!!.mWidth
                vf.height = frame.mFormat!!.mHeight
                vf.textureID = frame.mTextureId
                vf.syncMode = true
                vf.eglContext14 = mScreenGLRender!!.eGLContext
                vf.transform = frame.mTexMatrix
                mRtcEngine!!.pushExternalVideoFrame(vf)
            }
        })

        mScreenCapture!!.setOnScreenCaptureListener(object : ScreenCapture.OnScreenCaptureListener {
            override fun onStarted() {}
            override fun onError(err: Int) {
                when (err) {
                    ScreenCapture.SCREEN_ERROR_SYSTEM_UNSUPPORTED -> {
                    }
                    ScreenCapture.SCREEN_ERROR_PERMISSION_DENIED -> {
                    }
                }
            }
        })

        var screenWidth = metrics.widthPixels
        var screenHeight = metrics.heightPixels
        if (mIsLandSpace && screenWidth < screenHeight ||
                !mIsLandSpace && screenWidth > screenHeight) {
            screenWidth = metrics.heightPixels
            screenHeight = metrics.heightPixels
        }

        setOffscreenPreview(screenWidth, screenHeight)


        if (mRtcEngine == null) {

            try {
                RtcEngine.destroy()
                mRtcEngine = RtcEngine.create(applicationContext, getString(R.string.agora_app_id), object : IRtcEngineEventHandler() {
                    override fun onWarning(warn: Int) {
                        super.onWarning(warn)
                    }
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }
            mRtcEngine!!.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
            mRtcEngine!!.enableVideo()

            if (mRtcEngine!!.isTextureEncodeSupported) {
                mRtcEngine!!.setExternalVideoSource(true, true, true)
            } else {
                throw RuntimeException("Can not work on device do not supporting texture" + mRtcEngine!!.isTextureEncodeSupported)
            }

            mRtcEngine!!.enableVideo()
            mRtcEngine!!.setVideoEncoderConfiguration(VideoEncoderConfiguration(VideoEncoderConfiguration.VD_1280x720,
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_30,
                    VideoEncoderConfiguration.STANDARD_BITRATE,
                    VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT))
            //            mRtcEngine.setVideoProfile(Constants.VIDEO_PROFILE_720P, true)
            mRtcEngine!!.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
        }
    }

    @Throws(IllegalArgumentException::class)
    fun setOffscreenPreview(width: Int, height: Int) {
        require(!(width <= 0 || height <= 0)) { "Invalid offscreen resolution" }
        mScreenGLRender!!.init(width, height)
    }

    private fun startCapture() {
        mScreenCapture!!.start()
    }

    override fun onResume() {
        super.onResume()
        initModules()
        startCapture()
        mRtcEngine!!.joinChannel(getString(R.string.agora_access_token), getString(R.string.agora_channel_name), "Extra Optional Data", 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        if(mRtcEngine!=null) {
            deInitModules()
        }
    }

    private fun deInitModules() {
        mRtcEngine!!.leaveChannel()
        RtcEngine.destroy()
        mRtcEngine = null

        stopCapture()
        if (mScreenCapture != null) {
            mScreenCapture!!.release()
            mScreenCapture = null
        }
        if (mScreenGLRender != null) {
            mScreenGLRender!!.quit()
            mScreenGLRender = null
        }
    }

    private fun stopCapture() {
        mScreenCapture!!.stop()
    }

    override fun onBackPressed() {
        intent = Intent(this, VideoChatViewActivity::class.java)
        startActivity(intent)
        deInitModules()
        finish()
    }
}