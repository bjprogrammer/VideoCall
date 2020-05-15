package com.agora.videocall.utils

class ImgTexFormat(val mColorFormat: Int, val mWidth: Int, val mHeight: Int) {
    override fun toString(): String {
        return "ImgTexFormat{" +
                "mColorFormat=" + mColorFormat +
                ", mWidth=" + mWidth +
                ", mHeight=" + mHeight +
                '}'
    }

    companion object {
        const val COLOR_FORMAT_EXTERNAL_OES = 3
    }

}