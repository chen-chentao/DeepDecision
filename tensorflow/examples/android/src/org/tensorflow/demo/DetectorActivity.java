/*
 * Copyright 2016 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.demo;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Display;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;
import org.tensorflow.demo.OverlayView.DrawCallback;
import org.tensorflow.demo.env.BorderedText;
import org.tensorflow.demo.env.ImageUtils;
import org.tensorflow.demo.env.Logger;
import org.tensorflow.demo.tracking.MultiBoxTracker;
import org.tensorflow.demo.R;
import org.tensorflow.demo.video.MainActivity;

import static java.lang.Thread.sleep;

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends CameraActivity implements OnImageAvailableListener {
  private static final Logger LOGGER = new Logger();

  // Configuration values for the prepackaged multibox model.
  private static final int MB_INPUT_SIZE = 224;
  private static final int MB_IMAGE_MEAN = 128;
  private static final float MB_IMAGE_STD = 128;
  private static final String MB_INPUT_NAME = "ResizeBilinear";
  private static final String MB_OUTPUT_LOCATIONS_NAME = "output_locations/Reshape";
  private static final String MB_OUTPUT_SCORES_NAME = "output_scores/Reshape";
  private static final String MB_MODEL_FILE = "file:///android_asset/multibox_model.pb";
  private static final String MB_LOCATION_FILE =
      "file:///android_asset/multibox_location_priors.txt";

  // Configuration values for tiny-yolo-voc. Note that the graph is not included with TensorFlow and
  // must be manually placed in the assets/ directory by the user.
  // Graphs and models downloaded from http://pjreddie.com/darknet/yolo/ may be converted e.g. via
  // DarkFlow (https://github.com/thtrieu/darkflow). Sample command:
  // ./flow --model cfg/tiny-yolo-voc.cfg --load bin/tiny-yolo-voc.weights --savepb --verbalise=True
  private static String YOLO_MODEL_FILE = "file:///android_asset/graph-tiny-yolo-voc.pb";
  private static String YOLO_MODEL_FILE2 = "file:///android_asset/graph-yolo-voc.pb";
  private static int YOLO_INPUT_SIZE = 160;
  private static final String YOLO_INPUT_NAME = "input";
  private static final String YOLO_OUTPUT_NAMES = "output";
  private static final int YOLO_BLOCK_SIZE = 32;

  // Default to the included multibox model.
  private static final boolean USE_YOLO = true;

  private static int CROP_SIZE = USE_YOLO ? YOLO_INPUT_SIZE : MB_INPUT_SIZE;
  private static int CROP_SIZE1, CROP_SIZE2;

  // Minimum detection confidence to track a detection.
  private static final float MINIMUM_CONFIDENCE = USE_YOLO ? 0.25f : 0.1f;

  private static final boolean MAINTAIN_ASPECT = USE_YOLO;

  private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);

  private static final boolean SAVE_PREVIEW_BITMAP = false;
  private static final float TEXT_SIZE_DIP = 10;

  private Integer sensorOrientation;

  public Classifier detector;
  public Classifier detector1;//changed to help merge yolo and tiny yolo
  public Classifier detector2;

  private int previewWidth = 0;
  private int previewHeight = 0;
  private byte[][] yuvBytes;
  private int[] rgbBytes = null;
  private Bitmap rgbFrameBitmap = null;
  private Bitmap croppedBitmap = null;

  private boolean computing = false;

  private long timestamp = 0;

  private Matrix frameToCropTransform;
  private Matrix cropToFrameTransform;

  private Bitmap cropCopyBitmap;

  private MultiBoxTracker tracker;

  private byte[] luminance;

  private BorderedText borderedText;


  private long lastProcessingTimeMs;




  public void change(int x){
    if (x == 0){//small
      YOLO_MODEL_FILE = "file:///android_asset/graph-tiny-yolo-voc.pb";
    }
    else{
      YOLO_MODEL_FILE = "file:///android_asset/graph-yolo-voc.pb";
    }
    detector =
            TensorFlowYoloDetector.create(
                    getAssets(),
                    YOLO_MODEL_FILE,
                    YOLO_INPUT_SIZE,
                    YOLO_INPUT_NAME,
                    YOLO_OUTPUT_NAMES,
                    YOLO_BLOCK_SIZE);
  }

  @Override
  public void onPreviewSizeChosen(final Size size, final int rotation) {
    final float textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    tracker = new MultiBoxTracker(this);

    if (USE_YOLO) {
      String s = getIntent().getStringExtra("Detector");
      if(s == null) {
        detector1 =
                TensorFlowYoloDetector.create(
                        getAssets(),
                        YOLO_MODEL_FILE,
                        YOLO_INPUT_SIZE,
                        YOLO_INPUT_NAME,
                        YOLO_OUTPUT_NAMES,
                        YOLO_BLOCK_SIZE);
      }
      else{

        final String[] sl = s.split(",");
        double d =  Double.valueOf(sl[0]);
        d = Math.sqrt(d);
        YOLO_INPUT_SIZE = (int)d;
        Log.e("Detector",s +":d: " + YOLO_INPUT_SIZE);
        if(sl[1].equals("1")){//tiny == 1
          detector1 =
                  TensorFlowYoloDetector.create(
                          getAssets(),
                          YOLO_MODEL_FILE,
                          YOLO_INPUT_SIZE,
                          YOLO_INPUT_NAME,
                          YOLO_OUTPUT_NAMES,
                          YOLO_BLOCK_SIZE);
        }else{//big
          detector1 =
                  TensorFlowYoloDetector.create(
                          getAssets(),
                          YOLO_MODEL_FILE2,
                          YOLO_INPUT_SIZE,
                          YOLO_INPUT_NAME,
                          YOLO_OUTPUT_NAMES,
                          YOLO_BLOCK_SIZE);
        }

      }
//      detector2 =
//              TensorFlowYoloDetector.create(
//                      getAssets(),
//                      YOLO_MODEL_FILE2,
//                      YOLO_INPUT_SIZE,
//                      YOLO_INPUT_NAME,
//                      YOLO_OUTPUT_NAMES,
//                      YOLO_BLOCK_SIZE);

    } else {
      detector =
          TensorFlowMultiBoxDetector.create(
              getAssets(),
              MB_MODEL_FILE,
              MB_LOCATION_FILE,
              MB_IMAGE_MEAN,
              MB_IMAGE_STD,
              MB_INPUT_NAME,
              MB_OUTPUT_LOCATIONS_NAME,
              MB_OUTPUT_SCORES_NAME);
    }
    detector = detector1;
    previewWidth = size.getWidth();
    previewHeight = size.getHeight();

    final Display display = getWindowManager().getDefaultDisplay();
    final int screenOrientation = display.getRotation();

    LOGGER.i("Sensor orientation: %d, Screen orientation: %d", rotation, screenOrientation);

    sensorOrientation = rotation + screenOrientation;

    LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
    rgbBytes = new int[previewWidth * previewHeight];
    rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);

      CROP_SIZE1 = YOLO_INPUT_SIZE;
    CROP_SIZE = CROP_SIZE2 = CROP_SIZE1;
      Log.v("imagesize",""+CROP_SIZE1);

    croppedBitmap = Bitmap.createBitmap(CROP_SIZE, CROP_SIZE, Config.ARGB_8888);

    frameToCropTransform =
        ImageUtils.getTransformationMatrix(
            previewWidth, previewHeight,
            CROP_SIZE, CROP_SIZE,
            sensorOrientation, MAINTAIN_ASPECT);

    cropToFrameTransform = new Matrix();
    frameToCropTransform.invert(cropToFrameTransform);
    yuvBytes = new byte[3][];

    trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
    trackingOverlay.addCallback(
        new DrawCallback() {
          @Override
          public void drawCallback(final Canvas canvas) {
            tracker.draw(canvas);
            if (isDebug()) {
              tracker.drawDebug(canvas);
            }
          }
        });

    addCallback(
        new DrawCallback() {
          @Override
          public void drawCallback(final Canvas canvas) {
            if (!isDebug()) {
              return;
            }


            final Bitmap copy = cropCopyBitmap;
            if (copy == null) {
              return;
            }

            final int backgroundColor = Color.argb(100, 0, 0, 0);
            canvas.drawColor(backgroundColor);

            final Matrix matrix = new Matrix();
            final float scaleFactor = 2;
            matrix.postScale(scaleFactor, scaleFactor);
            matrix.postTranslate(
                canvas.getWidth() - copy.getWidth() * scaleFactor,
                canvas.getHeight() - copy.getHeight() * scaleFactor);
            canvas.drawBitmap(copy, matrix, new Paint());

            final Vector<String> lines = new Vector<String>();

//            if(CameraConnectionFragment.changed){
//              CameraConnectionFragment.changed = false;
//              if(CameraConnectionFragment.big){
//                change(1);
//              }else{
//                change(0);
//              }
//            }


            if (detector != null) {
              final String statString = detector.getStatString();
              final String[] statLines = statString.split("\n");
              for (final String line : statLines) {
                lines.add(line);
              }
            }
            lines.add("");

            lines.add("Frame: " + previewWidth + "x" + previewHeight);
            lines.add("Crop: " + copy.getWidth() + "x" + copy.getHeight());
            lines.add("View: " + canvas.getWidth() + "x" + canvas.getHeight());
            lines.add("Rotation: " + sensorOrientation);
            lines.add("Inference time: " + lastProcessingTimeMs + "ms");

            borderedText.drawLines(canvas, 10, canvas.getHeight() - 10, lines);
          }
        });
  }

  OverlayView trackingOverlay;

  @Override
  public void onImageAvailable(final ImageReader reader) {
    Image image = null;

    ++timestamp;
    final long currTimestamp = timestamp;

    try {
      image = reader.acquireLatestImage();

      if (image == null) {
        return;
      }

      Trace.beginSection("imageAvailable");

      final Plane[] planes = image.getPlanes();
      fillBytes(planes, yuvBytes);

      tracker.onFrame(
          previewWidth,
          previewHeight,
          planes[0].getRowStride(),
          sensorOrientation,
          yuvBytes[0],
          timestamp);
      trackingOverlay.postInvalidate();

      // No mutex needed as this method is not reentrant.
      if (computing) {
        image.close();
        return;
      }
      computing = true;

      final int yRowStride = planes[0].getRowStride();
      final int uvRowStride = planes[1].getRowStride();
      final int uvPixelStride = planes[1].getPixelStride();
      ImageUtils.convertYUV420ToARGB8888(
          yuvBytes[0],
          yuvBytes[1],
          yuvBytes[2],
          previewWidth,
          previewHeight,
          yRowStride,
          uvRowStride,
          uvPixelStride,
          rgbBytes);

      image.close();
    } catch (final Exception e) {
      if (image != null) {
        image.close();
      }
      LOGGER.e(e, "Exception!");
      Trace.endSection();
      return;
    }

    rgbFrameBitmap.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);
    final Canvas canvas = new Canvas(croppedBitmap);
    canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);

    // For examining the actual TF input.
    if (SAVE_PREVIEW_BITMAP) {
      ImageUtils.saveBitmap(croppedBitmap);
    }

    if (luminance == null) {
      luminance = new byte[yuvBytes[0].length];
    }
    System.arraycopy(yuvBytes[0], 0, luminance, 0, luminance.length);

    runInBackground(
        new Runnable() {
          @Override
          public void run() {

//            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
//            ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
//            activityManager.getMemoryInfo(mi);
//            double availableMegs = mi.availMem / 0x100000L;
//            Log.e("memmem", "mem: " + availableMegs);


            final Runtime runtime = Runtime.getRuntime();
            final long usedMemInMB=(runtime.totalMemory() - runtime.freeMemory()) / 1048576L;
            final long maxHeapSizeInMB=runtime.maxMemory() / 1048576L;
            final long availHeapSizeInMB = maxHeapSizeInMB - usedMemInMB;
            Log.e("memmem", "mem: " + availHeapSizeInMB);





            if(CameraConnectionFragment.changed){
              CameraConnectionFragment.changed = false;
              CROP_SIZE1 = 160 + CameraConnectionFragment.size * 64;
              YOLO_INPUT_SIZE = CROP_SIZE = CROP_SIZE2 = CROP_SIZE1;
              //Log.e("imagesize",":"+CROP_SIZE1);

              croppedBitmap = Bitmap.createBitmap(CROP_SIZE1, CROP_SIZE2, Config.ARGB_8888);

              frameToCropTransform =
                      ImageUtils.getTransformationMatrix(
                              previewWidth, previewHeight,
                              CROP_SIZE1, CROP_SIZE2,
                              sensorOrientation, MAINTAIN_ASPECT);
              final long t1 = SystemClock.uptimeMillis();
              if(CameraConnectionFragment.CA == 1){
                CameraConnectionFragment.CA = 0;
                Log.e("CACACA","OK1");
//                onPause();

                stopCam();
                Log.e("CACACA","OK2");
                try {
                  sleep(100);
                } catch (InterruptedException e) {
                  e.printStackTrace();
                }
                Log.e("CACACA","OK3");
                Intent intent = new Intent(DetectorActivity.this, MainActivity.class);
                startActivity(intent);
                Log.e("CACACA","here");
              }
              if(CameraConnectionFragment.big){
                detector = null;
                System.gc();
                Log.e("imagesize","before");
                detector =
                        TensorFlowYoloDetector.create(
                                getAssets(),
                                YOLO_MODEL_FILE2,
                                YOLO_INPUT_SIZE,
                                YOLO_INPUT_NAME,
                                YOLO_OUTPUT_NAMES,
                                YOLO_BLOCK_SIZE);
                Log.e("imagesize","after");
//                detector = detector2;
              }else{
                detector = null;
                System.gc();
                detector =
                        TensorFlowYoloDetector.create(
                                getAssets(),
                                YOLO_MODEL_FILE,
                                YOLO_INPUT_SIZE,
                                YOLO_INPUT_NAME,
                                YOLO_OUTPUT_NAMES,
                                YOLO_BLOCK_SIZE);
                //detector = detector1;
              }
              final long t2 = SystemClock.uptimeMillis();
              Log.e("loadtime","time:" + (t2-t1));
            }

            final long startTime = SystemClock.uptimeMillis();



            int h = croppedBitmap.getHeight();
            //Log.e("imagesize", "getHeight:"+ croppedBitmap.getHeight());

            if(h==YOLO_INPUT_SIZE) {
              final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);

              lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;
              //AppendLog.Log("process time: " + lastProcessingTimeMs);
              Log.e("imagesize","time: "+ lastProcessingTimeMs);
              cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
              final Canvas canvas = new Canvas(cropCopyBitmap);
              final Paint paint = new Paint();
              paint.setColor(Color.RED);
              paint.setStyle(Style.STROKE);
              paint.setStrokeWidth(2.0f);

              final List<Classifier.Recognition> mappedRecognitions =
                      new LinkedList<Classifier.Recognition>();

              for (final Classifier.Recognition result : results) {
                final RectF location = result.getLocation();
                if (location != null && result.getConfidence() >= MINIMUM_CONFIDENCE) {
                  canvas.drawRect(location, paint);

                  cropToFrameTransform.mapRect(location);
                  result.setLocation(location);
                  mappedRecognitions.add(result);
                }
              }

              tracker.trackResults(mappedRecognitions, luminance, currTimestamp);
              trackingOverlay.postInvalidate();

              requestRender();
              computing = false;
            }///////////////////
          }
        });

    Trace.endSection();
  }

  @Override
  protected int getLayoutId() {
    return R.layout.camera_connection_fragment_tracking;
  }

  @Override
  protected Size getDesiredPreviewFrameSize() {
    return DESIRED_PREVIEW_SIZE;
  }

  @Override
  public void onSetDebug(final boolean debug) {
    detector.enableStatLogging(debug);
  }
}
