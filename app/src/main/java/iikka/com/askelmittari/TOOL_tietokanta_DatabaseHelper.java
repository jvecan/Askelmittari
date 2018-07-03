package iikka.com.askelmittari;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.j256.ormlite.android.apptools.OrmLiteSqliteOpenHelper;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.TableUtils;

import java.sql.SQLException;

public class TOOL_tietokanta_DatabaseHelper extends OrmLiteSqliteOpenHelper {

    private static final String DATABASE_NAME = "stepcount.db";
    private static final int DATABASE_VERSION = 1;

    private Dao<TOOL_tietokanta_StepCountDaily, Integer> stepCountDao;


    public TOOL_tietokanta_DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase sqliteDatabase, ConnectionSource connectionSource) {
        try {
            TableUtils.createTable(connectionSource, TOOL_tietokanta_StepCountDaily.class);


        } catch (SQLException e) {
            Log.e(TOOL_tietokanta_DatabaseHelper.class.getName(), "Unable to create databases", e);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqliteDatabase, ConnectionSource connectionSource, int oldVer, int newVer) {

    }

    public Dao<TOOL_tietokanta_StepCountDaily, Integer> getStepCountDailyDao() throws SQLException {
        if (stepCountDao == null) {
            stepCountDao = getDao(TOOL_tietokanta_StepCountDaily.class);
        }
        return stepCountDao;
    }



}
