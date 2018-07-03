package iikka.com.askelmittari;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.j256.ormlite.android.apptools.OpenHelperManager;

public class TOOL_service_StepCounterService extends Service implements SensorEventListener {
    private String TAG = "TOOL_service_StepCtrSrvc";
    private IBinder stepCounterBinder = new StepCounterBinder();
    private int notificationId = 1020;
    private SensorManager mSensorManager;
    private SharedPreferences stepCounterPreferences;
    private TOOL_tietokanta_DatabaseHelper databaseHelper = null;
    private NotificationCompat.Builder notificationBuilder;
    private Notification notification;
    private Sensor sensor;
    private boolean listenerRegistered = false;

    private PowerManager mPowermanager;
    private PowerManager.WakeLock mWakeLock;

    private float mLimit = 10;
    private float mLastValues[] = new float[3 * 2];
    private float mScale[] = new float[2];
    private float mYOffset;

    private float mLastDirections[] = new float[3 * 2];
    private float mLastExtremes[][] = {new float[3 * 2], new float[3 * 2]};
    private float mLastDiff[] = new float[3 * 2];
    private int mLastMatch = -1;

    @Override
    public void onCreate() {


        int h = 480; // TODO: remove this constant
        mYOffset = h * 0.5f;
        mScale[0] = -(h * 0.5f * (1.0f / (SensorManager.STANDARD_GRAVITY * 2)));
        mScale[1] = -(h * 0.5f * (1.0f / (SensorManager.MAGNETIC_FIELD_EARTH_MAX)));

        stepCounterPreferences = getApplicationContext().getSharedPreferences("stepCounterPreferences", Context.MODE_PRIVATE);
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mPowermanager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        sensor = null;
        sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (sensor == null) {
            sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOffReceiver, intentFilter);

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if(mWakeLock == null) {
                mWakeLock = mPowermanager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            }
            if(mWakeLock.isHeld() == false) {
                mWakeLock.acquire();
            }
        }



        //mSensorManager.unregisterListener(this); //
        if(listenerRegistered ==  false) {
            Log.i("TAG", "listenerRegister");
            mSensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST); // sensor_delay_fastest
            listenerRegistered = true;
        }
        updateNotification(stepCounterPreferences.getInt("todaySteps", 0));

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        //Log.i(TAG, "onDestroy() called");
        unregisterReceiver(screenOffReceiver);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if(mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
        stopSelf();

    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return stepCounterBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return false;
    }

    public class StepCounterBinder extends Binder {
        TOOL_service_StepCounterService getService() {
            return TOOL_service_StepCounterService.this;
        }
    }

    public BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                //Log.i(TAG,"trying re-registration");

                Runnable runnable = new Runnable() {
                    public void run() {
                        Log.i("TAG", "Runnable executing.");
                        //mSensorManager.unregisterListener(TOOL_service_StepCounterService.this);
                        //mSensorManager.registerListener(TOOL_service_StepCounterService.this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
                        context.startService(new Intent(context, TOOL_service_StepCounterService.class));
                    }
                };
                new Handler().postDelayed(runnable, 2000);
            }
        }
    };

    @Override
    public void onSensorChanged(SensorEvent event) {

        int j = (sensor.getType() == Sensor.TYPE_ACCELEROMETER) ? 1 : 0;
        if (sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float vSum = 0;
            for (int i = 0; i < 3; i++) {
                final float v = mYOffset + event.values[i] * mScale[j];
                vSum += v;
            }
            int k = 0;
            float v = vSum / 3;

            float direction = (v > mLastValues[k] ? 1 : (v < mLastValues[k] ? -1 : 0));
            if (direction == -mLastDirections[k]) {
                // Direction changed
                int extType = (direction > 0 ? 0 : 1); // minumum or maximum?
                mLastExtremes[extType][k] = mLastValues[k];
                float diff = Math.abs(mLastExtremes[extType][k] - mLastExtremes[1 - extType][k]);

                if (diff > mLimit) {

                    boolean isAlmostAsLargeAsPrevious = diff > (mLastDiff[k] * 2 / 3);
                    boolean isPreviousLargeEnough = mLastDiff[k] > (diff / 3);
                    boolean isNotContra = (mLastMatch != 1 - extType);

                    if (isAlmostAsLargeAsPrevious && isPreviousLargeEnough && isNotContra) {
                        //Log.i(TAG, "accelerometer step taken");
                        onAccelerometerStep(); // tallennuslogiikka
                        mLastMatch = extType;
                    } else {
                        mLastMatch = -1;
                    }
                }
                mLastDiff[k] = diff;
            }
            mLastDirections[k] = direction;
            mLastValues[k] = v;

        } else if (sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            int steps = (int) event.values[0];
            //Log.i(TAG, "onSensorChanged: " + steps);
            //Log.i(TAG, "Tallennetaan step counterin arvoja. ");

            boolean isCounting = stepCounterPreferences.getBoolean("isCounting", false);

            //Log.i(TAG, "stepCounterPreviousValue: " + stepCounterPreferences.getInt("stepCounterPreviousValue", 0));

            if (isCounting) {
                SharedPreferences.Editor editor = stepCounterPreferences.edit();

                if (steps - stepCounterPreferences.getInt("stepCounterPreviousValue", 0) != 0) {
                    //Log.i(TAG, "step counter step taken");
                    int todayStepsNew = steps - stepCounterPreferences.getInt("stepCounterPreviousValue", 0) + stepCounterPreferences.getInt("todaySteps", 0);

                    if(todayStepsNew < 0) { // todennäköisesti kaatunut, joten ei päivitetä, vaan pidetään arvo samana kunnes stepcounterpreviousvalue on korjaantunut.
                        todayStepsNew = stepCounterPreferences.getInt("todaySteps", 0);
                        //Log.i(TAG, "Todennäköisesti kaatunut: asetetaan nollaksi");
                    }

                    updateNotification(todayStepsNew);
                    editor.putInt("todaySteps", todayStepsNew);
                }

                editor.putInt("stepCounterPreviousValue", steps);
                editor.commit();

                //Log.i(TAG, "stepCounterPreviousValue: " + stepCounterPreferences.getInt("stepCounterPreviousValue", 0));
                //Log.i(TAG, "todaySteps: " + stepCounterPreferences.getInt("todaySteps", 0));
            }

        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    public void updateNotification(int todaySteps) {
        //Log.i(TAG, "Notification päivitetty. ");
        Intent notificationIntent = new Intent(this, TOOL_activity_Askelmittari.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        if (notificationBuilder == null) {
            notificationBuilder = new NotificationCompat.Builder(this);
            notificationBuilder.setContentTitle(getText(R.string.askelmittari_notification_title))
                    .setSmallIcon(R.drawable.ic_askel)
                    .setWhen(0)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setTicker(getText(R.string.askelmittari_notification_title));
        }

        notificationBuilder.setContentText(getText(R.string.askelmittari_notification_message) + " " + todaySteps);
        notification = notificationBuilder.build();

        startForeground(notificationId, notification);

    }

    private void onAccelerometerStep() {
        boolean isCounting = stepCounterPreferences.getBoolean("isCounting", false);
        if (isCounting) {
            SharedPreferences.Editor editor = stepCounterPreferences.edit();
            int todayStepsNew = stepCounterPreferences.getInt("todaySteps", 0) + 1;
            updateNotification(todayStepsNew);
            editor.putInt("todaySteps", todayStepsNew);
            editor.putInt("stepCounterPreviousValue", todayStepsNew);
            editor.commit();
            //Log.i(TAG, "todaySteps: " + stepCounterPreferences.getInt("todaySteps", 0));
        }
    }

    private TOOL_tietokanta_DatabaseHelper getHelper() {
        if (databaseHelper == null) {
            databaseHelper = OpenHelperManager.getHelper(getApplicationContext(), TOOL_tietokanta_DatabaseHelper.class);
        }
        return databaseHelper;
    }

}
