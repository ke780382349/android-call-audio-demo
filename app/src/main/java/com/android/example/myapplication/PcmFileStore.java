package com.android.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class PcmFileStore {

    static final String TARGET_UPLINK = "uplink";
    static final String TARGET_DOWNLINK = "downlink";

    private static final String PREFERENCES_NAME = "pcm_injection_selection";
    private static final String KEY_UPLINK_PATH = "uplink_pcm_path";
    private static final String KEY_DOWNLINK_PATH = "downlink_pcm_path";

    private PcmFileStore() {
    }

    static File getPcmDirectory(Context context) {
        File baseDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (baseDirectory == null) {
            baseDirectory = context.getFilesDir();
        }
        return new File(baseDirectory, "call_pcm");
    }

    static List<File> findAllPcmFiles(Context context) {
        File[] files = getPcmDirectory(context).listFiles(file -> file != null
                && file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".pcm"));
        if (files == null) {
            return new ArrayList<>();
        }
        Arrays.sort(files, (left, right) -> Long.compare(
                right.lastModified(), left.lastModified()));
        return Arrays.asList(files);
    }

    static boolean isUsablePcm(Context context, @Nullable File file) {
        if (file == null
                || !file.isFile()
                || !file.getName().toLowerCase(Locale.ROOT).endsWith(".pcm")
                || file.length() <= 0
                || (file.length() & 1) != 0) {
            return false;
        }
        try {
            File parent = file.getCanonicalFile().getParentFile();
            return parent != null
                    && parent.equals(getPcmDirectory(context).getCanonicalFile());
        } catch (IOException exception) {
            return false;
        }
    }

    @Nullable
    static File getSelectedPcm(Context context, String target) {
        SharedPreferences preferences = preferences(context);
        String key = preferenceKey(target);
        String path = preferences.getString(key, null);
        if (path == null) {
            return null;
        }
        File selectedFile = new File(path);
        if (isUsablePcm(context, selectedFile)) {
            return selectedFile;
        }
        preferences.edit().remove(key).apply();
        return null;
    }

    static boolean selectPcm(Context context, String target, File file) {
        if (!isUsablePcm(context, file)) {
            return false;
        }
        preferences(context).edit()
                .putString(preferenceKey(target), file.getAbsolutePath())
                .apply();
        return true;
    }

    static void clearSelectionsForFile(Context context, File file) {
        SharedPreferences preferences = preferences(context);
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (String key : new String[]{KEY_UPLINK_PATH, KEY_DOWNLINK_PATH}) {
            String path = preferences.getString(key, null);
            if (path != null && sameFile(file, new File(path))) {
                editor.remove(key);
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static String preferenceKey(String target) {
        return TARGET_DOWNLINK.equals(target) ? KEY_DOWNLINK_PATH : KEY_UPLINK_PATH;
    }

    private static boolean sameFile(File left, File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (IOException exception) {
            return left.getAbsoluteFile().equals(right.getAbsoluteFile());
        }
    }
}
