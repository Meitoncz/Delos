package com.limelight.grid.assets;

import android.content.Context;
import com.limelight.utils.CacheHelper;

import java.io.File;

public class DiskAssetLoader {
    private final File cacheDir;

    public DiskAssetLoader(Context context) {
        this.cacheDir = context.getCacheDir();
    }

    public File getFile(String computerUuid, int appId) {
        return CacheHelper.openPath(false, cacheDir, "boxart", computerUuid, appId + ".png");
    }

    public void deleteAssetsForComputer(String computerUuid) {
        File dir = CacheHelper.openPath(false, cacheDir, "boxart", computerUuid);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

}
