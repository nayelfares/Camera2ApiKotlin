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

    private var mRecordImageButton: ImageButton? = null
    private var mIsRecording = false
    private var mVideoFolder: File? = null
    private var mVideoFileName=""
    private var mImageFolder: File? = null

    companion object {
        private const val REQUEST_CAMERA_PERMISSION_RESULT = 0
        private const val REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT = 1
        private val ORIENTATIONS = SparseIntArray()


        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 0)
            ORIENTATIONS.append(Surface.ROTATION_90, 90)
            ORIENTATIONS.append(Surface.ROTATION_180, 180)
            ORIENTATIONS.append(Surface.ROTATION_270, 270)
        }
    }

    lateinit var cameraHolder:CameraHolder
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createVideoFolder()
        createImageFolder()
        mTextureView = findViewById<View>(R.id.textureView) as TextureView
        cameraHolder=CameraHolder(this,mTextureView!!,mVideoFileName)
        mRecordImageButton = findViewById<View>(R.id.videoOnlineImageButton) as ImageButton
        mRecordImageButton!!.setOnClickListener {
            if (mIsRecording ) {
                mIsRecording = false
                mRecordImageButton!!.setImageResource(R.mipmap.btn_video_online)
                cameraHolder.startPreview()
                cameraHolder.mMediaRecorder.stop()
                cameraHolder.mMediaRecorder.reset()
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
        cameraHolder.startBackgroundThread()
        if (mTextureView!!.isAvailable) {
            cameraHolder.setupCamera(mTextureView!!.width, mTextureView!!.height)
            cameraHolder.connectCamera()
        } else {
            mTextureView!!.surfaceTextureListener = cameraHolder.mSurfaceTextureListener
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
        cameraHolder.closeCamera()
        cameraHolder.stopBackgroundThread()
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
                    cameraHolder.startRecord()
                    cameraHolder.mMediaRecorder.start()
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
                cameraHolder.startRecord()
                cameraHolder.mMediaRecorder.start()
            }
        }
    }

}
