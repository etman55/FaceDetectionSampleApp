package com.example.atef.camerasimpleapp.Ui;


import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.hardware.Camera.Size;
import android.view.WindowManager;
import android.widget.Toast;

import com.example.atef.camerasimpleapp.CameraSource;
import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.FaceDetector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by atef on 1/24/17.
 */

public class Preview extends ViewGroup implements SurfaceHolder.Callback {
    private static final String TAG = Preview.class.getSimpleName();
    SurfaceHolder surfaceHolder;
    SurfaceView surfaceView;
    Camera mCamera;
    Size mPreviewSize;
    List<Size> mSupportedPreviewSizes;
    private GraphicOverlay mOverlay;
    private OrientationEventListener orientationEventListener;
    int faceRotation;

    /**
     * Map to convert between a byte array, received from the camera, and its associated byte
     * buffer.  We use byte buffers internally because this is a more efficient way to call into
     * native code later (avoids a potential copy).
     */
    private Map<byte[], ByteBuffer> mBytesToByteBuffer = new HashMap<>();

    /**
     * Dedicated thread and associated runnable for calling into the detector with frames, as the
     * frames become available from the camera.
     */
    private Thread mProcessingThread;
    private FrameProcessingRunnable mFrameProcessor;

    public Preview(Context context, SurfaceView holder) {
        super(context);
        surfaceView = holder;
        this.surfaceHolder = surfaceView.getHolder();
        this.surfaceHolder.addCallback(this);

        // deprecated setting, but required on Android versions prior to 3.0
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    private boolean hasFocusMode() {
        Camera.Parameters parameters = mCamera.getParameters();
        List<String> focusModes = parameters.getSupportedFocusModes();
        return focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO);
    }

    public void setCamera(Camera camera, GraphicOverlay mGraphicOverlay) {
        mOverlay = mGraphicOverlay;
        mCamera = camera;
        if (mCamera != null) {
            Camera.Parameters parameters = mCamera.getParameters();
            if (hasFocusMode()) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            mCamera.setParameters(parameters);
            // supported preview sizes
            mSupportedPreviewSizes = mCamera.getParameters().getSupportedPreviewSizes();
            for (Camera.Size str : mSupportedPreviewSizes)
                Log.d(TAG, str.width + "/" + str.height);

            mPreviewSize = getOptimalPreviewSize(mSupportedPreviewSizes, 800, 600);
            parameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
            List<String> focusModes = parameters.getSupportedFocusModes();
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            int[] previewFpsRange = selectPreviewFpsRange(mCamera, 30.0f);
            if (previewFpsRange == null) {
                throw new RuntimeException("Could not find suitable preview frames per second range.");
            }
            parameters.setPreviewFpsRange(
                    previewFpsRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                    previewFpsRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
            parameters.setPreviewFormat(ImageFormat.NV21);
            mCamera.setParameters(parameters);
            mCamera.setPreviewCallbackWithBuffer(new CameraPreviewCallback());
            mCamera.addCallbackBuffer(createPreviewBuffer(mPreviewSize));
            mCamera.addCallbackBuffer(createPreviewBuffer(mPreviewSize));
            mCamera.addCallbackBuffer(createPreviewBuffer(mPreviewSize));
            mCamera.addCallbackBuffer(createPreviewBuffer(mPreviewSize));
            orientationEventListener = new OrientationEventListener(getContext(),
                    SensorManager.SENSOR_DELAY_NORMAL) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (orientation == ORIENTATION_UNKNOWN) return;
                    //up
                    if (orientation >= 0 && orientation < 45)
                        faceRotation = 1;
                        //right & back camera
                    else if (orientation >= 45 && orientation < 135)
                        faceRotation = 2;
                        //down
                    else if (orientation >= 135 && orientation < 225)
                        faceRotation = 3;
                        //left & back camera
                    else if (orientation >= 225 && orientation < 315)
                        faceRotation = 0;
                        //still up
                    else if (orientation >= 315 && orientation < 359)
                        faceRotation = 1;
                }
            };
            if (orientationEventListener.canDetectOrientation()) {
                orientationEventListener.enable();
            }
            if (mOverlay != null) {
                int min = Math.min(mPreviewSize.width, mPreviewSize.height);
                int max = Math.max(mPreviewSize.width, mPreviewSize.height);
                if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                    // Swap width and height sizes when in portrait, since it will be rotated by
                    // 90 degrees
                    mOverlay.setCameraInfo(min, max, Camera.CameraInfo.CAMERA_FACING_BACK);
                } else {
                    mOverlay.setCameraInfo(max, min, Camera.CameraInfo.CAMERA_FACING_BACK);
                }
                mOverlay.clear();
            }
        }
    }

    public void startDetection(FaceDetector detector) {
        mFrameProcessor = new FrameProcessingRunnable(detector);
        mProcessingThread = new Thread(mFrameProcessor);
        mFrameProcessor.setActive(true);
        mProcessingThread.start();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            if (mCamera != null) {
                mCamera.setPreviewDisplay(holder);
                mCamera.startPreview();
            }
        } catch (IOException e) {
            Log.d(TAG, "surfaceCreated: " + e.toString());
        }
    }
    /**
     * Calculates the correct rotation for the given camera id and sets the rotation in the
     * parameters.  It also sets the camera's display orientation and rotation.
     *
     * @param parameters the camera parameters for which to set the rotation
     * @param cameraId   the camera id to set rotation based on
     */
    public void setRotation(Camera camera, Camera.Parameters parameters, int cameraId,Context context) {
        WindowManager windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int degrees = 0;
        int rotation = windowManager.getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                Log.e(TAG, "Bad rotation value: " + rotation);
        }

        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, cameraInfo);

        int angle;
        int displayAngle;
        if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            angle = (cameraInfo.orientation + degrees) % 360;
            displayAngle = (360 - angle) % 360; // compensate for it being mirrored
        } else {  // back-facing
            angle = (cameraInfo.orientation - degrees + 360) % 360;
            displayAngle = angle;
        }
        camera.setDisplayOrientation(displayAngle);
        parameters.setRotation(angle);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.e(TAG, "surfaceChanged => w=" + width + ", h=" + height);
        // If your preview can change or rotate, take care of those events here.
        // Make sure to stop the preview before resizing or reformatting it.

        if (holder.getSurface() == null){
            // preview surface does not exist
            return;
        }
        setCamera(mCamera, mOverlay);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    public void release() {
        mFrameProcessor.setActive(false);
        if (mProcessingThread != null) {
            try {
                // Wait for the thread to complete to ensure that we can't have multiple threads
                // executing at the same time (i.e., which would happen if we called start too
                // quickly after stop).
                mProcessingThread.join();
            } catch (InterruptedException e) {
                Log.d(TAG, "Frame processing thread interrupted on release.");
            }
            mProcessingThread = null;
        }
        // clear the buffer to prevent oom exceptions
        mBytesToByteBuffer.clear();
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallbackWithBuffer(null);
            try {
                mCamera.setPreviewDisplay(null);
            } catch (IOException e) {
                e.printStackTrace();
            }
            mCamera.release();
            mCamera = null;
        }
        mFrameProcessor.release();
    }

    /**
     * Selects the most suitable preview frames per second range, given the desired frames per
     * second.
     *
     * @param camera            the camera to select a frames per second range from
     * @param desiredPreviewFps the desired frames per second for the camera preview frames
     * @return the selected preview frames per second range
     */
    private int[] selectPreviewFpsRange(Camera camera, float desiredPreviewFps) {
        // The camera API uses integers scaled by a factor of 1000 instead of floating-point frame
        // rates.
        int desiredPreviewFpsScaled = (int) (desiredPreviewFps * 1000.0f);

        // The method for selecting the best range is to minimize the sum of the differences between
        // the desired value and the upper and lower bounds of the range.  This may select a range
        // that the desired value is outside of, but this is often preferred.  For example, if the
        // desired frame rate is 29.97, the range (30, 30) is probably more desirable than the
        // range (15, 30).
        int[] selectedFpsRange = null;
        int minDiff = Integer.MAX_VALUE;
        List<int[]> previewFpsRangeList = camera.getParameters().getSupportedPreviewFpsRange();
        for (int[] range : previewFpsRangeList) {
            int deltaMin = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
            int deltaMax = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
            int diff = Math.abs(deltaMin) + Math.abs(deltaMax);
            if (diff < minDiff) {
                selectedFpsRange = range;
                minDiff = diff;
            }
        }
        return selectedFpsRange;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        final int height = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        if (mSupportedPreviewSizes != null) {
            mPreviewSize = getOptimalPreviewSize(mSupportedPreviewSizes, width, height);
        }
        if (mPreviewSize != null) {
            float ratio;
            if (mPreviewSize.height >= mPreviewSize.width)
                ratio = (float) mPreviewSize.height / (float) mPreviewSize.width;
            else
                ratio = (float) mPreviewSize.width / (float) mPreviewSize.height;

            // One of these methods should be used, second method squishes preview slightly
            setMeasuredDimension(width, (int) (width * ratio));

        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (changed && getChildCount() > 0) {
            final View child = getChildAt(0);

            final int width = r - l;
            final int height = b - t;

            int previewWidth = width;
            int previewHeight = height;
            if (mPreviewSize != null) {
                previewWidth = mPreviewSize.width;
                previewHeight = mPreviewSize.height;
            }

            // Center the child SurfaceView within the parent.
            if (width * previewHeight > height * previewWidth) {
                final int scaledChildWidth = previewWidth * height / previewHeight;
                child.layout((width - scaledChildWidth) / 2, 0,
                        (width + scaledChildWidth) / 2, height);
            } else {
                final int scaledChildHeight = previewHeight * width / previewWidth;
                child.layout(0, (height - scaledChildHeight) / 2,
                        width, (height + scaledChildHeight) / 2);
            }
        }
    }

    private Size getOptimalPreviewSize(List<Size> sizes, int w, int h) {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) w / h;
        if (sizes == null) return null;
        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;
        int targetHeight = h;
        // Try to find an size match aspect ratio and size
        for (Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }
        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    //==============================================================================================
    // Frame processing
    //==============================================================================================

    /**
     * Creates one buffer for the camera preview callback.  The size of the buffer is based off of
     * the camera preview size and the format of the camera image.
     *
     * @return a new preview buffer of the appropriate size for the current camera settings
     */
    private byte[] createPreviewBuffer(Size previewSize) {
        int bitsPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.NV21);
        long sizeInBits = previewSize.height * previewSize.width * bitsPerPixel;
        int bufferSize = (int) Math.ceil(sizeInBits / 7.0d) + 1;
        // Creating the byte array this way and wrapping it, as opposed to using .allocate(),
        // should guarantee that there will be an array to work with.
        byte[] byteArray = new byte[bufferSize];
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        if (!buffer.hasArray() || (buffer.array() != byteArray)) {
            // I don't think that this will ever happen.  But if it does, then we wouldn't be
            // passing the preview content to the underlying detector later.
            throw new IllegalStateException("Failed to create valid buffer for camera source.");
        }
        mBytesToByteBuffer.put(byteArray, buffer);
        return byteArray;
    }

    /**
     * Called when the camera has a new preview frame.
     */
    private class CameraPreviewCallback implements Camera.PreviewCallback {
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            mFrameProcessor.setNextFrame(data, camera);
        }
    }

    /**
     * This runnable controls access to the underlying receiver, calling it to process frames when
     * available from the camera.  This is designed to run detection on frames as fast as possible
     * (i.e., without unnecessary context switching or waiting on the next frame).
     * <p/>
     * While detection is running on a frame, new frames may be received from the camera.  As these
     * frames come in, the most recent frame is held onto as pending.  As soon as detection and its
     * associated processing are done for the previous frame, detection on the mostly recently
     * received frame will immediately start on the same thread.
     */
    private class FrameProcessingRunnable implements Runnable {
        private Detector<?> mDetector;
        private long mStartTimeMillis = SystemClock.elapsedRealtime();

        // This lock guards all of the member variables below.
        private final Object mLock = new Object();
        private boolean mActive = true;

        // These pending variables hold the state associated with the new frame awaiting processing.
        private long mPendingTimeMillis;
        private int mPendingFrameId = 0;
        private ByteBuffer mPendingFrameData;

        FrameProcessingRunnable(Detector<?> detector) {
            mDetector = detector;
        }

        /**
         * Releases the underlying receiver.  This is only safe to do after the associated thread
         * has completed, which is managed in camera source's release method above.
         */
        void release() {
            mDetector.release();
            mDetector = null;
        }

        /**
         * Marks the runnable as active/not active.  Signals any blocked threads to continue.
         */
        void setActive(boolean active) {
            synchronized (mLock) {
                mActive = active;
                mLock.notifyAll();
            }
        }

        /**
         * Sets the frame data received from the camera.  This adds the previous unused frame buffer
         * (if present) back to the camera, and keeps a pending reference to the frame data for
         * future use.
         */
        void setNextFrame(byte[] data, Camera camera) {
            synchronized (mLock) {
                if (mPendingFrameData != null) {
                    camera.addCallbackBuffer(mPendingFrameData.array());
                    mPendingFrameData = null;
                }

                if (!mBytesToByteBuffer.containsKey(data)) {
                    Log.d(TAG,
                            "Skipping frame.  Could not find ByteBuffer associated with the image " +
                                    "data from the camera.");
                    return;
                }

                // Timestamp and frame ID are maintained here, which will give downstream code some
                // idea of the timing of frames received and when frames were dropped along the way.
                mPendingTimeMillis = SystemClock.elapsedRealtime() - mStartTimeMillis;
                mPendingFrameId++;
                mPendingFrameData = mBytesToByteBuffer.get(data);

                // Notify the processor thread if it is waiting on the next frame (see below).
                mLock.notifyAll();
            }
        }

        /**
         * As long as the processing thread is active, this executes detection on frames
         * continuously.  The next pending frame is either immediately available or hasn't been
         * received yet.  Once it is available, we transfer the frame info to local variables and
         * run detection on that frame.  It immediately loops back for the next frame without
         * pausing.
         * <p/>
         * If detection takes longer than the time in between new frames from the camera, this will
         * mean that this loop will run without ever waiting on a frame, avoiding any context
         * switching or frame acquisition time latency.
         * <p/>
         * If you find that this is using more CPU than you'd like, you should probably decrease the
         * FPS setting above to allow for some idle time in between frames.
         */
        @Override
        public void run() {
            Frame outputFrame;
            ByteBuffer data;

            while (true) {
                synchronized (mLock) {
                    while (mActive && (mPendingFrameData == null)) {
                        try {
                            // Wait for the next frame to be received from the camera, since we
                            // don't have it yet.
                            mLock.wait();
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Frame processing loop terminated.", e);
                            return;
                        }
                    }

                    if (!mActive) {
                        // Exit the loop once this camera source is stopped or released.  We check
                        // this here, immediately after the wait() above, to handle the case where
                        // setActive(false) had been called, triggering the termination of this
                        // loop.
                        return;
                    }

                    if (faceRotation == 1 || faceRotation == 3)
                        outputFrame = new Frame.Builder()
                                .setImageData(mPendingFrameData, 800,
                                        600, ImageFormat.NV21)
                                .setId(mPendingFrameId)
                                .setRotation(faceRotation)
                                .setTimestampMillis(mPendingTimeMillis)
                                .build();
                    else
                        outputFrame = new Frame.Builder()
                                .setImageData(mPendingFrameData, 800,
                                        600, ImageFormat.NV21)
                                .setId(mPendingFrameId)
                                .setRotation(faceRotation)
                                .setTimestampMillis(mPendingTimeMillis)
                                .build();
                    // Hold onto the frame data locally, so that we can use this for detection
                    // below.  We need to clear mPendingFrameData to ensure that this buffer isn't
                    // recycled back to the camera before we are done using that data.
                    data = mPendingFrameData;
                    mPendingFrameData = null;
                }
                // The code below needs to run outside of synchronization, because this will allow
                // the camera to add pending frame(s) while we are running detection on the current
                // frame.
                try {
                    mDetector.receiveFrame(outputFrame);
                } catch (Throwable t) {
                    Log.e(TAG, "Exception thrown from receiver.", t);
                } finally {
                    mCamera.addCallbackBuffer(data.array());
                }
            }
        }
    }
}
