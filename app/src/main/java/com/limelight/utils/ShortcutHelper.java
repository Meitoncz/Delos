package com.limelight.utils;

import android.app.Activity;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

public class ShortcutHelper {

    private final TvChannelHelper tvChannelHelper;

    public static final String KEY_HOST_UUID = "host_uuid";
    public static final String KEY_HOST_NAME = "host_name";
    public static final String KEY_APP_UUID = "app_uuid";
    public static final String KEY_APP_NAME = "app_name";
    public static final String KEY_APP_ID = "app_id";

    public ShortcutHelper(Activity context) {
        this.tvChannelHelper = new TvChannelHelper(context);
    }

    public void reportGameLaunched(ComputerDetails computer, NvApp app) {
        tvChannelHelper.createTvChannel(computer);
        tvChannelHelper.addGameToChannel(computer, app);
    }
}
