package com.limelight;

import com.limelight.diag.DiagLog;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Logger;

public class LimeLog {
    private static final Logger LOGGER = Logger.getLogger(LimeLog.class.getName());
    // The tag of these lines in the app's own diagnostic log (docs/DIAGNOSTICS.md)
    private static final String TAG = "Lime";

    public static void info(String msg) {
        LOGGER.info(msg);
        DiagLog.i(TAG, msg);
    }
    
    public static void warning(String msg) {
        LOGGER.warning(msg);
        DiagLog.w(TAG, msg);
    }
    
    public static void severe(String msg) {
        LOGGER.severe(msg);
        DiagLog.e(TAG, msg);
    }
    
    public static void setFileHandler(String fileName) throws IOException {
        LOGGER.addHandler(new FileHandler(fileName));
    }
}
