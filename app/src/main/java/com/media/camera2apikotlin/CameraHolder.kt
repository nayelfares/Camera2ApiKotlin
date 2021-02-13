package com.media.camera2apikotlin

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import java.io.File
import java.util.*

class CameraHolder(val context: Context,val mTextureView:TextureView,val mVideoFileName: String) {
    companion object {
        private val ORIENTATIONS = SparseIntArray()
        private fun sensorToDeviceRotation(cameraCharacteristics: CameraCharacteristics, deviceOrientation: Int): Int {
            var deviceOrientation = deviceOrientation
            val sensorOrienatation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            deviceOrientation = ORIENTATIONS[deviceOrientation]
            return (sensorOrienatation!!.plus(deviceOrientation + 360)) % 360
        }

        private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
            val bigEnough: MutableList<Size> = ArrayList()
            for (option in choices) {
                if (option.height == option.width * height / width && option.width >= width && option.height >= height) {
                    bigEnough.add(option)
                }
            }
            return if (bigEnough.size > 0) {
                Collections.min(bigEnough, CompareSizeByArea())
            } else {
                choices[0]
            }
        }

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 0)
            ORIENTATIONS.append(Surface.ROTATION_90, 90)
            ORIENTATIONS.append(Surface.ROTATION_180, 180)
            ORIENTATIONS.append(Surface.ROTATION_270, 270)
        }
    }
    private class CompareSizeByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int {
            return java.lang.Long.signum((lhs.width * lhs.height).toLong() -
                    (rhs.width * rhs.height).toLong())
        }
    }

    lateinit var mMediaRecorder: MediaRecorder
    private var mTotalRotation = 0
    private var mPreviewCaptureSession: CameraCaptureSession? = null
    private var mRecordCaptureSession: CameraCaptureSession? = null
    lateinit var mCaptureRequestBuilder: CaptureRequest.Builder
    private var mRecordImageButton: ImageButton? = null
    private var mIsRecording = false
    private var mVideoFolder: File? = null
    private var mImageFolder: File? = null
    private var mBackgroundHandlerThread: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null
    private var mCameraId = "1"
    private var mPreviewSize: Size? = null
    private var mVideoSize: Size? = null
    private var mImageSize: Size? = null
    lateinit var mImageReader: ImageReader
    private val mOnImageAvailableListener =
        ImageReader.OnImageAvailableListener { reader -> mBackgroundHandler!!.post(ImageSaver(reader.acquireLatestImage())) }
    private inner class ImageSaver(private val mImage: Image) : Runnable {
        override fun run() {
            val byteBuffer = mImage.planes[0].buffer
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer[bytes]
        }

    }
    fun setupCamera(width: Int, height: Int) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK) {
                    continue
                }
                val map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val deviceOrientation =(context as Activity). windowManager.defaultDisplay.rotation
                mTotalRotation =sensorToDeviceRotation(cameraCharacteristics, deviceOrientation)
                val swapRotation = mTotalRotation == 90 || mTotalRotation == 270
                var rotatedWidth = width
                var rotatedHeight = height
                if (swapRotation) {
                    rotatedWidth = height
                    rotatedHeight = width
                }
                mPreviewSize = chooseOptimalSize(
                    map!!.getOutputSizes(SurfaceTexture::class.java),
                    rotatedWidth,
                    rotatedHeight
                )
                mVideoSize = chooseOptimalSize(
                    map.getOutputSizes(MediaRecorder::class.java),
                    rotatedWidth,
                    rotatedHeight
                )
                mImageSize = chooseOptimalSize(
                    map.getOutputSizes(ImageFormat.JPEG),
                    rotatedWidth,
                    rotatedHeight
                )
                mImageReader = ImageReader.newInstance(mImageSize!!.width, mImageSize!!.height, ImageFormat.JPEG, 1)
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler)
                mCameraId = cameraId
                return
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

     var mCameraDevice: CameraDevice? = null
     val mCameraDeviceStateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            mCameraDevice = camera
            mMediaRecorder = MediaRecorder()
            if (mIsRecording) {
                startRecord()
                mMediaRecorder.start()
            } else {
                startPreview()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            mCameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            mCameraDevice = null
        }
    }

     fun connectCamera() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED) {
                    cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler)
                }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }


    fun startRecord() {
        try {
            if (mIsRecording) {
                setupMediaRecorder()
            }
            val surfaceTexture = mTextureView.surfaceTexture
            surfaceTexture.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
            val previewSurface = Surface(surfaceTexture)
            val recordSurface = mMediaRecorder.surface
            mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            mCaptureRequestBuilder.addTarget(previewSurface)
            mCaptureRequestBuilder.addTarget(recordSurface)
            mCameraDevice!!.createCaptureSession(
                Arrays.asList(previewSurface, recordSurface, mImageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        mRecordCaptureSession = session
                        try {
                            mRecordCaptureSession!!.setRepeatingRequest(
                                mCaptureRequestBuilder.build(), null, null
                            )
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

     fun startPreview() {
        val surfaceTexture = mTextureView.surfaceTexture
        surfaceTexture.setDefaultBufferSize(640, 640)
        val previewSurface = Surface(surfaceTexture)
        try {
            mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mCaptureRequestBuilder.addTarget(previewSurface)
            mCameraDevice!!.createCaptureSession(
                Arrays.asList(previewSurface, mImageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        mPreviewCaptureSession = session
                        try {
                            mPreviewCaptureSession!!.setRepeatingRequest(mCaptureRequestBuilder.build(),
                                null, mBackgroundHandler)
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    fun closeCamera() {
        if (mCameraDevice != null) {
            mCameraDevice!!.close()
            mCameraDevice = null
        }
        mMediaRecorder.release()

    }
    private fun setupMediaRecorder() {
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mMediaRecorder.setOutputFile(mVideoFileName)
        mMediaRecorder.setVideoEncodingBitRate(1000000)
        mMediaRecorder.setVideoFrameRate(30)
        mMediaRecorder.setVideoSize(mVideoSize!!.width, mVideoSize!!.height)
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mMediaRecorder.setOrientationHint(mTotalRotation)
        mMediaRecorder.prepare()
    }

     fun startBackgroundThread() {
        mBackgroundHandlerThread = HandlerThread("Camera2VideoImage")
        mBackgroundHandlerThread!!.start()
        mBackgroundHandler = Handler(mBackgroundHandlerThread!!.looper)
    }

     fun stopBackgroundThread() {
        mBackgroundHandlerThread!!.quitSafely()
        try {
            mBackgroundHandlerThread!!.join()
            mBackgroundHandlerThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    val mSurfaceTextureListener: TextureView.SurfaceTextureListener = object :
        TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            setupCamera(width, height)
            connectCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }
}