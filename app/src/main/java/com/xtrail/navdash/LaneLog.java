package com.xtrail.navdash;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 车道/导航广播落盘日志：追加写入手机存储，供用户驾车测试后连上手机读取。
 *
 * 存储位置：
 *  - Android 10+：公共"下载"目录（MediaStore.Downloads，无需存储权限，手机文件管理器/MTP 可见）
 *    → /storage/emulated/0/Download/xtrail_lane.log
 *  - Android 9-：App 外部专属目录（需 WRITE_EXTERNAL_STORAGE 权限）
 *    → /storage/emulated/0/Android/data/com.xtrail.navdash/files/Download/xtrail_lane.log
 *
 * 日志格式：每行 = 时间戳 + 内容，UTF-8。
 */
public final class LaneLog {

    private static final String FILE = "xtrail_lane.log";
    private static final String DISPLAY = "xtrail_lane.log";

    private LaneLog() {}

    /** 追加一行日志（带时间戳）。任何失败静默，绝不影响导航解析。 */
    public static void log(Context c, String line) {
        try {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            String entry = ts + "  " + line + "\n";
            if (Build.VERSION.SDK_INT >= 29) {
                writeMediaStore(c, entry);
            } else {
                writeLegacy(c, entry);
            }
        } catch (Exception ignored) {
        }
    }

    /** 读取最近 maxLines 行（供首页"查看日志"按钮）。 */
    public static String read(Context c, int maxLines) {
        StringBuilder sb = new StringBuilder();
        try {
            InputStream is;
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = findExisting(c);
                if (uri == null) return "（暂无日志，先跑一次导航）";
                is = c.getContentResolver().openInputStream(uri);
            } else {
                File f = legacyFile(c);
                if (f == null || !f.exists()) return "（暂无日志，先跑一次导航）";
                is = new FileInputStream(f);
            }
            if (is == null) return "（读取失败）";
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                Deque<String> q = new ArrayDeque<>();
                String line;
                while ((line = br.readLine()) != null) q.add(line);
                while (q.size() > maxLines) q.removeFirst();
                for (String s : q) sb.append(s).append('\n');
            }
        } catch (Exception e) {
            return "读取失败: " + e;
        }
        return sb.toString();
    }

    /** 日志文件绝对路径（用于提示用户/adb pull）。 */
    public static String path(Context c) {
        if (Build.VERSION.SDK_INT >= 29) {
            return "/storage/emulated/0/Download/" + FILE;
        }
        File f = legacyFile(c);
        return f != null ? f.getAbsolutePath() : ("Download/" + FILE);
    }

    /** 清空日志文件。 */
    public static void clear(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = findExisting(c);
                if (uri != null) c.getContentResolver().delete(uri, null, null);
            } else {
                File f = legacyFile(c);
                if (f != null && f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- Android 10+：MediaStore.Downloads ----------
    private static void writeMediaStore(Context c, String entry) throws IOException {
        Uri uri = findExisting(c);
        if (uri == null) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, DISPLAY);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        }
        if (uri == null) return;
        try (OutputStream os = c.getContentResolver().openOutputStream(uri, "wa")) {
            if (os != null) os.write(entry.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Uri findExisting(Context c) {
        try (Cursor cur = c.getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{DISPLAY}, null)) {
            if (cur != null && cur.moveToFirst()) {
                return ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(0));
            }
        }
        return null;
    }

    // ---------- Android 9-：App 专属外部目录（需 WRITE 权限） ----------
    private static File legacyFile(Context c) {
        File dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return dir != null ? new File(dir, FILE) : null;
    }

    private static void writeLegacy(Context c, String entry) throws IOException {
        File f = legacyFile(c);
        if (f == null) return;
        try (FileOutputStream fos = new FileOutputStream(f, true)) {
            fos.write(entry.getBytes(StandardCharsets.UTF_8));
        }
    }
}
