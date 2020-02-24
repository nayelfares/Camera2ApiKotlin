package com.media.camera2apikotlin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private var mTextureView: TextureView? = null
    private val mSurfaceTextureListener: TextureView.SurfaceTextureListener = object :
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
    private var mCameraDevice: CameraDevice? = null
    private val mCameraDeviceStateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            mCameraDevice = camera
            mMediaRecorder = MediaRecorder()
            if (mIsRecording) {
                try {
                    createVideoFileName()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
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

    lateinit var mMediaRecorder: MediaRecorder
    private var mTotalRotation = 0
    private var mPreviewCaptureSession: CameraCaptureSession? = null
    private var mRecordCaptureSession: CameraCaptureSession? = null
    lateinit var mCaptureRequestBuilder: CaptureRequest.Builder
    private var mRecordImageButton: ImageButton? = null
    private var mIsRecording = false
    private var mVideoFolder: File? = null
    private var mVideoFileName=""
    private var mImageFolder: File? = null

    companion object {
        private const val REQUEST_CAMERA_PERMISSION_RESULT = 0
        private const val REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT = 1
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createVideoFolder()
        createImageFolder()
        mTextureView = findViewById<View>(R.id.textureView) as TextureView
        mRecordImageButton = findViewById<View>(R.id.videoOnlineImageButton) as ImageButton
        mRecordImageButton!!.setOnClickListener {
            if (mIsRecording ) {
                mIsRecording = false
                mRecordImageButton!!.setImageResource(R.mipmap.btn_video_online)
                startPreview()
                mMediaRecorder.stop()
                mMediaRecorder.reset()
                val mediaStoreUpdateIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                mediaStoreUpdateIntent.data = Uri.fromFile(File(mVideoFileName))
                sendBroadcast(mediaStoreUpdateIntent)
            } else {
                mIsRecording = true
                mRecordImageButton!!.setImageResource(R.mipmap.btn_video_busy)
                checkWriteStoragePermission()
            }
        }
        mRecordImageButton!!.setOnLongClickListener {
            mRecordImageButton!!.setImageResource(R.mipmap.btn_timelapse)
            checkWriteStoragePermission()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (mTextureView!!.isAvailable) {
            setupCamera(mTextureView!!.width, mTextureView!!.height)
            connectCamera()
        } else {
            mTextureView!!.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION_RESULT) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(applicationContext,
                    "Application will not run without camera services", Toast.LENGTH_SHORT).show()
            }
            if (grantResults[1] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(applicationContext,
                    "Application will not have audio on record", Toast.LENGTH_SHORT).show()
            }
        }
        if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (mIsRecording) {
                    mIsRecording = true
                    mRecordImageButton!!.setImageResource(R.mipmap.btn_video_busy)
                }
                Toast.makeText(this,
                    "Permission successfully granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this,
                    "App needs to save video to run", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocas: Boolean) {
        super.onWindowFocusChanged(hasFocas)
        val decorView = window.decorView
        if (hasFocas) {
            decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        }
    }

    private fun setupCamera(width: Int, height: Int) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK) {
                    continue
                }
                val map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val deviceOrientation = windowManager.defaultDisplay.rotation
                mTotalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation)
                val swapRotation = mTotalRotation == 90 || mTotalRotation == 270
                var rotatedWidth = width
                var rotatedHeight = height
                if (swapRotation) {
                    rotatedWidth = height
                    rotatedHeight = width
                }
                mPreviewSize = chooseOptimalSize(map!!.getOutputSizes(SurfaceTexture::class.java), rotatedWidth, rotatedHeight)
                mVideoSize = chooseOptimalSize(map.getOutputSizes(MediaRecorder::class.java), rotatedWidth, rotatedHeight)
                mImageSize = chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG), rotatedWidth, rotatedHeight)
                mImageReader = ImageReader.newInstance(mImageSize!!.width, mImageSize!!.height, ImageFormat.JPEG, 1)
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler)
                mCameraId = cameraId
                return
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun connectCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED) {
                    cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler)
                } else {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        Toast.makeText(this,
                            "Video app required access to camera", Toast.LENGTH_SHORT).show()
                    }
                    requestPermissions(arrayOf(
                        Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
                    ), REQUEST_CAMERA_PERMISSION_RESULT)
                }
            } else {
                cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun startRecord() {
        try {
            if (mIsRecording) {
                setupMediaRecorder()
            }
            val surfaceTexture = mTextureView!!.surfaceTexture
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

    private fun startPreview() {
        val surfaceTexture = mTextureView!!.surfaceTexture
        surfaceTexture.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
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

    private fun closeCamera() {
        if (mCameraDevice != null) {
            mCameraDevice!!.close()
            mCameraDevice = null
        }
        mMediaRecorder.release()

    }

    private fun startBackgroundThread() {
        mBackgroundHandlerThread = HandlerThread("Camera2VideoImage")
        mBackgroundHandlerThread!!.start()
        mBackgroundHandler = Handler(mBackgroundHandlerThread!!.looper)
    }

    private fun stopBackgroundThread() {
        mBackgroundHandlerThread!!.quitSafely()
        try {
            mBackgroundHandlerThread!!.join()
            mBackgroundHandlerThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun createVideoFolder() {
        val movieFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        mVideoFolder = File(movieFile, "camera2VideoImage")
        if (!mVideoFolder!!.exists()) {
            mVideoFolder!!.mkdirs()
        }
    }

    @Throws(IOException::class)
    private fun createVideoFileName(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val prepend = "VIDEO_" + timestamp + "_"
        val videoFile = File.createTempFile(prepend, ".mp4", mVideoFolder)
        mVideoFileName = videoFile.absolutePath
        return videoFile
    }

    private fun createImageFolder() {
        val imageFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        mImageFolder = File(imageFile, "camera2VideoImage")
        if (!mImageFolder!!.exists()) {
            mImageFolder!!.mkdirs()
        }
    }

    private fun checkWriteStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
                try {
                    createVideoFileName()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                if (mIsRecording) {
                    startRecord()
                    mMediaRecorder.start()
                }
            } else {
                if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    Toast.makeText(this, "app needs to be able to save videos", Toast.LENGTH_SHORT).show()
                }
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT)
            }
        } else {
            try {
                createVideoFileName()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            if (mIsRecording ) {
                startRecord()
                mMediaRecorder.start()
            }
        }
    }

    @Throws(IOException::class)
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

}
