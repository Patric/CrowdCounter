package com.example.crowdcounter


import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
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

    private val hideHandler = Handler()

    lateinit var cameraLayout: FrameLayout
    lateinit var myTextureView: TextureView
    lateinit var myGLSurfaceView: GLSurfaceView


    val MAX_PREVIEW_WIDTH = 1920
    val MAX_PREVIEW_HEIGHT = 1080

    lateinit var myCameraId:String


    var myCaptureSession:CameraCaptureSession? = null
    var myPreviewSize:Size? = null
    var myCameraDevice:CameraDevice? = null
    var myBackgroundThread:HandlerThread? = null
    var myBackgroundHandler: Handler? = null
    var myImageReader:ImageReader? = null
    lateinit var myPreviewRequestBuilder: CaptureRequest.Builder
    lateinit var myPreviewRequest: CaptureRequest
    var myCameraOpenCloseLock: Semaphore = Semaphore(1)
    val REQUEST_CAMERA_PERMISSION = 1



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

        setContentView(R.layout.activity_fullscreen)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        isFullscreen = true

        // Set up the user interaction to manually show or hide the system UI.
        fullscreenContent = findViewById(R.id.fullscreen_content)
        fullscreenContent.setOnClickListener { toggle() }

        fullscreenContentControls = findViewById(R.id.fullscreen_content_controls)


        // Upon interacting with UI controls, delay any scheduled hide()
        // operations to prevent the jarring behavior of controls going away
        // while interacting with the UI.
        findViewById<Button>(R.id.photo_button).setOnTouchListener(delayHideTouchListener)

        myTextureView = findViewById(R.id.camTextureView)
        cameraLayout = findViewById(R.id.CameraLayout)

        myGLSurfaceView = MyGLSurfaceView(this)
       // myGLSurfaceView = findViewById(R.id.myGLSurfaceView);

        setContentView(myGLSurfaceView)

        //cameraLayout = findViewById(R.id.CameraLayout)

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

    var mySurfaceTextureListener: TextureView.SurfaceTextureListener = object: TextureView.SurfaceTextureListener{
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
            configureTransform(width,height)

        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
            if (myTextureView != null){

              //  myTextureView.surfaceTexture.releaseTexImage()

            }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
            return true
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            openCamera(width,height)


        }

    }

    interface OnImageReadyListener {
        // Later this method needs to be overwritten
        fun getImage(image: Bitmap?)

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

//        private fun BitmapToMat(bitmap: Bitmap): Mat? {
//            val bitmapARGB8888 = JPEGtoARGB8888(bitmap)
//            val imageMat = Mat()
//            Utils.bitmapToMat(bitmapARGB8888, imageMat)
//            return imageMat
//        }


    }




//    var onImageReadyListener = onImageReadyListener() {
//
//    }





    class MyGLRenderer: GLSurfaceView.Renderer {

        override fun onSurfaceCreated(unused: GL10, config: EGLConfig){
            //Set the background frame colour
            GLES20.glGenTextures(1, GLTextureHandle, 0)
            GLES20.glEnable(GL10.GL_TEXTURE_2D)
           // GLES20.glBindTexture(GL10.GL_TEXTURE_2D, );
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        }

        override fun onDrawFrame(unused: GL10?) {
            //Redraw background color
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        }

        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0,0,width, height)
        }


    }



    class MyGLSurfaceView(context: Context) : GLSurfaceView(context){

        private val renderer: MyGLRenderer
        init{
            //Creating openGL ES 2.0 context
            setEGLContextClientVersion(2)

            renderer = MyGLRenderer()

            //set the Renderer to draw on the GLSurfaceView
            setRenderer(renderer)

            //Render view only when there is change in drawing data
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        }


    }

    private var myOnImageAvailableListener: OnImageAvailableListener = object: OnImageAvailableListener{

        override fun onImageAvailable(reader: ImageReader?) {
            val readImage: Image? = reader?.acquireNextImage()
           // var rect = Rect(0,0, 300, 300)
            //readImage?.setCropRect(rect)
            val bBuffer: ByteBuffer = readImage?.getPlanes()?.get(0)!!.getBuffer()
            bBuffer.rewind()

            val buffer = ByteArray(bBuffer.remaining())
            readImage?.getPlanes().get(0).getBuffer().get(buffer)
            val bitmap = BitmapFactory.decodeByteArray(buffer, 0, buffer.size)
           // bitmapBuffer = bitmap
//            if(onImageReadyListener != null)
//                onImageReadyListener?.getImage(bitmap);

            readImage?.close()


        }
    }
//        OnImageAvailableListener { reader ->
//            Log.d(
//                FragmentActivity.TAG,
//                "The onImageAvailable thread id: " + Thread.currentThread().id
//            )
//            val readImage: Image = reader.acquireLatestImage()
//            readImage.close()
//        }


//    var mySurfaceTextureListener: TextureView.SurfaceTextureListener = object: TextureView.SurfaceTextureListener{
//        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
//            configureTransform(width,height)
//        }
//
//        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
//
//
//        }
//
//        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
//            return true
//        }
//
//        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
//            openCamera(width,height)
//
//
//        }
//
//    }



    override fun onResume() {
        super.onResume()
        startBackgroungThread()


        if(myTextureView?.isAvailable == true){
            openCamera(myTextureView!!.width,myTextureView!!.height)
        }else{
            myTextureView!!.surfaceTextureListener = mySurfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
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

                var map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                //println(map)
                if (map == null){
                    continue
                }

                var sizesList:MutableList<Size> = ArrayList()
                sizesList.addAll(map.getOutputSizes(ImageFormat.YUV_420_888))

                var largestPreviewSize: Size = Collections.max(sizesList, compareSizesByArea())

                myImageReader = ImageReader.newInstance(largestPreviewSize.width,largestPreviewSize.height,
                    ImageFormat.YUV_420_888,2)
                myImageReader?.setOnImageAvailableListener(myOnImageAvailableListener,myBackgroundHandler)
                var displaySize = Point()

                //displaySize(1080, 2131)
                windowManager.defaultDisplay.getSize(displaySize)

                var rotatedPreviewWidth = width
                var rotatedPreviewHeight = height
                var maxPreviewWidth = displaySize?.y
                var maxPreviewHeight = displaySize?.x

                if (maxPreviewWidth != null) {
                    if (maxPreviewWidth > MAX_PREVIEW_WIDTH){
                        maxPreviewWidth = MAX_PREVIEW_WIDTH
                    }
                }

                if (maxPreviewHeight != null) {
                    if (maxPreviewHeight > MAX_PREVIEW_HEIGHT){
                        maxPreviewHeight = MAX_PREVIEW_HEIGHT
                    }
                }
                myPreviewSize = chooseOptimalSize(sizesList.toTypedArray(), rotatedPreviewWidth,rotatedPreviewHeight,maxPreviewWidth,maxPreviewHeight)//,largestPreviewSize)
               // println("myPreviewSize: $myPreviewSize")


                myCameraId = cameraId
                return
            }
        }catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }


   

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createCameraPreviewSession(){
        try {
            var texture = myTextureView!!.surfaceTexture
            texture.setDefaultBufferSize(myPreviewSize!!.getWidth(), myPreviewSize!!.getHeight())
            var surface = Surface(texture)
            //val mImageSurface: Surface? = myImageReader?.getSurface()

            myPreviewRequestBuilder =
                myCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            //myPreviewRequestBuilder.addTarget(surface!!)
            myPreviewRequestBuilder.addTarget(myImageReader!!.surface)


            //myPreviewRequestBuilder.addTarget(surface)
            //myPreviewRequestBuilder.set(CaptureRequest.JPEG_QUALITY, 90.toByte())
            var cameraCSSC = object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(session: CameraCaptureSession) {

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
            myCameraDevice!!.createCaptureSession(Arrays.asList(surface, myImageReader?.surface!!),cameraCSSC, null)
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
        }else{
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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

    private fun chooseOptimalSize(choices:Array<Size>, textureViewWidth:Int, textureViewHeight:Int,
                                  maxWidth:Int?, maxHeight:Int?//, aspectRatio: Size
    ): Size
    {
        var bigEnough:MutableList<Size> = ArrayList()
        var notBigEnough:MutableList<Size> = ArrayList()
//        var w = aspectRatio.width
//        var h = aspectRatio.height
        for (option in choices){
            if (option.width <= maxWidth!! && option.height <= maxHeight!!){
                if (option.width >= textureViewWidth && option.height >= textureViewHeight){
                    bigEnough.add(option)
                }
                else{
                    notBigEnough.add(option)
                }
            }
        }

        if(bigEnough.size > 0){
            return Collections.max(bigEnough, compareSizesByArea())
        }else if(notBigEnough.size > 0) {
            return Collections.max(notBigEnough, compareSizesByArea())
        } else {
            return choices[0]
        }

    }

    private fun configureTransform(viewWidth:Int, viewHeight:Int){
        if (null == myTextureView || null == myPreviewSize){
            return
        }

        var rotation =windowManager.defaultDisplay.rotation
        var matrix: Matrix? = Matrix()
        var viewRect = RectF(0F,0F,viewWidth.toFloat(),viewHeight.toFloat())
        var bufferRect = RectF(0F,0F,myPreviewSize!!.height.toFloat(),myPreviewSize!!.width.toFloat())
        var centerX = viewRect.centerX()
        var centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation){
            bufferRect.offset(centerX- bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix!!.setRectToRect(viewRect,bufferRect, Matrix.ScaleToFit.FILL)
            //Use max to set it to fullscreen. Use min to not scale resolution
            var scale = Math.max((viewHeight/myPreviewSize!!.width.toDouble()).toFloat(),(viewWidth/myPreviewSize!!.height.toDouble()).toFloat())

            matrix.postScale(scale,scale,centerX,centerY)
            matrix.postRotate(90*(rotation-2).toFloat(),centerX,centerY)
        }
        else if (Surface.ROTATION_180 == rotation){
            matrix!!.postRotate(180.toFloat(),centerX,centerY)
        }
        else if (Surface.ROTATION_0 == rotation)
        {
            bufferRect.offset(centerX- bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix!!.setRectToRect(viewRect,bufferRect, Matrix.ScaleToFit.FILL)

            var scale = Math.max((viewHeight/myPreviewSize!!.width.toDouble()).toFloat(),(viewWidth/myPreviewSize!!.height.toDouble()).toFloat())
            matrix.postScale(scale,scale,centerX,centerY)

        }
        //println("matrix: $matrix")
        myTextureView!!.setTransform(matrix)
    }

    class compareSizesByArea: Comparator<Size>{
        override fun compare(lhs: Size?, rhs: Size?): Int {
            var i = sign(((lhs!!.width * lhs.height).toLong() - (rhs!!.width * rhs.height).toLong()).toDouble())
            return i.toInt()
        }
    }

    private fun openCamera(width: Int,height: Int){
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            requestCameraPermission()
            return
        }

        setUpCameraOutputs(width, height)
        configureTransform(width,height)
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



}






