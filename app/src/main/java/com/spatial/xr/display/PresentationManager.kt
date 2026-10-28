package com.spatial.xr.display

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager

class PresentationManager(
    private val context: Context,
    private val callback: PresentationSurfaceCallback
) {

    interface PresentationSurfaceCallback {
        fun onSurfaceCreated(holder: SurfaceHolder)
        fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int)
        fun onSurfaceDestroyed(holder: SurfaceHolder)
        fun onDismissed()
    }

    private var presentation: SpatialPresentation? = null

    fun attachDisplay(display: Display) {
        detachDisplay()
        presentation = SpatialPresentation(context, display, callback).apply {
            show()
        }
    }

    fun detachDisplay() {
        presentation?.dismiss()
        presentation = null
    }

    fun isAttached(): Boolean = presentation?.isShowing == true

    private class SpatialPresentation(
        context: Context,
        display: Display,
        private val callback: PresentationSurfaceCallback
    ) : Presentation(context, display), SurfaceHolder.Callback {

        private lateinit var surfaceView: SurfaceView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            surfaceView = SurfaceView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                holder.addCallback(this@SpatialPresentation)
            }
            setContentView(surfaceView)
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            callback.onSurfaceCreated(holder)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            callback.onSurfaceChanged(holder, format, width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            callback.onSurfaceDestroyed(holder)
        }

        override fun onStop() {
            super.onStop()
            callback.onDismissed()
        }
    }
}
