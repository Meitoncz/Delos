package com.limelight;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.limelight.utils.CacheHelper;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

public class PosterContentProvider extends ContentProvider {


    public static final String AUTHORITY = "poster." + BuildConfig.APPLICATION_ID;
    public static final String PNG_MIME_TYPE = "image/png";
    public static final int APP_ID_PATH_INDEX = 2;
    public static final int COMPUTER_UUID_PATH_INDEX = 1;

    private static final UriMatcher sUriMatcher;
    private static final String BOXART_PATH = "boxart";
    private static final int BOXART_URI_ID = 1;

    static {
        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(AUTHORITY, BOXART_PATH, BOXART_URI_ID);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int match = sUriMatcher.match(uri);
        if (match == BOXART_URI_ID) {
            return openBoxArtFile(uri, mode);
        }
        return openBoxArtFile(uri, mode);

    }

    public ParcelFileDescriptor openBoxArtFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new UnsupportedOperationException("This provider is only for read mode");
        }

        List<String> segments = uri.getPathSegments();
        if (segments.size() != 3) {
            throw new FileNotFoundException();
        }
        File file = resolveBoxArtFile(getContext().getCacheDir(),
                segments.get(COMPUTER_UUID_PATH_INDEX), segments.get(APP_ID_PATH_INDEX));
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * Maps untrusted URI segments to an existing cached box art file. The provider is exported,
     * so the result is guaranteed to be a regular file inside {@code <cacheDir>/boxart}.
     *
     * @throws FileNotFoundException if the ids are malformed, escape the cache or the file is missing
     */
    static File resolveBoxArtFile(File cacheDir, String uuid, String appId) throws FileNotFoundException {
        if (uuid == null || uuid.isEmpty() || uuid.equals(".") || uuid.equals("..")
                || uuid.indexOf('/') >= 0 || uuid.indexOf('\\') >= 0
                || uuid.indexOf('%') >= 0 || uuid.indexOf('\0') >= 0) {
            throw new FileNotFoundException();
        }
        if (appId == null || appId.isEmpty() || appId.length() > 10) {
            throw new FileNotFoundException();
        }
        for (int i = 0; i < appId.length(); i++) {
            char c = appId.charAt(i);
            if (c < '0' || c > '9') {
                throw new FileNotFoundException();
            }
        }
        int id;
        try {
            id = Integer.parseInt(appId);
        } catch (NumberFormatException e) {
            throw new FileNotFoundException();
        }

        try {
            File boxArtRoot = new File(cacheDir, BOXART_PATH).getCanonicalFile();
            File file = CacheHelper.openPath(false, cacheDir, BOXART_PATH, uuid, id + ".png").getCanonicalFile();
            if (file.getPath().startsWith(boxArtRoot.getPath() + File.separator) && file.isFile()) {
                return file;
            }
        } catch (IOException | IllegalArgumentException e) {
            // fall through
        }
        throw new FileNotFoundException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("This provider is only for read mode");
    }

    @Override
    public String getType(Uri uri) {
        return PNG_MIME_TYPE;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("This provider is only for read mode");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("This provider doesn't support query");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException("This provider is support read only");
    }


    public static Uri createBoxArtUri(String uuid, String appId) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(BOXART_PATH)
                .appendPath(uuid)
                .appendPath(appId)
                .build();
    }

}
