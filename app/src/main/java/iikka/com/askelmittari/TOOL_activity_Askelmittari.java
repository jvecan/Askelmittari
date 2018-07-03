package iikka.com.askelmittari;


import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.evernote.android.job.JobManager;
import com.evernote.android.job.JobRequest;
import com.github.lzyzsd.circleprogress.DonutProgress;
import com.j256.ormlite.android.apptools.OpenHelperManager;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.GenericRawResults;
import com.j256.ormlite.table.TableUtils;

import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.chart.BarChart;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;

import java.sql.SQLException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static android.content.Context.SENSOR_SERVICE;

public class TOOL_activity_Askelmittari extends AppCompatActivity implements SensorEventListener {
    private GraphicalView chartView;
    private XYSeries askeldata;
    private XYSeriesRenderer seriesRendererAskeldata;
    private XYMultipleSeriesRenderer mRenderer;
    private XYMultipleSeriesDataset dataset;
    private SensorManager mSensorManager;
    private SharedPreferences stepCounterPreferences;
    private TOOL_tietokanta_DatabaseHelper databaseHelper = null;
    private TOOL_service_StepCounterService stepCounterService;
    private TextView txtPituus;
    private TextView txtTavoite;
    private Button btnToggleStepCounting;
    private Sensor sensor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tool_askelmittari);

       stepCounterPreferences = getSharedPreferences("stepCounterPreferences", Context.MODE_PRIVATE);

        JobManager.create(getApplicationContext());
        JobManager mJobManager = JobManager.instance();

        // Sensorituen määrittely ja listenerien rekisteröinti.
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensor = null;
        sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (sensor == null) {
            sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            //Toast.makeText(this, "Puhelin ei tue askelmittaria. Käytetään kiihdytysanturia laskemiseen. ", Toast.LENGTH_LONG).show();
        }
        mSensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);

        // Käyttöliittymän arvojen asettaminen.
        btnToggleStepCounting = (Button) findViewById(R.id.btnToggleStepCounting);
        btnToggleStepCounting.setVisibility(View.GONE);//alkupGone vaiha ko testattu
        txtPituus = (TextView) findViewById(R.id.txtPituus);
        txtTavoite = (TextView) findViewById(R.id.txtAskeltavoite);
        txtTavoite.setText("" + stepCounterPreferences.getInt("goal", 10000));
        txtPituus.setText("" + stepCounterPreferences.getInt("stepCounterUserHeight", 175));

        String stepCounterStartDate = stepCounterPreferences.getString("stepCounterStartDateForUi", null);
        String stepCounterEndDate = stepCounterPreferences.getString("stepCounterEndDateForUi", null);

        if (stepCounterStartDate != null && stepCounterEndDate != null) {
            TextView txtAloitusPvm = (TextView) findViewById(R.id.txtAloitusPvm);
            TextView txtLopetusPvm = (TextView) findViewById(R.id.txtLopetusPvm);
            txtAloitusPvm.setText(stepCounterStartDate);
            txtLopetusPvm.setText(stepCounterEndDate);
        }

        // Kaavion alustaminen ja päivittäminen.
        initializeChart();
        updateChart();

        // Näytetään jatkamisnappi, jos käyttäjä on suorittanut tehtävän.
        setToggleStepCountingButton();

        // Jos lasketaan, varmistetaan että service on päällä.
        boolean isCounting = stepCounterPreferences.getBoolean("isCounting", false);
        if (isCounting) {
            startService(new Intent(this, TOOL_service_StepCounterService.class));
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
            btnToggleStepCounting.setText("Lopeta laskeminen");
        } else {
            btnToggleStepCounting.setText("Aloita laskeminen");
        }

        Button btnMuutaPituus = (Button) findViewById(R.id.btnMuutaPituus);
        btnMuutaPituus.setOnClickListener(new View.OnClickListener()

        {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder alert = new AlertDialog.Builder(TOOL_activity_Askelmittari.this);
                alert.setTitle("Pituuden asettaminen");
                alert.setMessage("Aseta pituus:");
                final EditText inputPituus = new EditText(TOOL_activity_Askelmittari.this);
                inputPituus.setInputType(InputType.TYPE_CLASS_NUMBER);
                inputPituus.setRawInputType(Configuration.KEYBOARD_12KEY);
                inputPituus.setText("" + stepCounterPreferences.getInt("stepCounterUserHeight", 175));

                alert.setView(inputPituus);
                inputPituus.setSelection(inputPituus.getText().length());
                alert.setPositiveButton("Aseta pituus", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int userHeight = 175;
                        String inputPituusText = inputPituus.getText().toString();
                        try {
                            userHeight = Integer.parseInt(inputPituusText);
                        } catch (NumberFormatException e) {
                            //
                        }
                        if (userHeight < 90 || userHeight > 240) {
                            userHeight = 175;
                        }
                        SharedPreferences.Editor editor = stepCounterPreferences.edit();
                        editor.putInt("stepCounterUserHeight", userHeight);
                        editor.commit();
                        txtPituus.setText("" + stepCounterPreferences.getInt("stepCounterUserHeight", 0));
                        updateKilometers(stepCounterPreferences.getInt("todaySteps", 0));
                        //updateCalories(stepCounterPreferences.getInt("todaySteps", 0));
                        updateTrip(stepCounterPreferences.getInt("todaySteps", 0) - stepCounterPreferences.getInt("tripStartSteps", 0));

                        dialog.dismiss();

                    }
                });
                alert.setNegativeButton("Peruuta", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                        dialog.dismiss();
                    }
                });
                alert.create();
                alert.show();
            }
        });

        Button btnMuutaTavoite = (Button) findViewById(R.id.btnMuutaTavoite);
        btnMuutaTavoite.setOnClickListener(new View.OnClickListener()

        {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder alert = new AlertDialog.Builder(TOOL_activity_Askelmittari.this);
                alert.setTitle("Tavoitteen asettaminen");
                alert.setMessage("Aseta tavoite:");
                final EditText inputTavoite = new EditText(TOOL_activity_Askelmittari.this);
                inputTavoite.setInputType(InputType.TYPE_CLASS_NUMBER);
                inputTavoite.setRawInputType(Configuration.KEYBOARD_12KEY);
                inputTavoite.setText("" + stepCounterPreferences.getInt("goal", 10000));

                alert.setView(inputTavoite);
                inputTavoite.setSelection(inputTavoite.getText().length());
                alert.setPositiveButton("Aseta tavoite", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int userGoal = 10000;
                        String inputGoalText = inputTavoite.getText().toString();
                        try {
                            userGoal = Integer.parseInt(inputGoalText);
                        } catch (NumberFormatException e) {
                            //
                        }

                        if (userGoal < 10 || userGoal > 10000000) {
                            userGoal = 10000;
                        }
                        SharedPreferences.Editor editor = stepCounterPreferences.edit();
                        editor.putInt("goal", userGoal);
                        editor.commit();

                        txtTavoite.setText("" + stepCounterPreferences.getInt("goal", 10000));
                        updateCircleProgress(stepCounterPreferences.getInt("todaySteps", 0), userGoal);

                        dialog.dismiss();

                    }
                });
                alert.setNegativeButton("Peruuta", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
                alert.create();
                alert.show();

            }
        });

        btnToggleStepCounting.setOnClickListener(new View.OnClickListener()

        {
            @Override
            public void onClick(View v) {
                boolean isCounting = stepCounterPreferences.getBoolean("isCounting", false);
                if (isCounting) {
                    stopStepCounter();
                } else {
                    startStepCounter();
                }
            }
        });


        Button btnReset = (Button) findViewById(R.id.btnReset);
        btnReset.setOnClickListener(new View.OnClickListener()

        {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder alert = new AlertDialog.Builder(TOOL_activity_Askelmittari.this);
                alert.setTitle("Haluatko varmasti resetoida askelmittarin?");
                alert.setMessage("Tyhjennä askelmittari painamalla kyllä. ");

                alert.setPositiveButton("Tyhjennä askelmittari", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                        SharedPreferences.Editor editor = stepCounterPreferences.edit();
                        editor.putBoolean("startNewFlag", true);
                        editor.commit();

                        updateChart();
                        updateCircleProgress(0, stepCounterPreferences.getInt("goal", 10000));
                        updateKilometers(0);
                        //updateCalories(0);
                        setToggleStepCountingButton();


                    }
                });
                alert.setNegativeButton("Peruuta", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
                alert.create();
                alert.show();

            }
        });

        Button btnTrip = (Button) findViewById(R.id.btnTrip);
        btnTrip.setOnClickListener(new View.OnClickListener()

        {
            @Override
            public void onClick(View v) {
                SharedPreferences.Editor editor = stepCounterPreferences.edit();
                boolean isTripCounting = stepCounterPreferences.getBoolean("isTripCounting", false);
                if (isTripCounting) {
                    editor.putBoolean("isTripCounting", false);
                    editor.commit();
                    updateTrip(0);
                } else {
                    editor.putBoolean("isTripCounting", true);
                    editor.putInt("tripStartSteps", stepCounterPreferences.getInt("todaySteps", 0));
                    editor.commit();
                    updateTrip(0);
                }

            }
        });

    }


    @Override
    public void onResume() {
        super.onResume();

        mSensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
        updateChart();
        updateCircleProgress(stepCounterPreferences.getInt("todaySteps", 0), stepCounterPreferences.getInt("goal", 10000));
        updateKilometers(stepCounterPreferences.getInt("todaySteps", 0));
        //updateCalories(stepCounterPreferences.getInt("todaySteps", 0));
        updateTrip(stepCounterPreferences.getInt("todaySteps", 0) - stepCounterPreferences.getInt("tripStartSteps", 0));
        setToggleStepCountingButton();


    }

    @Override
    public void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }


    @Override
    public void onSensorChanged(SensorEvent event) {
        boolean startNewFlag = stepCounterPreferences.getBoolean("startNewFlag", true);
        final int goal = stepCounterPreferences.getInt("goal", 10000);

        if (startNewFlag) {

            int steps = 0;
            if (sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                steps = 0;
            } else if (sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
                steps = (int) event.values[0];
            }

            aloitaAskelmittariTyhjasta(steps);

        }


        boolean isCounting = stepCounterPreferences.getBoolean("isCounting", false);

        if (isCounting) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateCircleProgress(stepCounterPreferences.getInt("todaySteps", 0), goal);
                    updateTrip(stepCounterPreferences.getInt("todaySteps", 0) - stepCounterPreferences.getInt("tripStartSteps", 0));
                }
            });


        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    private void aloitaAskelmittariTyhjasta(int steps) {
        //Log.i("TOOL_activity_Askelmittari", "onSensorChanged(), aloitettu laskeminen");
        SharedPreferences.Editor editor = stepCounterPreferences.edit();
        editor.putBoolean("startNewFlag", false);

        Calendar currentDate = Calendar.getInstance();
        SimpleDateFormat dateForDatabaseFormat = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat uiDateFormat = new SimpleDateFormat("d.M");
        String dateForDatabase = dateForDatabaseFormat.format(currentDate.getTime());

        Calendar endDate = Calendar.getInstance();
        endDate.add(Calendar.DAY_OF_YEAR, stepCounterPreferences.getInt("stepCounterDurationInDays", 30));

        int jobId = new JobRequest.Builder(TOOL_job_StepCounterJob.TAG)
                .setExecutionWindow(30_000L, 60_000L)
                .setBackoffCriteria(5_000L, JobRequest.BackoffPolicy.EXPONENTIAL)
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false)
                .setRequiredNetworkType(JobRequest.NetworkType.ANY)
                .setRequirementsEnforced(true)
                .setPersisted(true)
                .setUpdateCurrent(true)
                .build()
                .schedule();

        try {
            Dao<TOOL_tietokanta_StepCountDaily, Integer> dao = getHelper().getStepCountDailyDao();
            //TableUtils.clearTable(getHelper().getConnectionSource(), TOOL_tietokanta_StepCountDaily.class);
            TOOL_tietokanta_StepCountDaily startingStepCount = new TOOL_tietokanta_StepCountDaily();
            startingStepCount.setStepcount(0);
            startingStepCount.setPvm(dateForDatabase);
            dao.createOrUpdate(startingStepCount);

            editor.putLong("lastSaveTime", currentDate.getTimeInMillis());
            editor.putInt("lastSaveCount", steps);
            editor.putInt("todaySteps", 0);
            editor.putBoolean("isCounting", true);
            editor.putInt("stepCounterPreviousValue", steps);
            editor.putString("stepCounterStartDate", dateForDatabaseFormat.format(currentDate.getTime()));
            editor.putString("stepCounterEndDate", dateForDatabaseFormat.format(endDate.getTime()));

            TextView txtAloitusPvm = (TextView) findViewById(R.id.txtAloitusPvm);
            TextView txtLopetusPvm = (TextView) findViewById(R.id.txtLopetusPvm);
            endDate.add(Calendar.DAY_OF_YEAR, -1);
            editor.putString("stepCounterStartDateForUi", uiDateFormat.format(currentDate.getTime()));
            editor.putString("stepCounterEndDateForUi", uiDateFormat.format(endDate.getTime()));
            txtAloitusPvm.setText(uiDateFormat.format(currentDate.getTime()));
            txtLopetusPvm.setText(uiDateFormat.format(endDate.getTime()));

            editor.commit();

            //Log.i("TOOL_activity_Askelmittari", "onSensorChanged(), Päättymispäivä. " + dateForDatabaseFormat.format(endDate.getTime()));
            //Log.i("TOOL_activity_Askelmittari", "onSensorChanged(), Tallennettu aloitus. Tallennettu sensorin arvo: " + steps);

        } catch (SQLException e) {
            e.printStackTrace();
        }

        startService(new Intent(this, TOOL_service_StepCounterService.class));
        setToggleStepCountingButton();
        //Intent intent = new Intent(this, TOOL_service_StepCounterService.class);
        //bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void setToggleStepCountingButton() {
        //boolean firstTimeFinished = stepCounterPreferences.getBoolean("firstTimeFinished", false);
        //if (firstTimeFinished) {
            btnToggleStepCounting.setVisibility(View.VISIBLE);

            boolean isCounting = stepCounterPreferences.getBoolean("isCounting", false);
            if (isCounting) {
                btnToggleStepCounting.setText("Lopeta laskeminen");
            } else {
                btnToggleStepCounting.setText("Aloita laskeminen");
            }
        //}
    }


    private void updateCircleProgress(int todaySteps, int goal) {
        DonutProgress askelProgress = (DonutProgress) findViewById(R.id.askelProgress);
        float progress = Math.round(((float) todaySteps / goal) * 100);

        askelProgress.setText("" + todaySteps);
        if (progress > 100) {
            askelProgress.setProgress(100);
        } else {
            askelProgress.setProgress(progress);
        }

    }

    private void initializeChart() {
        askeldata = new XYSeries("Askeleet");
        mRenderer = new XYMultipleSeriesRenderer();
        seriesRendererAskeldata = new XYSeriesRenderer();
        dataset = new XYMultipleSeriesDataset();

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        final float fontSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, metrics);

        seriesRendererAskeldata.setColor(Color.BLUE);
        seriesRendererAskeldata.setFillPoints(true);
        seriesRendererAskeldata.setLineWidth(20);
        seriesRendererAskeldata.setDisplayChartValues(false);

        mRenderer.addSeriesRenderer(0, seriesRendererAskeldata);
        mRenderer.setShowLegend(false);

        float leftMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 70, metrics);
        mRenderer.setMarginsColor(Color.argb(0x00, 0xff, 0x00, 0x00));
        mRenderer.setMargins(new int[]{35, Math.round(leftMargin), 25, 85});

        mRenderer.setPanEnabled(false, false);
        mRenderer.setZoomEnabled(false, false);
        mRenderer.setShowGrid(false);

        mRenderer.setBackgroundColor(Color.TRANSPARENT);

        mRenderer.setAxisTitleTextSize(fontSize);
        mRenderer.setLabelsTextSize(fontSize);

        mRenderer.setYTitle("Askelten määrä");
        mRenderer.setYLabelsAlign(Paint.Align.RIGHT);
        mRenderer.setYLabelsPadding(24);

        mRenderer.setXLabelsPadding(6);
        mRenderer.setXLabelsAlign(Paint.Align.CENTER);
        mRenderer.setXAxisMin(-0.48); // -0.08

        //mRenderer.setInitialRange();
        float barWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15, metrics);
        mRenderer.setBarWidth(barWidth);
        mRenderer.setBarSpacing(0.25);
        mRenderer.setOrientation(XYMultipleSeriesRenderer.Orientation.HORIZONTAL);

        dataset.addSeries(0, askeldata);

        chartView = ChartFactory.getBarChartView(this, dataset, mRenderer, BarChart.Type.DEFAULT);
        FrameLayout chartContainer = (FrameLayout) findViewById(R.id.chartContainer);
        chartContainer.addView(chartView);
    }

    private void updateKilometers(int todaySteps) {
        TextView txtKilometrit = (TextView) findViewById(R.id.textViewKilometrit);
        double kilometersToday = calculateKilometres(todaySteps);
        txtKilometrit.setText(kilometersToday + " kilometriä");
    }

    private void updateTrip(int tripSteps) {

        boolean isTripCounting = stepCounterPreferences.getBoolean("isTripCounting", false);
        TextView txtKalorit = (TextView) findViewById(R.id.textViewTrip);
        if (isTripCounting) {
            txtKalorit.setText(tripSteps + "");
        } else {
            txtKalorit.setText("Ei aktiivinen");
        }

    }

    private void updateCalories(int todaySteps) { // Vai aika?
        /*
        TextView txtKalorit = (TextView) findViewById(R.id.textViewKalorit);
        double kilometersToday = calculateKilometres(todaySteps);
        int caloriesInKilometers = 65;
        int caloriesToday = (int) (caloriesInKilometers * kilometersToday);
        txtKalorit.setText(caloriesToday + " kaloria");*/
    }

    private double calculateKilometres(int todaySteps) {
        String sukupuoli = stepCounterPreferences.getString("stepCounterUserGender", "mies");

        double askelpituus = 0.415;
        double pituus = 178;

        if (stepCounterPreferences.getInt("stepCounterUserHeight", 0) != 0) {
            pituus = stepCounterPreferences.getInt("stepCounterUserHeight", 0);
        } else {
            if (sukupuoli.equals("mies")) {
                pituus = 178;
            } else if (sukupuoli.equals("nainen")) {
                pituus = 165;
            }
        }

        if (sukupuoli.equals("mies")) {
            askelpituus = 0.415 * pituus;
        } else if (sukupuoli.equals("nainen")) {
            askelpituus = 0.413 * pituus;
        }

        double askelPituusMetri = askelpituus / 100;
        double metersToday = (todaySteps * askelPituusMetri);
        double kilometers = Math.round((metersToday / 1000) * 100.0) / 100.0;

        return kilometers;
    }


    private void updateChart() {

        try {
            Dao<TOOL_tietokanta_StepCountDaily, Integer> dao = getHelper().getStepCountDailyDao();

            long maxUnits = dao.queryRawValue("SELECT max(stepcount) stepcount_sum FROM stepcountdaily GROUP BY strftime('%Y-%m-%d', pvm)");

            if (maxUnits < 10000) {
                maxUnits = 10000;
            }

            askeldata.clear();

            mRenderer.setYAxisMax(maxUnits);
            mRenderer.setYAxisMin(0);
            mRenderer.setXLabels(0);

            GenericRawResults<String[]> rawResults = dao.queryRaw("SELECT pvm, sum(stepcount) stepcount_sum FROM stepcountdaily GROUP BY strftime('%Y-%m-%d %H', pvm)");
            List<String[]> results = rawResults.getResults();
            mRenderer.clearXTextLabels();

            if (results.size() > 0) {
                SimpleDateFormat dateForDatabaseFormat = new SimpleDateFormat("yyyy-MM-dd");
                SimpleDateFormat dateForLabelFormat = new SimpleDateFormat("d.M");

                Calendar dateForLabel = Calendar.getInstance();
                for (int i = 0; i < results.size(); i++) {
                    String[] resultArray = results.get(i);

                    askeldata.add(i, Double.valueOf(resultArray[1]));
                    try {
                        dateForLabel.setTime(dateForDatabaseFormat.parse(resultArray[0]));
                        mRenderer.addXTextLabel(i, dateForLabelFormat.format(dateForLabel.getTime()));

                    } catch (ParseException e) {
                        e.printStackTrace();
                    }
                }
            }

            if (results.size() < 6) {
                for (int j = 0; j < (6 - results.size()); j++) {
                    askeldata.add(results.size() + j, 0);
                    mRenderer.addXTextLabel(results.size() + j, "");
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        if (chartView != null) {
            chartView.repaint();
        }
    }

    private void stopStepCounter() {
        btnToggleStepCounting.setText("Aloita laskeminen");
        //JobManager.instance().cancelAllForTag(TOOL_job_StepCounterJob.TAG);
        stopService(new Intent(this, TOOL_service_StepCounterService.class));
        SharedPreferences.Editor editor = stepCounterPreferences.edit();
        editor.putBoolean("isCounting", false);
        editor.commit();

    }

    private void startStepCounter() {

        btnToggleStepCounting.setText("Lopeta laskeminen");
        int jobId = new JobRequest.Builder(TOOL_job_StepCounterJob.TAG)
                .setExecutionWindow(30_000L, 60_000L)
                .setBackoffCriteria(5_000L, JobRequest.BackoffPolicy.EXPONENTIAL)
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false)
                .setRequiredNetworkType(JobRequest.NetworkType.ANY)
                .setRequirementsEnforced(true)
                .setPersisted(true)
                .setUpdateCurrent(true)
                .build()
                .schedule();
        startService(new Intent(this, TOOL_service_StepCounterService.class));
        SharedPreferences.Editor editor = stepCounterPreferences.edit();
        editor.putBoolean("isCounting", true);
        editor.commit();

    }

    private TOOL_tietokanta_DatabaseHelper getHelper() {
        if (databaseHelper == null) {
            databaseHelper =
                    OpenHelperManager.getHelper(this, TOOL_tietokanta_DatabaseHelper.class);
        }
        return databaseHelper;
    }

}

