package com.limelight;

import android.app.Application;
import com.limelight.diag.CrashReporter;
import com.limelight.diag.DiagLog;
import com.limelight.utils.DelosToast;
import com.limelight.utils.ToastKind;

import com.limelight.profiles.ProfilesManager;

public class DelosApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // The diagnostic log starts first, so everything that follows can be in it (docs/DIAGNOSTICS.md)
        DiagLog.init(this);
        CrashReporter.INSTANCE.install(this);
        ProfilesManager profilesManager = ProfilesManager.getInstance();
        if (!profilesManager.load(this)) {
            DelosToast.show(this, R.string.profile_manager_failed_to_load, DelosToast.LONG, ToastKind.ERROR);
        }
    }
}