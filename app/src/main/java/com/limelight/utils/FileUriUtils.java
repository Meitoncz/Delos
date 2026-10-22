package com.limelight.utils;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;

/**
 * Description
 * Date: 2024-03-20
 * Time: 13:46
 */
public class FileUriUtils {
    public static String openUriForRead(Context context, Uri uri) {
        if (uri == null)
            return "";
        InputStream inputStream = null;
        Reader reader = null;
        BufferedReader bufferedReader = null;
        StringBuilder result = new StringBuilder();
        try {
            inputStream = context.getContentResolver().openInputStream(uri);
            reader = new InputStreamReader(inputStream);
            bufferedReader = new BufferedReader(reader);
            String temp;
            while ((temp = bufferedReader.readLine()) != null) {
                result.append(temp);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return result.toString();
    }

    public static boolean writerFileString(File file, String content) {
        FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = new FileOutputStream(file);
            fileOutputStream.write(content.getBytes());
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return true;
    }
}
