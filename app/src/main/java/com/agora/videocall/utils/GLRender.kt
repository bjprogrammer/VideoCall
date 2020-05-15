package com.agora.videocall.utils

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.Log
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GLRender {
    private var mGLHandlerThread: HandlerThread? = null
    private var mGLHandler: Handler? = null
    private var mTextureView: TextureView? = null
    private var mEglCore: EglCore? = null
    private var mWindowSurface: WindowSurface? = null
    var eGLContext: EGLContext? = null
        private set
    private var mGLSurfaceView: GLSurfaceView? = null
    private var mState: AtomicInteger? = null
    private var mThreadId: Long = 0
    private var mGLRenderListenerList: LinkedList<GLRenderListener>? = null
    private val mRenderListenerLock = Any()
    private var mEventTaskList: LinkedList<Runnable>? = null
    private val mEventLock = Any()
    private var mGLDrawTaskList: LinkedList<Runnable>? = null
    private val mDrawLock = Any()
    private val runnableDrawFrame = Runnable { doDrawFrame() }
    private val mGLRenderer: GLSurfaceView.Renderer = object : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
            surfaceCreated(true)
        }

        override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
            surfaceChanged(width, height)
        }

        override fun onDrawFrame(gl: GL10) {
            drawFrame()
        }
    }
    private val mTextureListener: SurfaceTextureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "onSurfaceTextureAvailable $surface $width $height")
            initHandlerThread()
            var msg = Message.obtain(mGLHandler, MSG_TYPE_SURFACE_CREATED, surface)
            mGLHandler!!.sendMessage(msg)
            msg = Message.obtain(mGLHandler, MSG_TYPE_SURFACE_CHANGED, width, height)
            mGLHandler!!.sendMessage(msg)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "onSurfaceTextureSizeChanged $surface $width $height")
            val msg = Message.obtain(mGLHandler, MSG_TYPE_SURFACE_CHANGED, width, height)
            mGLHandler!!.sendMessage(msg)
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            Log.d(TAG, "onSurfaceTextureDestroyed $st")
            quit(st)
            return false
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    constructor() {
        doInit(EGL14.EGL_NO_CONTEXT)
    }

    constructor(ctx: EGLContext) {
        doInit(ctx)
    }

    private fun doInit(ctx: EGLContext) {
        mState = AtomicInteger(STATE_RELEASED)
        mGLRenderListenerList = LinkedList()
        mEventTaskList = LinkedList()
        mGLDrawTaskList = LinkedList()
        eGLContext = ctx
    }

    fun init(width: Int, height: Int) {
        mState!!.set(STATE_IDLE)
        initHandlerThread()
        var msg = Message.obtain(mGLHandler, MSG_TYPE_SURFACE_CREATED, width, height)
        mGLHandler!!.sendMessage(msg)
        msg = Message.obtain(mGLHandler, MSG_TYPE_SURFACE_CHANGED, width, height)
        mGLHandler!!.sendMessage(msg)
    }

    fun init(sv: GLSurfaceView) {
        mState!!.set(STATE_IDLE)
        sv.setEGLContextClientVersion(2) // GLES 2.0
        sv.setRenderer(mGLRenderer)
        sv.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        mGLSurfaceView = sv
    }

    fun init(tv: TextureView) {
        mState!!.set(STATE_IDLE)
        tv.surfaceTextureListener = mTextureListener
        mTextureView = tv
    }

    fun addListener(listener: GLRenderListener) {
        synchronized(mRenderListenerLock) {
            if (!mGLRenderListenerList!!.contains(listener)) {
                mGLRenderListenerList!!.add(listener)
            }
        }
    }

    fun removeListener(listener: GLRenderListener) {
        synchronized(mRenderListenerLock) { mGLRenderListenerList!!.remove(listener) }
    }

    val state: Int
        get() = mState!!.get()

    val isGLRenderThread: Boolean
        get() = mThreadId == Thread.currentThread().id

    fun onPause() {
        if (mGLSurfaceView != null) {
            mState!!.set(STATE_RELEASED)
            mGLSurfaceView!!.queueEvent { quit() }
            mGLSurfaceView!!.onPause()
        }
    }

    fun onResume() {
        if (mState!!.get() == STATE_RELEASED) {
            mState!!.set(STATE_IDLE)
        }
        if (mGLSurfaceView != null) {
            mGLSurfaceView!!.onResume()
        }
    }

    fun requestRender() {
        if (mGLSurfaceView != null) {
            mGLSurfaceView!!.requestRender()
        }
        if (mGLHandler != null) {
            mGLHandler!!.sendEmptyMessage(MSG_TYPE_DRAW_FRAME)
        }
    }

    fun queueEvent(runnable: Runnable) {
        if (mState!!.get() == STATE_IDLE) {
            Log.d(TAG, "glContext not ready, queue event: $runnable")
            synchronized(mEventLock) { mEventTaskList!!.add(runnable) }
        } else if (mState!!.get() == STATE_READY) {
            if (mGLSurfaceView != null) {
                mGLSurfaceView!!.queueEvent(runnable)
                mGLSurfaceView!!.queueEvent(runnableDrawFrame)
            } else if (mGLHandler != null) {
                mGLHandler!!.post(runnable)
                mGLHandler!!.post(runnableDrawFrame)
            }
        } else {
            Log.d(TAG, "glContext lost, drop event: $runnable")
        }
    }

    fun queueDrawFrameAppends(runnable: Runnable) {
        if (mState!!.get() == STATE_READY) {
            synchronized(mDrawLock) { mGLDrawTaskList!!.add(runnable) }
        }
    }

    fun quit() {
        if (mTextureView == null && mGLSurfaceView == null && mGLHandlerThread != null) {
            mState!!.set(STATE_RELEASED)
            quit(null)
        }
    }

    private fun surfaceCreated(reInitCtx: Boolean) {
        mState!!.set(STATE_READY)
        mThreadId = Thread.currentThread().id
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        if (reInitCtx && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            eGLContext = EGL14.eglGetCurrentContext()
        }
        synchronized(mRenderListenerLock) {
            val it: Iterator<GLRenderListener> = mGLRenderListenerList!!.iterator()
            while (it.hasNext()) {
                val listener = it.next()
                listener.onReady()
            }
        }
    }

    private fun surfaceChanged(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        synchronized(mRenderListenerLock) {
            val it: Iterator<GLRenderListener> = mGLRenderListenerList!!.iterator()
            while (it.hasNext()) {
                val listener = it.next()
                listener.onSizeChanged(width, height)
            }
        }
    }

    private fun drawFrame() {
        var it: Iterator<*>
        synchronized(mEventLock) {
            it = mEventTaskList!!.iterator()
            while (true) {
                if (!it.hasNext()) {
                    mEventTaskList!!.clear()
                    break
                }
                val runnable = it.next() as Runnable
                runnable.run()
            }
        }
        synchronized(mRenderListenerLock) {
            it = mGLRenderListenerList!!.iterator()
            while (true) {
                if (!it.hasNext()) {
                    break
                }
                val listener = it.next() as GLRenderListener
                listener.onDrawFrame()
            }
        }
        doDrawFrame()
    }

    private fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            eGLContext = EGL14.EGL_NO_CONTEXT
        }
        mState!!.set(STATE_RELEASED)
        synchronized(mRenderListenerLock) {
            val it: Iterator<GLRenderListener> = mGLRenderListenerList!!.iterator()
            while (it.hasNext()) {
                val listener = it.next()
                listener.onReleased()
            }
        }
    }

    private fun doDrawFrame() {
        while (true) {
            var runnable: Runnable
            synchronized(mDrawLock) {
                if (mGLDrawTaskList!!.isEmpty()) {
                    return
                }
                runnable = mGLDrawTaskList!!.first
                mGLDrawTaskList!!.removeFirst()
            }
            runnable.run()
        }
    }

    private fun prepareGlSurface(st: SurfaceTexture?, width: Int, height: Int) {
        mEglCore = EglCore(eGLContext, 0)
        mWindowSurface = st?.let { WindowSurface(mEglCore, it) }
                ?: WindowSurface(mEglCore, width, height)
        mWindowSurface!!.makeCurrent()
        GLES20.glViewport(0, 0, mWindowSurface!!.width, mWindowSurface!!.height)
    }

    private fun releaseGlSurface(st: SurfaceTexture?) {
        st?.release()
        if (mWindowSurface != null) {
            mWindowSurface!!.release()
            mWindowSurface = null
        }
        if (mEglCore != null) {
            mEglCore!!.release()
            mEglCore = null
        }
    }

    private fun initHandlerThread() {
        if (mGLHandlerThread == null) {
            mGLHandlerThread = HandlerThread("MyGLThread")
            mGLHandlerThread!!.start()
            mGLHandler = Handler(mGLHandlerThread!!.looper, Handler.Callback { msg ->
                when (msg.what) {
                    MSG_TYPE_SURFACE_CREATED -> {
                        prepareGlSurface(msg.obj as? SurfaceTexture, msg.arg1, msg.arg2)
                        surfaceCreated(true)
                    }
                    MSG_TYPE_SURFACE_CHANGED -> surfaceChanged(msg.arg1, msg.arg2)
                    MSG_TYPE_DRAW_FRAME -> {
                        drawFrame()
                        mWindowSurface!!.swapBuffers()
                    }
                    MSG_TYPE_QUIT -> {
                        release()
                        releaseGlSurface(msg.obj as? SurfaceTexture)
                        mGLHandlerThread!!.quit()
                    }
                }
                true
            })
        }
    }

    private fun quit(st: SurfaceTexture?) {
        if (mGLHandlerThread != null) {
            mGLHandler!!.removeCallbacksAndMessages(null)
            val msg = Message.obtain(mGLHandler, MSG_TYPE_QUIT, st)
            mGLHandler!!.sendMessage(msg)
            try {
                mGLHandlerThread!!.join()
            } catch (e: InterruptedException) {
                Log.d(TAG, "quit " + Log.getStackTraceString(e))
            } finally {
                mGLHandlerThread = null
                mGLHandler = null
            }
        }
    }

    interface ScreenshotListener {
        fun onBitmapAvailable(screenshot: Bitmap?)
    }

    interface GLRenderListener {
        fun onReady()
        fun onSizeChanged(width: Int, height: Int)
        fun onDrawFrame()
        fun onReleased()
    }

    companion object {
        private const val TAG = "GLRender"
        private const val DEBUG_ENABLED = true
        const val STATE_IDLE = 0
        const val STATE_READY = 1
        const val STATE_RELEASED = 2
        private const val MSG_TYPE_SURFACE_CREATED = 0
        private const val MSG_TYPE_SURFACE_CHANGED = 1
        private const val MSG_TYPE_DRAW_FRAME = 2
        private const val MSG_TYPE_QUIT = 3
    }
}