package com.agora.videocall.utils

import android.annotation.TargetApi
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface

/**
 * Recordable EGL window surface.
 *
 *
 * It's good practice to explicitly quit() the surface, preferably from a "finally" block.
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
class WindowSurface : EglSurfaceBase {
    private var mSurface: Surface? = null

    constructor(eglCore: EglCore?, width: Int, height: Int) : super(eglCore!!) {
        createOffscreenSurface(width, height)
    }

    constructor(eglCore: EglCore?, surface: Surface?) : super(eglCore!!) {
        createWindowSurface(surface)
        mSurface = surface
    }

    constructor(eglCore: EglCore?, texture: SurfaceTexture?) : super(eglCore!!) {
        createWindowSurface(texture)
    }

    fun release() {
        releaseEglSurface()
        if (mSurface != null) {
            mSurface!!.release()
            mSurface = null
        }
    }

    /**
     * Recreate the EGLSurface, using the new EglBase.  The caller should have already
     * freed the old EGLSurface with releaseEglSurface().
     *
     *
     * This is useful when we want to update the EGLSurface associated with a Surface.
     * For example, if we want to share with a different EGLContext, which can only
     * be done by tearing down and recreating the context.  (That's handled by the caller;
     * this just creates a new EGLSurface for the Surface we were handed earlier.)
     *
     *
     * If the previous EGLSurface isn't fully destroyed, e.g. it's still current on a
     * context somewhere, the create call will fail with complaints from the Surface
     * about already being connected.
     */
    fun recreate(newEglCore: EglCore?) {
        if (mSurface == null) {
            throw RuntimeException("not yet implemented for SurfaceTexture")
        } else {
            mEglCore = newEglCore!! // switch to new context
            createWindowSurface(mSurface) // create new surface
        }
    }
}