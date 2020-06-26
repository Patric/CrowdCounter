package com.example.crowdcounter


import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.face.FirebaseVisionFace
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetector
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions
import kotlinx.android.synthetic.main.activity_fullscreen.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList
import kotlin.math.sign
import kotlin.system.exitProcess


/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
class FullscreenActivity : AppCompatActivity() {
    private lateinit var fullscreenContent: TextView
    private lateinit var fullscreenContentControls: LinearLayout
    private var mFirebaseAnalytics: FirebaseAnalytics? = null
    private val hideHandler = Handler()

    lateinit var numberOfFaces: TextView
    lateinit var cameraLayout: FrameLayout
   // lateinit var myGLSurfaceView: GLSurfaceView
    lateinit var myImageView: ImageView

    val MAX_PREVIEW_WIDTH = 640
    val MAX_PREVIEW_HEIGHT = 460

    lateinit var myCameraId:String
    var myCaptureSession:CameraCaptureSession? = null
    var myCameraDevice:CameraDevice? = null
    var myBackgroundThread:HandlerThread? = null
    var myBackgroundHandler: Handler? = null
    var myImageReader:ImageReader? = null
    lateinit var myPreviewRequestBuilder: CaptureRequest.Builder
    lateinit var myPreviewRequest: CaptureRequest
    var myCameraOpenCloseLock: Semaphore = Semaphore(1)
    val REQUEST_CAMERA_PERMISSION = 1

    var buttonClicked: Boolean = false



    @SuppressLint("InlinedApi")
    private val hidePart2Runnable = Runnable {
        // Delayed removal of status and navigation bar

        // Note that some of these constants are new as of API 16 (Jelly Bean)
        // and API 19 (KitKat). It is safe to use them, as they are inlined
        // at compile-time and do nothing on earlier devices.
        fullscreenContent.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LOW_PROFILE or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }
    private val showPart2Runnable = Runnable {
        // Delayed display of UI elements
        supportActionBar?.show()
        fullscreenContentControls.visibility = View.VISIBLE

    }
    private var isFullscreen: Boolean = false

    private val hideRunnable = Runnable { hide() }

    /**
     * Touch listener to use for in-layout UI controls to delay hiding the
     * system UI. This is to prevent the jarring behavior of controls going away
     * while interacting with activity UI.
     */
    private val delayHideTouchListener = View.OnTouchListener { view, motionEvent ->
        when (motionEvent.action) {
            MotionEvent.ACTION_DOWN -> if (AUTO_HIDE) {
                delayedHide(AUTO_HIDE_DELAY_MILLIS)
            }
            MotionEvent.ACTION_UP -> view.performClick()
            else -> {
            }
        }
        false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);

        setContentView(R.layout.activity_fullscreen)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        isFullscreen = true

        // Set up the user interaction to manually show or hide the system UI.
        fullscreenContent = findViewById(R.id.fullscreen_content)
        fullscreenContent.setOnClickListener { toggle() }

        fullscreenContentControls = findViewById(R.id.fullscreen_content_controls)

        myImageView = findViewById(R.id.myImageView)

        // Upon interacting with UI controls, delay any scheduled hide()
        // operations to prevent the jarring behavior of controls going away
        // while interacting with the UI.
        findViewById<Button>(R.id.photo_button).setOnTouchListener(delayHideTouchListener)

        cameraLayout = findViewById(R.id.CameraLayout)
        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this)

        numberOfFaces = findViewById(R.id.editTextNumber)

        photo_button.setOnClickListener(View.OnClickListener {
            buttonClicked = true
        })


    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        // Trigger the initial hide() shortly after the activity has been
        // created, to briefly hint to the user that UI controls
        // are available.
        delayedHide(100)
    }

    private fun toggle() {
        if (isFullscreen) {
            hide()
        } else {
           // show()
        }
    }

    private fun hide() {
        // Hide UI first
        supportActionBar?.hide()
        fullscreenContentControls.visibility = View.GONE
        isFullscreen = false

        // Schedule a runnable to remove the status and navigation bar after a delay
        hideHandler.removeCallbacks(showPart2Runnable)
        hideHandler.postDelayed(hidePart2Runnable, UI_ANIMATION_DELAY.toLong())
    }

    private fun show() {
        // Show the system bar
        fullscreenContent.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        isFullscreen = true

        // Schedule a runnable to display UI elements after a delay
        hideHandler.removeCallbacks(hidePart2Runnable)
        hideHandler.postDelayed(showPart2Runnable, UI_ANIMATION_DELAY.toLong())
    }

    /**
     * Schedules a call to hide() in [delayMillis], canceling any
     * previously scheduled calls.
     */
    private fun delayedHide(delayMillis: Int) {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, delayMillis.toLong())
    }

    companion object {
        var GLTextureHandle = IntArray(1)
        lateinit var bitmapBuffer: Bitmap
        /**
         * Whether or not the system UI should be auto-hidden after
         * [AUTO_HIDE_DELAY_MILLIS] milliseconds.
         */
        private const val AUTO_HIDE = true

        /**
         * If [AUTO_HIDE] is set, the number of milliseconds to wait after
         * user interaction before hiding the system UI.
         */
        private const val AUTO_HIDE_DELAY_MILLIS = 3000

        /**
         * Some older devices needs a small delay between UI widget updates
         * and a change of the status and navigation bar.
         */
        private const val UI_ANIMATION_DELAY = 300
    }


    class ImageConverter {

        private val TAG = ImageConverter::class.java.simpleName
        //converting bitmap from JPEG to ARGB8888 format
        fun JPEGtoARGB8888(input: Bitmap): Bitmap{
            var output: Bitmap? = null
            var size: Int = input.getWidth() * input.getHeight()
            val pixels = IntArray(size)
            input.getPixels(pixels,0,input.getWidth(),0,0,input.getWidth(),input.getHeight())
            output = Bitmap.createBitmap(input.getWidth(),input.getHeight(), Bitmap.Config.ARGB_8888)
            output.setPixels(pixels, 0, output.getWidth(), 0, 0, output.getWidth(), output.getHeight())
            return output // ARGB_8888 formated bitmap

        }




    }

    interface OnImageReadyListener {
        // Later this method needs to be overwritten
        fun getImage(image: Bitmap?)

    }












//    class MyGLRenderer: GLSurfaceView.Renderer {
//
//        override fun onSurfaceCreated(unused: GL10, config: EGLConfig){
//            //Set the background frame colour
//            GLES20.glGenTextures(1, GLTextureHandle, 0)
//            GLES20.glEnable(GL10.GL_TEXTURE_2D)
//           // GLES20.glBindTexture(GL10.GL_TEXTURE_2D, );
//            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
//        }
//
//        override fun onDrawFrame(unused: GL10?) {
//            //Redraw background color
//            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
//
//
//        }
//
//        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
//            GLES20.glViewport(0,0,width, height)
//        }
//
//
//    }
//
//
//
//    class MyGLSurfaceView(context: Context) : GLSurfaceView(context){
//
//        private val renderer: MyGLRenderer
//        init{
//            //Creating openGL ES 2.0 context
//            setEGLContextClientVersion(2)
//
//            renderer = MyGLRenderer()
//
//            //set the Renderer to draw on the GLSurfaceView
//            setRenderer(renderer)
//
//            //Render view only when there is change in drawing data
//            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
//
//        }
//
//
//    }




//    private static byte[] YUV_420_888_data(Image image) {
//        final int imageWidth = image.getWidth();
//        final int imageHeight = image.getHeight();
//        final Image.Plane[] planes = image.getPlanes();
//        byte[] data = new byte[imageWidth * imageHeight *
//                ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8];
//        int offset = 0;
//
//        for (int plane = 0; plane < planes.length; ++plane) {
//            final ByteBuffer buffer = planes[plane].getBuffer();
//            final int rowStride = planes[plane].getRowStride();
//            // Experimentally, U and V planes have |pixelStride| = 2, which
//            // essentially means they are packed.
//            final int pixelStride = planes[plane].getPixelStride();
//            final int planeWidth = (plane == 0) ? imageWidth : imageWidth / 2;
//            final int planeHeight = (plane == 0) ? imageHeight : imageHeight / 2;
//            if (pixelStride == 1 && rowStride == planeWidth) {
//                // Copy whole plane from buffer into |data| at once.
//                buffer.get(data, offset, planeWidth * planeHeight);
//                offset += planeWidth * planeHeight;
//            } else {
//                // Copy pixels one by one respecting pixelStride and rowStride.
//                byte[] rowData = new byte[rowStride];
//                for (int row = 0; row < planeHeight - 1; ++row) {
//                    buffer.get(rowData, 0, rowStride);
//                    for (int col = 0; col < planeWidth; ++col) {
//                    data[offset++] = rowData[col * pixelStride];
//                }
//                }
//                // Last row is special in some devices and may not contain the full
//                // |rowStride| bytes of data.
//                // See http://developer.android.com/reference/android/media/Image.Plane.html#getBuffer()
//                buffer.get(rowData, 0, Math.min(rowStride, buffer.remaining()));
//                for (int col = 0; col < planeWidth; ++col) {
//                    data[offset++] = rowData[col * pixelStride];
//                }
//            }
//        }
//
//        return data;


//    class MSurface(context: Context?) : SurfaceView(context),
//        SurfaceHolder.Callback {
//        protected fun onDraw(canvas: Canvas?) {
//            super.onDraw(canvas)
//            val icon = BitmapFactory.decodeResource(getResources(), R.drawable.icon)
//            canvas!!.drawColor(Color.BLACK)
//            canvas.drawBitmap(icon, 10, 10, Paint())
//        }
//
//        fun surfaceChanged(
//            holder: SurfaceHolder?,
//            format: Int,
//            width: Int,
//            height: Int
//        ) {
//            // TODO Auto-generated method stub
//        }
//
//        fun surfaceCreated(holder: SurfaceHolder) {
//            var canvas: Canvas? = null
//            try {
//                canvas = holder.lockCanvas(null)
//                synchronized(holder) { onDraw(canvas) }
//            } catch (e: Exception) {
//                e.printStackTrace()
//            } finally {
//                if (canvas != null) {
//                    holder.unlockCanvasAndPost(canvas)
//                }
//            }
//        }
//
//        fun surfaceDestroyed(holder: SurfaceHolder?) {
//            // TODO Auto-generated method stub
//        }
//
//        init {
//            getHolder().addCallback(this)
//        }
//    }






    private var myOnImageAvailableListener: OnImageAvailableListener = object: OnImageAvailableListener{



        override fun onImageAvailable(reader: ImageReader?) {


                val readImage: Image? = reader?.acquireLatestImage()

                val bBuffer: ByteBuffer = readImage?.getPlanes()?.get(0)!!.getBuffer()
                bBuffer.rewind()

                val buffer = ByteArray(bBuffer.remaining())


                readImage?.getPlanes().get(0).getBuffer().get(buffer)
                val bitmap = BitmapFactory.decodeByteArray(buffer, 0, buffer.size)
                val matrix = Matrix()

                matrix.postRotate(90F)



                var rotatedBitmap = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    true
                )
                var bitmapConfig = bitmap.config
                if(bitmapConfig == null) {
                    bitmapConfig = android.graphics.Bitmap.Config.ARGB_8888;
                    rotatedBitmap = rotatedBitmap.copy(bitmapConfig, true)
                }
                this@FullscreenActivity.runOnUiThread(java.lang.Runnable {
                    //myImageView.setRotation(90F)

                    val options: FirebaseVisionFaceDetectorOptions =
                        FirebaseVisionFaceDetectorOptions.Builder()
                            .setPerformanceMode(FirebaseVisionFaceDetectorOptions.FAST)
                            .setLandmarkMode(FirebaseVisionFaceDetectorOptions.ALL_LANDMARKS)
                            .setClassificationMode(FirebaseVisionFaceDetectorOptions.FAST)
                            .setMinFaceSize((0.2F))
                            .build()
//                    val metadata =
//                        FirebaseVisionImageMetadata.Builder()
//                            .setWidth(640) // 480x360 is typically sufficient for
//                            .setHeight(460) // image recognition
//                            .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_YV12)
//                            .setRotation(90)
//                            .build()

                    if(buttonClicked == true){
                        buttonClicked = false
                        val image: FirebaseVisionImage = FirebaseVisionImage.fromBitmap(rotatedBitmap)
                        val detector: FirebaseVisionFaceDetector = FirebaseVision.getInstance()
                            .getVisionFaceDetector(options)
                        val result: Task<List<FirebaseVisionFace>> = detector.detectInImage(image)
                            .addOnSuccessListener(
                                object : OnSuccessListener<List<FirebaseVisionFace?>?> {
                                    override fun onSuccess(faces: List<FirebaseVisionFace?>?) {
                                        // Task completed successfully
                                        // ...
                                        //println("Success")
                                        //println("Number of faces: ${faces!!.count()}")
                                        numberOfFaces.setText("Number of faces: ${faces!!.count()}")
                                        for (face in faces!!) {
                                            val bounds = face?.boundingBox
                                            var p: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
                                            var canvas: Canvas = Canvas(rotatedBitmap)
                                            p.setColor(Color.rgb(0, 255, 0))
                                            p.setTextSize(10F)
                                            var rect: Rect = Rect(bounds)
                                            canvas.drawRect(rect, p)

                                        }
                                        myImageView.setImageBitmap(rotatedBitmap)
                                        //canvas.drawText(face!!.trackingId, toFloat(rect.left), toFloat(rect.top), p)

//                                    //println(bounds)
//                                    val rotY =
//                                        face?.headEulerAngleY // Head is rotated to the right rotY degrees
//                                    val rotZ =
//                                        face?.headEulerAngleZ // Head is tilted sideways rotZ degrees
//
//                                    // If landmark detection was enabled (mouth, ears, eyes, cheeks, and
//                                    // nose available):
//                                    val leftEar: FirebaseVisionFaceLandmark? =
//                                        face?.getLandmark(FirebaseVisionFaceLandmark.LEFT_EAR)
//                                    if (leftEar != null) {
//                                        val leftEarPos: FirebaseVisionPoint = leftEar.getPosition()
//                                    }
//
//                                    // If contour detection was enabled:
//                                    val leftEyeContour: List<FirebaseVisionPoint> =
//                                        face?.getContour(FirebaseVisionFaceContour.LEFT_EYE)!!.points
//                                    val upperLipBottomContour: List<FirebaseVisionPoint> =
//                                        face?.getContour(FirebaseVisionFaceContour.UPPER_LIP_BOTTOM)
//                                            .points
//
//                                    // If classification was enabled:
//                                    if (face.smilingProbability != FirebaseVisionFace.UNCOMPUTED_PROBABILITY) {
//                                        val smileProb = face?.smilingProbability
//                                    }
//                                    if (face.rightEyeOpenProbability != FirebaseVisionFace.UNCOMPUTED_PROBABILITY) {
//                                        val rightEyeOpenProb =
//                                            face.rightEyeOpenProbability
//                                    }
//
//                                    // If face tracking was enabled:
//                                    if (face.trackingId != FirebaseVisionFace.INVALID_ID) {
//                                        val id = face.trackingId
//                                        //println(id)
//                                    }
//                                }
                                    }
                                })
                            .addOnFailureListener(
                                object : OnFailureListener {
                                    override fun onFailure(p0: java.lang.Exception) {
                                        println("Failure")

                                    }
                                })

                        //myImageView.setImageBitmap(rotatedBitmap)
                    }

                })
            //}
            //catch(e: Exception){
                //println("Image analysis failed")
            //}
                //if(onImageReadyListener != null)
                // onImageReadyListener?.getImage(bitmap);

                readImage?.close()


        }
    }




    override fun onResume() {
        super.onResume()
        startBackgroungThread()
        openCamera(MAX_PREVIEW_WIDTH,MAX_PREVIEW_HEIGHT)
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun openCamera(width: Int,height: Int){
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            requestCameraPermission()
            return
        }

        setUpCameraOutputs(width, height)
        var cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try{
            if (!myCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)){
                throw RuntimeException("Time out waiting to lock camera opening")
            }
            cameraManager.openCamera(myCameraId,cameraCDSC,myBackgroundHandler)
        }catch (e: CameraAccessException){
            e.printStackTrace()
        }catch (e:InterruptedException){
            throw RuntimeException("Interrupted while trying to lock camera opening")
        }
    }

    //1080 x 1965 put here
    private fun setUpCameraOutputs(width:Int,height:Int){
        var cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try{
            for(cameraId in cameraManager.cameraIdList){
                var characteristics = cameraManager.getCameraCharacteristics(cameraId)
                var orientation = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (orientation != null && orientation == CameraCharacteristics.LENS_FACING_FRONT){
                    continue
                }
                myCameraId = cameraId
                myImageReader = ImageReader.newInstance(MAX_PREVIEW_WIDTH,MAX_PREVIEW_HEIGHT,
                    ImageFormat.JPEG,2)
                myImageReader?.setOnImageAvailableListener(myOnImageAvailableListener,myBackgroundHandler)
                return
            }
        }catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }


   

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createCameraPreviewSession(){
        try {
            myPreviewRequestBuilder =
                myCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            //myPreviewRequestBuilder.addTarget(surface!!)
            myPreviewRequestBuilder.addTarget(myImageReader!!.surface)


            //myPreviewRequestBuilder.addTarget(surface)
            //myPreviewRequestBuilder.set(CaptureRequest.JPEG_QUALITY, 90.toByte())
            var cameraCSSC = object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    stopBackgroundThread()
                    finish()
                }

                override fun onConfigured(session: CameraCaptureSession) {
                    if (null == myCameraDevice) {
                        return
                    }
                    myCaptureSession = session
                    try {
                        myPreviewRequestBuilder.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )
                        myPreviewRequest = myPreviewRequestBuilder.build()
                        myCaptureSession!!.setRepeatingRequest(
                            myPreviewRequest,
                            null,
                            myBackgroundHandler
                        )
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }

            }

            //Image modifications
            myCameraDevice!!.createCaptureSession(Arrays.asList(myImageReader?.surface!!),cameraCSSC, myBackgroundHandler)
            //println("AFTER: ${surface}, ${myImageReader?.surface!!}")



        }catch (e: CameraAccessException){
            e.printStackTrace()
        }
    }

    private fun requestCameraPermission(){
        var permissionList:MutableList<String> = ArrayList()
        permissionList.add(Manifest.permission.CAMERA)
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this,
                permissionList.toTypedArray(), REQUEST_CAMERA_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if(requestCode == REQUEST_CAMERA_PERMISSION){
            if(grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED)
                exitProcess(1)
        }
    }

    private fun startBackgroungThread() {
        myBackgroundThread = HandlerThread("CameraBackground")
        myBackgroundThread!!.start()
        myBackgroundHandler = Handler(myBackgroundThread!!.looper)

    }

    private fun stopBackgroundThread() {
        myBackgroundThread?.quitSafely()
        try {
            myBackgroundThread?.join()
            myBackgroundThread = null
            myBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }



    var cameraCDSC = object: CameraDevice.StateCallback(){
        @RequiresApi(Build.VERSION_CODES.P)
        override fun onOpened(camera: CameraDevice) {
            myCameraOpenCloseLock.release()
            myCameraDevice = camera
            println("Creating Preview session")
            this@FullscreenActivity.createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            myCameraOpenCloseLock.release()
            camera.close()
            myCameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            myCameraOpenCloseLock.release()
            camera.close()
            myCameraDevice = null
            finish()
        }

    }

    private fun closeCamera(){
        try{
            myCameraOpenCloseLock.acquire()
            if (null != myCaptureSession){
                myCaptureSession!!.close()
                myCaptureSession = null
            }
            if (null != myCameraDevice){
                myCameraDevice!!.close()
                myCameraDevice = null
            }
            if (null != myImageReader){
                myImageReader?.close()
                myImageReader = null
            }
        }catch(e:InterruptedException){
            throw RuntimeException()
        } finally{
            myCameraOpenCloseLock.release()
        }
    }



}






