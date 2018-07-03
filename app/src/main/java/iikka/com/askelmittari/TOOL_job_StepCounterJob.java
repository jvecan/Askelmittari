package iikka.com.askelmittari;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.support.annotation.NonNull;
import android.util.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.evernote.android.job.Job;
import com.evernote.android.job.JobRequest;
import com.j256.ormlite.android.apptools.OpenHelperManager;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.stmt.QueryBuilder;

import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TOOL_job_StepCounterJob extends Job implements SensorEventListener {
    public static final String TAG = "step_counter_job";
    private SharedPreferences stepCounterPreferences;
    private TOOL_tietokanta_DatabaseHelper databaseHelper = null;
    private SensorManager mSensorManager;
    private Sensor sensor;

    @Override
    @NonNull
    protected Result onRunJob(Params params) {
        //Log.i("TOOL_job_StepCounterJob", "onRunJob() called");
        stepCounterPreferences = getContext().getApplicationContext().getSharedPreferences("stepCounterPreferences", Context.MODE_PRIVATE);

        mSensorManager = (SensorManager) getContext().getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        sensor = null;
        sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (sensor == null) {
            sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        SharedPreferences userInfoPreferences = getContext().getApplicationContext().getSharedPreferences("User_info", Context.MODE_PRIVATE);
        final String id = userInfoPreferences.getString("id", "");

        //mSensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);

        boolean isCounting = stepCounterPreferences.getBoolean("isCounting", false);

        if (isCounting && isCanceled() == false) {
            int jobId = new JobRequest.Builder(TOOL_job_StepCounterJob.TAG)
                    .setExecutionWindow(30_000L, 60_000L)
                    .setBackoffCriteria(5_000L, JobRequest.BackoffPolicy.LINEAR)
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)
                    .setRequiredNetworkType(JobRequest.NetworkType.ANY)
                    .setRequirementsEnforced(true)
                    .setPersisted(true)
                    .setUpdateCurrent(true)
                    .build()
                    .schedule();

            getContext().getApplicationContext().startService(new Intent(getContext(), TOOL_service_StepCounterService.class));

            Calendar currentDate = Calendar.getInstance();
            SimpleDateFormat dateForDatabaseFormat = new SimpleDateFormat("yyyy-MM-dd");

            long lastSaveTime = stepCounterPreferences.getLong("lastSaveTime", 0);
            Calendar lastSaveDateTime = Calendar.getInstance();
            lastSaveDateTime.setTimeInMillis(lastSaveTime);

            //Log.i(TAG, "stepCounterPreviousValue: " + stepCounterPreferences.getInt("stepCounterPreviousValue", 0));
            //Log.i(TAG, "todaySteps: " + stepCounterPreferences.getInt("todaySteps", 0));
            //Log.i(TAG, "lastSaveTime: " + lastSaveTimeFormat.format(lastSaveDateTime.getTimeInMillis()));

            String stepCounterEndDatePref = stepCounterPreferences.getString("stepCounterEndDate", null);
            Calendar stepCounterEndDate = Calendar.getInstance();
            try {
                if(stepCounterEndDatePref != null) {
                    stepCounterEndDate.setTime(dateForDatabaseFormat.parse(stepCounterEndDatePref));
                }
            } catch (ParseException e) {
                e.printStackTrace();
            }

            SharedPreferences.Editor editor = stepCounterPreferences.edit();
            int stepsToBeAdded = 0;

            if (sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                stepsToBeAdded = stepCounterPreferences.getInt("todaySteps", 0);
            } else if (sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
                stepsToBeAdded = stepCounterPreferences.getInt("stepCounterPreviousValue", 0) - stepCounterPreferences.getInt("lastSaveCount", 0);
                if (stepsToBeAdded < -2) { // Todennäköisesti kaatunut.
                    stepsToBeAdded = stepCounterPreferences.getInt("stepCounterPreviousValue", 0);
                    editor.putInt("lastSaveCount", 0);
                }
            }

            try {
                Dao<TOOL_tietokanta_StepCountDaily, Integer> dao = getHelper().getStepCountDailyDao();
                QueryBuilder<TOOL_tietokanta_StepCountDaily, Integer> queryBuilder = dao.queryBuilder();
                queryBuilder.where().eq("pvm", dateForDatabaseFormat.format(lastSaveDateTime.getTimeInMillis()));
                List<TOOL_tietokanta_StepCountDaily> edellinenList = queryBuilder.query();
                TOOL_tietokanta_StepCountDaily edellinenTallennettu = edellinenList.get(0);

                SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");

                // Tietokantaan on aina tallennettu jotain, koska ensimmäinen luodaan tyhjänä heti kun askelmittari käynnistetään.
                if (fmt.format(currentDate.getTime()).equals(fmt.format(lastSaveDateTime.getTime())) == false) { // Päivä vaihtui
                    // Päivä on jälkeen päättymispäivän ja ensimmäinen kerta kun mennään läpi.
                    if (currentDate.after(stepCounterEndDate) && stepCounterPreferences.getBoolean("firstTimeFinished", false) == false) {
                        Log.i(TAG, "Lopetetaan laskeminen, koska päättymispäivä. ");
                        edellinenTallennettu.setStepcount(stepsToBeAdded + edellinenTallennettu.getStepcount());
                        sendStepcountData(id, edellinenTallennettu.getPvm(), edellinenTallennettu.getStepcount());
                        dao.createOrUpdate(edellinenTallennettu);

                        editor.putLong("lastSaveTime", currentDate.getTimeInMillis());
                        editor.putBoolean("isCounting", false);
                        editor.putBoolean("firstTimeFinished", true);
                        editor.putInt("todaySteps", 0);

                        editor.putString("stepCounterStartDate", null);
                        editor.putString("stepCounterEndDate", null);
                        editor.putString("stepCounterEndDateForUi", null);
                        editor.putString("stepCounterStartDateForUi", null);
                        // TESTI
                       // editor.putBoolean("startNewFlag", true);
                        // TESTI

                        Log.i(TAG, "Muuttunut päivä, viimeinen päivä ja ensimmäistä kertaa läpi. ");

                        getContext().getApplicationContext().stopService(new Intent(getContext(), TOOL_service_StepCounterService.class));
                    } else if(currentDate.after(stepCounterEndDate)) {
                        Log.i(TAG, "Lopetetaan laskeminen, koska päättymispäivä. ");
                        edellinenTallennettu.setStepcount(stepsToBeAdded + edellinenTallennettu.getStepcount());
                        sendStepcountData(id, edellinenTallennettu.getPvm(), edellinenTallennettu.getStepcount());
                        dao.createOrUpdate(edellinenTallennettu);

                        editor.putLong("lastSaveTime", currentDate.getTimeInMillis());
                        editor.putBoolean("isCounting", false);
                        editor.putInt("todaySteps", 0);

                        editor.putString("stepCounterStartDate", null);
                        editor.putString("stepCounterEndDate", null);
                        editor.putString("stepCounterEndDateForUi", null);
                        editor.putString("stepCounterStartDateForUi", null);

                        //editor.putBoolean("startNewFlag", true);

                        Log.i(TAG, "Muuttunut päivä, viimeinen päivä, menty jo läpi.  ");

                        getContext().getApplicationContext().stopService(new Intent(getContext(), TOOL_service_StepCounterService.class));

                    }
                    else if(currentDate.get(Calendar.DAY_OF_YEAR) != lastSaveDateTime.get(Calendar.DAY_OF_YEAR)) {
                        Log.i(TAG, "Muuttunut päivä. ");
                        edellinenTallennettu.setStepcount(stepsToBeAdded + edellinenTallennettu.getStepcount());
                        sendStepcountData(id, edellinenTallennettu.getPvm(), edellinenTallennettu.getStepcount());
                        dao.createOrUpdate(edellinenTallennettu);

                        TOOL_tietokanta_StepCountDaily newStepCountDaily = new TOOL_tietokanta_StepCountDaily();
                        newStepCountDaily.setPvm(dateForDatabaseFormat.format(currentDate.getTimeInMillis()));
                        newStepCountDaily.setStepcount(0);
                        dao.createOrUpdate(newStepCountDaily);

                        editor.putInt("todaySteps", 0);
                        editor.putLong("lastSaveTime", currentDate.getTimeInMillis());
                    } else { //
                        Log.i("ASKELMITTARI", "failsafe");
                    }
                } else { // Päivä edelleen sama.
                    if(currentDate.get(Calendar.HOUR_OF_DAY) != lastSaveDateTime.get(Calendar.HOUR_OF_DAY)) { // Tunti vaihtunut.
                        Log.i(TAG, "Muuttunut tunti. ");
                        edellinenTallennettu.setStepcount(stepsToBeAdded + edellinenTallennettu.getStepcount());
                        sendStepcountData(id, edellinenTallennettu.getPvm(), edellinenTallennettu.getStepcount());
                        dao.createOrUpdate(edellinenTallennettu);

                        editor.putLong("lastSaveTime", currentDate.getTimeInMillis());
                        editor.putInt("lastSaveCount", stepCounterPreferences.getInt("stepCounterPreviousValue", 0));
                    } else {
                        Log.i(TAG, "Tunti ei vielä vaihtunut. ");
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            editor.commit();



        }

        return Result.SUCCESS;
    }

    private TOOL_tietokanta_DatabaseHelper getHelper() {
        if (databaseHelper == null) {
            databaseHelper = OpenHelperManager.getHelper(getContext().getApplicationContext(), TOOL_tietokanta_DatabaseHelper.class);
        }
        return databaseHelper;
    }


    private void sendStepcountData(final String id, final String pvm, final int stepcount) {
        final String stepcountUrl = "https://www..";

        /*
        StringRequest stringRequestStepcount = new StringRequest(Request.Method.POST, stepcountUrl,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.d(TAG, "Data sent. ");
                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.d(TAG, "Failed to send data. ");
            }
        }) {
            @Override
            protected Map<String, String> getParams() throws AuthFailureError {
                Map<String, String> params = new HashMap<String, String>();
                params.put("id", id);
                params.put("pvm", pvm);
                params.put("stepcount", String.valueOf(stepcount));
                Log.d("StepCounterJob", "id: " + id);
                Log.d("StepCounterJob", "pvm: " + pvm);
                Log.d("StepCounterJob", "stepcount: " + stepcount);
                return (params);
            }
        };
        */
        //MySingleton.getInstance(getContext().getApplicationContext()).addToRequestQueue(stringRequestStepcount);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {


    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }
}
