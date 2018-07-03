package iikka.com.askelmittari;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = "stepcountdaily")

public class TOOL_tietokanta_StepCountDaily {

    @DatabaseField (generatedId=true)
    private int id;

    @DatabaseField (unique=true)
    private String pvm;

    @DatabaseField
    private int stepcount;

    public TOOL_tietokanta_StepCountDaily() {

    }

    public TOOL_tietokanta_StepCountDaily(int id, String pvm, int stepcount) {
        this.id = id;
        this.pvm = pvm;
        this.stepcount = stepcount;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getPvm() {
        return pvm;
    }

    public void setPvm(String pvm) {
        this.pvm = pvm;
    }

    public int getStepcount() {
        return stepcount;
    }

    public void setStepcount(int stepcount) {
        this.stepcount = stepcount;
    }


}
