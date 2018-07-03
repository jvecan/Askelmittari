package iikka.com.askelmittari;

import android.content.Context;
import android.support.annotation.NonNull;

import com.evernote.android.job.Job;
import com.evernote.android.job.JobCreator;
import com.evernote.android.job.JobManager;

public class TOOL_job_EvernoteJobCreator implements JobCreator {

    @Override
    public Job create(String tag) {
        switch (tag) {
            case TOOL_job_StepCounterJob.TAG:
                return new TOOL_job_StepCounterJob();
            default:
                return null;
        }
    }

    public static final class AddReceiver extends AddJobCreatorReceiver {
        @Override
        protected void addJobCreator(@NonNull Context context, @NonNull JobManager manager) {
            manager.addJobCreator(new TOOL_job_EvernoteJobCreator());
        }
    }
}
