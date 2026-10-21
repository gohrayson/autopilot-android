package app.autopilot.android;

import android.app.job.*;

/** Android owns the persisted periodic schedule; work runs outside the main thread. */
public final class UpdateJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        ((AutopilotApp) getApplication()).updates.backgroundCheck(() -> jobFinished(params, false));
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        ((AutopilotApp)getApplication()).updates.cancelBackgroundWork();
        return true;
    }
}
