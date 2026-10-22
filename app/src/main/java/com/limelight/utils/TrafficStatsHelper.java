package com.limelight.utils;

import android.net.TrafficStats;

public class TrafficStatsHelper {
    public static long getPackageRxBytes(int uid) {
        return TrafficStats.getUidRxBytes(uid);
    }

    public static long getPackageTxBytes(int uid) {
        return TrafficStats.getUidTxBytes(uid);
    }
}
