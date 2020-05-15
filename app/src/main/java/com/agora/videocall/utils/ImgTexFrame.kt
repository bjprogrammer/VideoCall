package com.agora.videocall.utils

import android.opengl.Matrix
import java.util.*

class ImgTexFrame(var mFormat: ImgTexFormat?, textureId: Int, matrix: FloatArray?, ts: Long) : AVFrameBase() {
    @JvmField
    var mTextureId = NO_TEXTURE
    @JvmField
    val mTexMatrix: FloatArray
    override fun toString(): String {
        return "ImgTexFrame{" +
                "mFormat=" + mFormat +
                ", mTextureId=" + mTextureId +
                ", mTexMatrix=" + Arrays.toString(mTexMatrix) +
                '}'
    }

    companion object {
        const val NO_TEXTURE = -1
        val DEFAULT_MATRIX = FloatArray(16)
    }

    init {
        mTextureId = textureId
        pts = ts
        dts = ts
        if (matrix != null && matrix.size == 16) {
            mTexMatrix = matrix
        } else {
            mTexMatrix = DEFAULT_MATRIX
            Matrix.setIdentityM(mTexMatrix, 0)
        }
    }
}