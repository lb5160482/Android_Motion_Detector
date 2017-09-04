package pl.aprilapps.motiondetectorsample;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TableRow;
import android.widget.TextView;

import com.jwetherell.motiondetection.data.GlobalData;
import com.jwetherell.motiondetection.data.Preferences;
import com.jwetherell.motiondetection.detection.AggregateLumaMotionDetection;
import com.jwetherell.motiondetection.detection.IMotionDetection;
import com.jwetherell.motiondetection.detection.LumaMotionDetection;
import com.jwetherell.motiondetection.detection.RgbMotionDetection;
import com.jwetherell.motiondetection.image.ImageProcessing;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.mail.AuthenticationFailedException;
import javax.mail.MessagingException;

public class MotionDetectionActivity extends SensorsActivity {

    private static final String TAG = "MotionDetectionActivity";
    private boolean debug = false;

    private static SurfaceView preview = null;
    private static SurfaceHolder previewHolder = null;
    private static TextView textView;
    private static Camera camera = null;
    private static boolean inPreview = false;
    private static long mReferenceTime = 0;
    private static IMotionDetection detector = null;
    private Button toggleButton;
    private Boolean isDetecting = false;
    private static Boolean doDetect = false;

    private static TextView countText;
    private static CountDownTimer countDownTimer;
    private static EditText startTimeEditText;
    private static TableRow startTimeTableRow;
    private static TableRow userNameTableRow;
    private static TableRow passWordTableRow;
    private EditText userEditText;
    private EditText passEditText;
    private String fileName;
    private String sendFileName;
    private  Boolean isSendingMsg = false;
    private Object syncSendMsg;
    private int numImageTaken = 0;

    private static volatile AtomicBoolean processing = new AtomicBoolean(false);

    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        syncSendMsg = new Object();

        preview = (SurfaceView) findViewById(R.id.preview);
        previewHolder = preview.getHolder();

        toggleButton = (Button)findViewById(R.id.toggle_detection);
        toggleButton.setOnClickListener(mOnClickListener);

        previewHolder.addCallback(surfaceCallback);
        previewHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        textView = (TextView)findViewById(R.id.thief_text);
        countText = (TextView)findViewById(R.id.count_text);
        startTimeEditText = (EditText) findViewById(R.id.start_time);
        userEditText = (EditText)findViewById(R.id.username);
        passEditText = (EditText)findViewById(R.id.password);
        startTimeTableRow = (TableRow)findViewById(R.id.tableRow1);
        userNameTableRow = (TableRow)findViewById(R.id.tableRow_user);
        passWordTableRow = (TableRow)findViewById(R.id.tableRow_password);

        if (Preferences.USE_RGB) {
            detector = new RgbMotionDetection();
        } else if (Preferences.USE_LUMA) {
            detector = new LumaMotionDetection();
        } else {
            // Using State based (aggregate map)
            detector = new AggregateLumaMotionDetection();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPause() {
        super.onPause();

        camera.setPreviewCallback(null);
        if (inPreview) camera.stopPreview();
        inPreview = false;
        camera.release();
        camera = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onResume() {
        super.onResume();

        camera = Camera.open();
    }

    private PreviewCallback previewCallback = new PreviewCallback() {

        /**
         * {@inheritDoc}
         */
        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {
            if (data == null) return;
            Camera.Size size = cam.getParameters().getPreviewSize();
            if (size == null) return;

            if (!GlobalData.isPhoneInMotion()) {
                DetectionThread thread = new DetectionThread(data, size.width, size.height);
                thread.start();
            }
        }
    };

    private SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {

        /**
         * {@inheritDoc}
         */
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            try {
                camera.setPreviewDisplay(previewHolder);
                camera.setPreviewCallback(previewCallback);
            } catch (Throwable t) {
                Log.e("surfaceCallback", "setPreviewDisplay()", t);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = getBestPreviewSize(width, height, parameters);
            if (size != null) {
                parameters.setPreviewSize(size.width, size.height);
                if (debug) Log.d(TAG, "Using width=" + size.width + " height=" + size.height);
            }
            camera.setParameters(parameters);
            camera.startPreview();
            inPreview = true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            // Ignore
        }
    };

    private static Camera.Size getBestPreviewSize(int width, int height, Camera.Parameters parameters) {
        Camera.Size result = null;

        for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
            if (size.width <= width && size.height <= height) {
                if (result == null) {
                    result = size;
                } else {
                    int resultArea = result.width * result.height;
                    int newArea = size.width * size.height;

                    if (newArea > resultArea) result = size;
                }
            }
        }

        return result;
    }

    private final class DetectionThread extends Thread {

        private byte[] data;
        private int width;
        private int height;

        public DetectionThread(byte[] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            if (!doDetect) {
                MotionDetectionActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (textView.getText() != "") {
                            textView.setText("");
                        }
                    }
                });
                return;
            }
//            if (debug) Log.d(TAG, "start running detection thread!");
            if (!processing.compareAndSet( false, true)) return;

            if (debug) Log.d(TAG, "BEGIN PROCESSING...");
            try {
                // Previous frame
                int[] pre = null;
                if (Preferences.SAVE_PREVIOUS) pre = detector.getPrevious();

                // Current frame (with changes)
                // long bConversion = System.currentTimeMillis();
                int[] img = null;
                if (Preferences.USE_RGB) {
                    img = ImageProcessing.decodeYUV420SPtoRGB(data, width, height);
                } else {
                    img = ImageProcessing.decodeYUV420SPtoLuma(data, width, height);
                }
                // long aConversion = System.currentTimeMillis();
                // Log.d(TAG, "Converstion="+(aConversion-bConversion));

                // Current frame (without changes)
                int[] org = null;
                if (Preferences.SAVE_ORIGINAL && img != null) org = img.clone();

                if (img != null && detector.detect(img, width, height)) {

                    MotionDetectionActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            textView.setText("Someone is coming into your room!");
                        }
                    });

                    // The delay is necessary to avoid taking a picture while in
                    // the
                    // middle of taking another. This problem can causes some
                    // phones
                    // to reboot.
                    long now = System.currentTimeMillis();
                    if (now > (mReferenceTime + Preferences.PICTURE_DELAY)) {
                        mReferenceTime = now;

                        Bitmap previous = null;
                        if (Preferences.SAVE_PREVIOUS && pre != null) {
                            if (Preferences.USE_RGB) previous = ImageProcessing.rgbToBitmap(pre, width, height);
                            else previous = ImageProcessing.lumaToGreyscale(pre, width, height);
                        }

                        Bitmap original = null;
                        if (Preferences.SAVE_ORIGINAL && org != null) {
                            if (Preferences.USE_RGB) original = ImageProcessing.rgbToBitmap(org, width, height);
                            else original = ImageProcessing.lumaToGreyscale(org, width, height);
                        }

                        Bitmap bitmap = null;
                        if (Preferences.SAVE_CHANGES) {
                            if (Preferences.USE_RGB) bitmap = ImageProcessing.rgbToBitmap(img, width, height);
                            else bitmap = ImageProcessing.lumaToGreyscale(img, width, height);
                        }

                        if (debug) Log.i(TAG, "Saving.. previous=" + previous + " original=" + original + " bitmap=" + bitmap);
                        Looper.prepare();
                        new SavePhotoTask().execute(previous, original, bitmap);
                        numImageTaken++;
                        if (numImageTaken == 2) {
                            numImageTaken = 0;
                            if (!isSendingMsg) {
                                synchronized (syncSendMsg) {
                                    System.out.println("numImageTaken: sending!!!!!");
                                    sendFileName = fileName;
                                }
                                sendMessage(sendFileName);
                            }
                        }
                    } else {
                        if (debug) Log.i(TAG, "Not taking picture because not enough time has passed since the creation of the Surface");
                    }
                }
                else {
                    MotionDetectionActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            textView.setText("");
                        }
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                processing.set(false);
            }
            // Log.d(TAG, "END PROCESSING...");

            processing.set(false);
        }
    };

    private final class SavePhotoTask extends AsyncTask<Bitmap, Integer, Integer> {
        private String path = Environment.getExternalStorageDirectory() + "/capturedImages";

        public SavePhotoTask() {
            File folder = new File(path);
            if (!folder.isDirectory()) {
                folder.mkdir();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected Integer doInBackground(Bitmap... data) {
            for (int i = 0; i < data.length; i++) {
                Bitmap bitmap = data[i];
                String name = String.valueOf(System.currentTimeMillis());
                if (i == 1) {
                    synchronized (syncSendMsg) {
                        fileName = path + "/" + name + ".jpg";
                    }
                }
                if (bitmap != null) save(name, bitmap);
            }
            return 1;
        }

        private void save(String name, Bitmap bitmap) {
            File photo = new File(path, name + ".jpg");
            if (photo.exists()) photo.delete();

            try {
                FileOutputStream fos = new FileOutputStream(photo.getPath());
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                fos.close();
            } catch (java.io.IOException e) {
                if (debug) Log.e(TAG, "Exception in photoCallback", e);
            }
        }
    }
    private final View.OnClickListener mOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(final View v) {
            switch (v.getId()) {
                case R.id.toggle_detection:
                    if (!isDetecting) {
                        int startTime = Integer.parseInt(startTimeEditText.getText().toString());

                        countDownTimer = new CountDownTimer(startTime * 1000, 10) {
                            public void onTick(final long millisUntilFinished) {
                                MotionDetectionActivity.this.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        int num = (int)millisUntilFinished;
                                        countText.setText(num / 1000.0 + "s...to be started!");
                                    }
                                });
                            }

                            public void onFinish() {
                                MotionDetectionActivity.this.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        countText.setText("");
                                        doDetect = true;
                                    }
                                });
                            }
                        };
                        countDownTimer.start();
                        startTimeTableRow.setVisibility(View.INVISIBLE);
                        userNameTableRow.setVisibility(View.INVISIBLE);
                        passWordTableRow.setVisibility(View.INVISIBLE);
                        isDetecting = true;
                        TextView tv = (TextView)findViewById(R.id.toggle_detection);
                        tv.setText("Stop Detecting");
                    }
                    else {
                        countDownTimer.cancel();
                        startTimeTableRow.setVisibility(View.VISIBLE);
                        userNameTableRow.setVisibility(View.VISIBLE);
                        passWordTableRow.setVisibility(View.VISIBLE);
                        countText.setText("");
                        isDetecting = false;
                        doDetect = false;
                        TextView tv = (TextView)findViewById(R.id.toggle_detection);
                        tv.setText("Start Detecting");
                    }
                    break;
            }
        }
    };

    private void sendMessage(String fileName) {
        String[] recipients = {userEditText.getText().toString() };
        SendEmailAsyncTask email = new SendEmailAsyncTask();
        email.activity = this;
        email.m = new Mail(userEditText.getText().toString(), passEditText.getText()
                .toString());
        email.m.set_from(userEditText.getText().toString());
        email.m.setBody("Image captured:\n");
        email.m.set_to(recipients);
        email.m.set_subject("Image captured from LB Motion Detector");
        try {
            email.m.addAttachment(fileName);
        }
        catch (Exception e) {
            displayMessage("Image attached failed!");
            if (debug) Log.d(TAG, "Image attached failed!");
            return;
        }
        email.execute();
    }

    public void displayMessage(String message) {
        Snackbar.make(findViewById(R.id.password), message, Snackbar.LENGTH_LONG)
                .setAction("Action", null).show();
    }

    class SendEmailAsyncTask extends AsyncTask<Void, Void, Boolean> {
        Mail m;
        MotionDetectionActivity activity;

        public SendEmailAsyncTask() {}

        @Override
        protected Boolean doInBackground(Void... params) {
            isSendingMsg = true;
            try {
                activity.displayMessage("start sending message!");
                if (debug) Log.d(TAG, "start sending message");
                if (m.send()) {
                    activity.displayMessage("Email sent.");
                    if (debug) Log.d(TAG, "Email sent!");
                } else {
                    activity.displayMessage("Email failed to send.");
                    if (debug) Log.d(TAG, "Email failed to send.");
                }
                isSendingMsg = false;
                return true;
            } catch (AuthenticationFailedException e) {
                Log.e(SendEmailAsyncTask.class.getName(), "Bad account details");
                e.printStackTrace();
                activity.displayMessage("Authentication failed.");
                if (debug) Log.d(TAG, "Authentication failed.");
                isSendingMsg = false;
                return false;
            } catch (MessagingException e) {
                Log.e(SendEmailAsyncTask.class.getName(), "Email failed");
                e.printStackTrace();
                activity.displayMessage("Email failed to send.");
                if (debug) Log.d(TAG, "Email failed to send.");
                isSendingMsg = false;
                return false;
            } catch (Exception e) {
                e.printStackTrace();
                activity.displayMessage("Unexpected error occured.");
                if (debug) Log.d(TAG, "Unexpected error occured");
                isSendingMsg = false;
                return false;
            }
        }
    }
}