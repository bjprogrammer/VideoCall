package com.agora.videocall.utils.capture

import android.annotation.TargetApi
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.Surface
import android.view.Window
import com.agora.videocall.utils.*
import com.agora.videocall.utils.GLRender.GLRenderListener
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * capture video frames from screen
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class ScreenCapture(context: Context?, render: GLRender?, density: Int) : OnFrameAvailableListener {
    private val mContext: Context
    private var mOnScreenCaptureListener: OnScreenCaptureListener? = null
    var mMediaProjectManager // mMediaProjectionManager
            : MediaProjectionManager? = null
    private var mMediaProjection // mMediaProjection
            : MediaProjection? = null
    private var mVirtualDisplay // mVirtualDisplay
            : VirtualDisplay? = null
    private val mScreenBroadcastReceiver: BroadcastReceiver = ScreenBroadcastReceiver(this)
    private var mWidth = 1280 // mWidth
    private var mHeight = 720 // mHeight
    private var mState: AtomicInteger? = null
    private val mGLRender: GLRender
    private var mTextureId = 0
    private var mSurface: Surface? = null
    private var mSurfaceTexture: SurfaceTexture? = null
    private var mTexInited = false
    private var mImgTexFormat: ImgTexFormat? = null
    private var mMainHandler: Handler?
    private var mScreenSetupThread: HandlerThread? = null
    private var mScreenSetupHandler: Handler? = null
    private val mScreenDensity: Int

    // fill extra frame
    private var mFillFrameRunnable: Runnable? = null

    // Performance trace
    private var mLastTraceTime: Long = 0
    private var mFrameDrawed: Long = 0

    /**
     * Source pin transfer ImgTexFrame, used for gpu path and preview
     */
    @JvmField
    var mImgTexSrcConnector: SrcConnector<ImgTexFrame>? = null

    /**
     * Start screen record.<br></br>
     * Can only be called on mState IDLE.
     */
    fun start(): Boolean {
        if (DEBUG_ENABLED) {
            Log.d(TAG, "start")
        }
        if (mState!!.get() != SCREEN_STATE_IDLE) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            val msg = mMainHandler!!.obtainMessage(SCREEN_RECORD_FAILED, SCREEN_ERROR_SYSTEM_UNSUPPORTED, 0)
            mMainHandler!!.sendMessage(msg)
            return false
        }

        //if the screen permission is show, will start failed
        if (mScreenCaptureActivity != null) {
            Log.e(TAG, "start failed you may best confim the user permission")
            return false
        }
        mState!!.set(SCREEN_STATE_INITIALIZING)
        mScreenSetupHandler!!.removeMessages(MSG_SCREEN_START_SCREEN_ACTIVITY)
        mScreenSetupHandler!!.sendEmptyMessage(MSG_SCREEN_START_SCREEN_ACTIVITY)
        return true
    }

    /**
     * stop screen record
     */
    fun stop() {
        if (DEBUG_ENABLED) {
            Log.d(TAG, "stop")
        }
        if (mState!!.get() == SCREEN_STATE_IDLE) {
            return
        }

        // stop fill frame
        mMainHandler!!.removeCallbacks(mFillFrameRunnable)
        val msg = Message()
        msg.what = MSG_SCREEN_RELEASE
        msg.arg1 = RELEASE_SCREEN_THREAD.inv()
        mState!!.set(SCREEN_STATE_STOPPING)
        mScreenSetupHandler!!.removeMessages(MSG_SCREEN_RELEASE)
        mScreenSetupHandler!!.sendMessage(msg)
    }

    fun release() {
        // stop fill frame
        if (mMainHandler != null) {
            mMainHandler!!.removeCallbacks(mFillFrameRunnable)
        }
        if (mState!!.get() == SCREEN_STATE_IDLE) {
            mScreenSetupHandler!!.removeMessages(MSG_SCREEN_QUIT)
            mScreenSetupHandler!!.sendEmptyMessage(MSG_SCREEN_QUIT)
            quitThread()
            return
        }
        val msg = Message()
        msg.what = MSG_SCREEN_RELEASE
        msg.arg1 = RELEASE_SCREEN_THREAD
        mState!!.set(SCREEN_STATE_STOPPING)
        mScreenSetupHandler!!.removeMessages(MSG_SCREEN_RELEASE)
        mScreenSetupHandler!!.sendMessage(msg)
        quitThread()
    }

    /**
     * screen status changed listener
     *
     * @param listener
     */
    fun setOnScreenCaptureListener(listener: OnScreenCaptureListener?) {
        mOnScreenCaptureListener = listener
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (mState!!.get() != SCREEN_STATE_CAPTURING) {
            return
        }
        mGLRender.requestRender()
        if (mMainHandler != null) {
            mMainHandler!!.removeCallbacks(mFillFrameRunnable)
            mMainHandler!!.postDelayed(mFillFrameRunnable, 100)
        }
    }

    private fun initTexFormat() {
        mImgTexFormat = ImgTexFormat(ImgTexFormat.COLOR_FORMAT_EXTERNAL_OES, mWidth, mHeight)
        mImgTexSrcConnector!!.onFormatChanged(mImgTexFormat)
    }

    fun initProjection(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (DEBUG_ENABLED) {
            Log.d(TAG, "initProjection")
        }
        mContext.unregisterReceiver(mScreenBroadcastReceiver)
        if (requestCode != MEDIA_PROJECTION_REQUEST_CODE) {
            if (DEBUG_ENABLED) {
                Log.d(TAG, "Unknown request code: $requestCode")
            }
        } else if (resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "Screen Cast Permission Denied, resultCode: $resultCode")
            val msg = mMainHandler!!.obtainMessage(SCREEN_RECORD_FAILED,
                    SCREEN_ERROR_PERMISSION_DENIED, 0)
            mMainHandler!!.sendMessage(msg)
            stop()
        } else {
            // get media projection and virtual display
            mMediaProjection = mMediaProjectManager!!.getMediaProjection(resultCode, intent!!)
            if (mSurface != null) {
                startScreenCapture()
            } else {
                mState!!.set(SCREEN_STATE_INITIALIZED)
            }
        }
    }

    private val mGLRenderListener: GLRenderListener = object : GLRenderListener {
        override fun onReady() {
            Log.d(TAG, "onReady")
        }

        override fun onSizeChanged(width: Int, height: Int) {
            Log.d(TAG, "onSizeChanged : $width*$height")
            mWidth = width
            mHeight = height
            mTexInited = false
            if (mVirtualDisplay != null) {
                mVirtualDisplay!!.release()
                mVirtualDisplay = null
            }
            mTextureId = GlUtil.createOESTextureObject()
            if (mSurfaceTexture != null) {
                mSurfaceTexture!!.release()
            }
            if (mSurface != null) {
                mSurface!!.release()
            }
            mSurfaceTexture = SurfaceTexture(mTextureId)
            mSurfaceTexture!!.setDefaultBufferSize(mWidth, mHeight)
            mSurface = Surface(mSurfaceTexture)
            mSurfaceTexture!!.setOnFrameAvailableListener(this@ScreenCapture)
            if (mState!!.get() >= SCREEN_STATE_INITIALIZED && mVirtualDisplay == null) {
                mScreenSetupHandler!!.removeMessages(MSG_SCREEN_START)
                mScreenSetupHandler!!.sendEmptyMessage(MSG_SCREEN_START)
            }
        }

        override fun onDrawFrame() {
            val pts = System.nanoTime() / 1000 / 1000
            try {
                mSurfaceTexture!!.updateTexImage()
            } catch (e: Exception) {
                Log.e(TAG, "updateTexImage failed, ignore")
                return
            }
            if (!mTexInited) {
                mTexInited = true
                initTexFormat()
            }
            val texMatrix = FloatArray(16)
            mSurfaceTexture!!.getTransformMatrix(texMatrix)
            val frame = ImgTexFrame(mImgTexFormat, mTextureId, texMatrix, pts)
            try {
                mImgTexSrcConnector!!.onFrameAvailable(frame)
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "Draw frame failed, ignore")
            }
            if (TRACE) {
                mFrameDrawed++
                val tm = System.currentTimeMillis()
                val tmDiff = tm - mLastTraceTime
                if (tmDiff >= 5000) {
                    val fps = mFrameDrawed * 1000f / tmDiff
                    Log.d(TAG, "screen fps: " + String.format(Locale.getDefault(), "%.2f", fps))
                    mFrameDrawed = 0
                    mLastTraceTime = tm
                }
            }
        }

        override fun onReleased() {}
    }

    private fun startScreenCapture() {
        mVirtualDisplay = mMediaProjection!!.createVirtualDisplay("ScreenCapture",
                mWidth, mHeight, mScreenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC, mSurface,
                null, null)
        mState!!.set(SCREEN_STATE_CAPTURING)
        val msg = mMainHandler!!.obtainMessage(SCREEN_RECORD_STARTED, 0, 0)
        mMainHandler!!.sendMessage(msg)
    }

    private class MainHandler(screenCapture: ScreenCapture) : Handler() {
        private val weakCapture: WeakReference<ScreenCapture>
        override fun handleMessage(msg: Message) {
            val screenCapture = weakCapture.get() ?: return
            when (msg.what) {
                SCREEN_RECORD_STARTED -> if (screenCapture.mOnScreenCaptureListener != null) {
                    screenCapture.mOnScreenCaptureListener!!.onStarted()
                }
                SCREEN_RECORD_FAILED -> if (screenCapture.mOnScreenCaptureListener != null) {
                    screenCapture.mOnScreenCaptureListener!!.onError(msg.arg1)
                }
                else -> {
                }
            }
        }

        init {
            weakCapture = WeakReference(screenCapture)
        }
    }

    private fun initScreenSetupThread() {
        mScreenSetupThread = HandlerThread("screen_setup_thread", Thread.NORM_PRIORITY)
        mScreenSetupThread!!.start()
        mScreenSetupHandler = object : Handler(mScreenSetupThread!!.looper) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_SCREEN_START_SCREEN_ACTIVITY -> {
                        doScreenSetup()
                    }
                    MSG_SCREEN_INIT_PROJECTION -> {
                        initProjection(msg.arg1, msg.arg2, mProjectionIntent)
                    }
                    MSG_SCREEN_START -> {
                        startScreenCapture()
                    }
                    MSG_SCREEN_RELEASE -> {
                        doScreenRelease(msg.arg1)
                    }
                    MSG_SCREEN_QUIT -> {
                        mScreenSetupThread!!.quit()
                    }
                }
            }
        }
    }

    private fun quitThread() {
        try {
            mScreenSetupThread!!.join()
        } catch (e: InterruptedException) {
            Log.d(TAG, "quitThread " + Log.getStackTraceString(e))
        } finally {
            mScreenSetupThread = null
        }
        if (mMainHandler != null) {
            mMainHandler!!.removeCallbacksAndMessages(null)
            mMainHandler = null
        }
    }

    private fun doScreenSetup() {
        if (DEBUG_ENABLED) {
            Log.d(TAG, "doScreenSetup")
        }
        if (mMediaProjectManager == null) {
            mMediaProjectManager = mContext.getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        }
        // registerReceiver
        var intentFilter: IntentFilter?
        IntentFilter().also { intentFilter = it }.addAction(ASSISTANT_ACTIVITY_CREATED_INTENT)
        mContext.registerReceiver(mScreenBroadcastReceiver, intentFilter)

        // start ScreenCaptureAssistantActivity for MediaProjection onActivityResult
        var intent: Intent?
        Intent(mContext, ScreenCaptureAssistantActivity::class.java).also { intent = it }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        mContext.startActivity(intent)
    }

    private fun doScreenRelease(isQuit: Int) {
        if (DEBUG_ENABLED) {
            Log.d(TAG, "doScreenRelease")
        }
        mState!!.set(SCREEN_STATE_IDLE)
        if (mVirtualDisplay != null) {
            mVirtualDisplay!!.release()
        }
        if (mMediaProjection != null) {
            mMediaProjection!!.stop()
        }
        mVirtualDisplay = null
        mMediaProjection = null
        if (isQuit == RELEASE_SCREEN_THREAD) {
            mScreenSetupHandler!!.sendEmptyMessage(MSG_SCREEN_QUIT)
        }
    }

    var mProjectionIntent: Intent? = null

    class ScreenCaptureAssistantActivity : Activity() {
        var mScreenCapture // init in the ScreenBroadcastReceiver;
                : ScreenCapture? = null

        public override fun onCreate(bundle: Bundle?) {
            super.onCreate(bundle)
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            mScreenCaptureActivity = this
            // send to start the media projection activity
            val intent = Intent(ASSISTANT_ACTIVITY_CREATED_INTENT)
            sendBroadcast(intent)
        }

        public override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent) {
            if (mScreenCapture != null && mScreenCapture!!.mState!!.get() != SCREEN_STATE_IDLE) {
                val msg = Message()
                msg.what = MSG_SCREEN_INIT_PROJECTION
                msg.arg1 = requestCode
                msg.arg2 = resultCode
                mScreenCapture!!.mProjectionIntent = intent
                mScreenCapture!!.mScreenSetupHandler!!.removeMessages(MSG_SCREEN_INIT_PROJECTION)
                mScreenCapture!!.mScreenSetupHandler!!.sendMessage(msg)
            }
            finish()
            mScreenCapture = null
            mScreenCaptureActivity = null
        }
    }

    interface OnScreenCaptureListener {
        /**
         * Notify screen capture started.
         */
        fun onStarted()

        /**
         * Notify error occurred while camera capturing.
         *
         * @param err err code.
         * @see .SCREEN_ERROR_SYSTEM_UNSUPPORTED
         *
         * @see .SCREEN_ERROR_PERMISSION_DENIED
         */
        fun onError(err: Int)
    }

    companion object {
        private const val DEBUG_ENABLED = true
        private val TAG = ScreenCapture::class.java.simpleName
        const val ASSISTANT_ACTIVITY_CREATED_INTENT = "ScreenCapture.OnAssistantActivityCreated"
        const val MEDIA_PROJECTION_REQUEST_CODE = 1001
        var mScreenCaptureActivity: ScreenCaptureAssistantActivity? = null
        const val SCREEN_STATE_IDLE = 0
        const val SCREEN_STATE_INITIALIZING = 1
        const val SCREEN_STATE_INITIALIZED = 2
        const val SCREEN_STATE_STOPPING = 3
        const val SCREEN_STATE_CAPTURING = 4
        const val SCREEN_ERROR_SYSTEM_UNSUPPORTED = -1
        const val SCREEN_ERROR_PERMISSION_DENIED = -2
        const val SCREEN_RECORD_STARTED = 4
        const val SCREEN_RECORD_FAILED = 5
        private const val MSG_SCREEN_START_SCREEN_ACTIVITY = 1
        private const val MSG_SCREEN_INIT_PROJECTION = 2
        private const val MSG_SCREEN_START = 3
        private const val MSG_SCREEN_RELEASE = 4
        private const val MSG_SCREEN_QUIT = 5
        private const val RELEASE_SCREEN_THREAD = 1
        private const val TRACE = true
    }

    init {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            throw RuntimeException("Need API level " + Build.VERSION_CODES.LOLLIPOP)
        }
        require(!(context == null || render == null)) { "the context or render must be not null" }
        mContext = context
        mGLRender = render
        mScreenDensity = density
        mGLRender.addListener(mGLRenderListener)
        mImgTexSrcConnector = SrcConnector()
        mMainHandler = MainHandler(this)
        mState = AtomicInteger(SCREEN_STATE_IDLE)
        mFillFrameRunnable = Runnable {
            if (mState!!.get() == SCREEN_STATE_CAPTURING) {
                mGLRender.requestRender()
                (mMainHandler as MainHandler).postDelayed(mFillFrameRunnable, 100)
            }
        }
        initScreenSetupThread()
    }
}