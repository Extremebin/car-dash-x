package com.xtrail.navdash;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * X 计划核心：共享 MediaSession + 本地音乐播放器。
 *
 * 关键设计（解决方案 A 的音乐冲突）：
 *   - 本 Service 是【唯一】持有 MediaSession 的组件。车机只看到这一个 session。
 *   - 音乐通过 ExoPlayer 持续播放（真音频 → A2DP 出声）。
 *   - 导航时【不打断音乐】，只把 metadata 的 title/album 换成导航文字。
 *     车机只看 title 变化就刷新（方案 A 已实证），所以导航文字能实时更新，音乐继续播。
 *   - 导航结束，metadata 恢复为歌曲信息。
 *
 * 仪表盘两行区：title(歌名区) + album(专辑区)。导航时 title=主指令，album=距离。
 */
public final class DashMediaService extends Service {

    private static final String CHANNEL = "dash_media";
    private static final int NOTIFICATION_ID = 20;
    private static final String TAG_AMAP = "CarDashX/AMap";   // 车机版广播日志 tag

    public static final String ACTION_START = "com.xtrail.navdash.START";
    public static final String ACTION_STOP = "com.xtrail.navdash.STOP";
    public static final String ACTION_NAV = "com.xtrail.navdash.NAV";   // 更新导航文字
    public static final String ACTION_MUSIC = "com.xtrail.navdash.MUSIC"; // 扫描/播放
    public static final String ACTION_PROBE = "com.xtrail.navdash.PROBE"; // 字段探测
    public static final String ACTION_CHARSET = "com.xtrail.navdash.CHARSET"; // 字符集测试（点一次翻一页）
    public static final String ACTION_CHARSET_STOP = "com.xtrail.navdash.CHARSET_STOP"; // 结束字符集测试

    public static final String EXTRA_NAV_LINE1 = "nav_line1";   // 主指令（标题区）
    public static final String EXTRA_NAV_LINE2 = "nav_line2";   // 距离（专辑区）
    public static final String EXTRA_NAV_ON = "nav_on";         // 是否处于导航
    public static final String ACTION_STATUS = "com.xtrail.navdash.STATUS";
    public static final String EXTRA_STATUS_TITLE = "status_title";
    public static final String EXTRA_STATUS_ARTIST = "status_artist";
    public static final String EXTRA_STATUS_NAV = "status_nav";
    public static final String EXTRA_STATUS_TRACK = "status_track";

    private static DashMediaService instance;
    private static volatile String lastTitle = "-";
    private static volatile String lastArtist = "-";
    private static volatile String lastNavi = "-";
    private static volatile String lastTrack = "-";
    private static volatile String lastBroadcastRaw = "";   // 最近一条车机版广播原始字段（调试）
    private static volatile String lastKeyType = "-";       // 最近 KEY_TYPE
    private static volatile boolean receiverRegistered = false; // 接收器是否已成功注册
    private static volatile int broadcastCount = 0;             // 收到车机版广播次数
    private static volatile long lastBroadcastMs = 0;           // 最近收到时间

    private BroadcastReceiver amapReceiver;   // 车机版标准广播接收器

    private MediaSession session;      // 平台 MediaSession（方案 A 同款，AVRCP 元数据）
    private ExoPlayer player;
    private List<MediaItem> playlist = new ArrayList<>();

    // 导航覆盖态：true 时 metadata 显示导航文字（音乐照播）
    private boolean navOn = false;
    private String navLine1 = "";
    private String navLine2 = "";
    private String navBaseLine1 = "";   // line1 基础（不含读秒），跨广播持久
    private String lastRedLight = "";   // 最近一次红绿灯读秒（持久，供合并到 line2）
    private int lastRedSec = 0;         // 最近红绿灯读秒（仅秒数，供 line1 尾部）
    private String lastLightPhase = ""; // 最近红绿灯相位（红/绿，供 line1 读秒标注）
    private String lastLane = "";       // 最近一次车道指引（持久，调试用）
    private long lastLaneMs = 0;        // 最近一次车道信息更新时间（供解析器做新鲜度判断）
    private String lastNextRoad = "";   // 最近即将进入的路（供 line2）
    private String lastCurRoad = "";    // 最近当前路（供 line2）
    private static int lastSpeed = -1;  // 最近当前车速 km/h（供顶部 TRACK_NUMBER），-1=未知
    private long lastLogMs = 0;         // 落盘日志节流（非 13012 类型每 2 秒最多一条）

    // 字段探测：true 时 metadata 各字段设为不同标记值，用于在仪表盘上定位字段映射
    private boolean probeActive = false;
    private final Handler probeHandler = new Handler(Looper.getMainLooper());

    // 字符集测试：每次点击递增一页；页末再点恢复普通显示
    private boolean charsetActive = false;
    private int charsetIndex = 0;
    private static final String[][] CHARSET_PAGES = {
            {"←↑→↓",     "P1 基础箭头"},
            {"↖↗↘↙",     "P2 斜箭头"},
            {"↰↱↩↪",     "P3 转向/掉头"},
            {"↶↷⤶⤷",     "P4 掉头弯钩"},
            {"⇆⇄⮪⮫",     "P5 掉头箭头"},
            {"⟳⟲↻↺",     "P6 环岛圆转"},
            {"◎⊙◯○●",    "P7 圆圈"},
            {"🔴🟢❤💚",   "P8 彩色红绿"},
            {"红灯绿灯",   "P9 中文红绿"},
            {"红绿黄灯",   "P10 中文红绿2"},
            {"A1zZ09",    "P11 字母数字"},
            {"·:｜%·",    "P12 标点"},
            {"←掉头↻环",  "P13 组合"},
            {"↩12秒",     "P14 掉头+秒"},
    };

    private static String lastLaneShown = "";   // 最近车道指引（供首页调试显示）
    private static String lastLaneJson = "";    // 最近车道原始 JSON（供弹窗显示）

    public static String lastTitle() { return lastTitle; }
    public static String lastArtist() { return lastArtist; }
    public static String lastNavi() { return lastNavi; }

    /** HUD 主指令行（导航时），未导航返回 "". */
    public static String lastNaviLine1() {
        return instance != null && instance.navOn ? instance.navLine1 : "";
    }

    /** HUD 副行（距离等），未导航返回 "". */
    public static String lastNaviLine2() {
        return instance != null && instance.navOn ? instance.navLine2 : "";
    }

    /** 最近一次红绿灯读秒（无则 ""）。 */
    public static String lastRedLight() {
        return instance != null ? instance.lastRedLight : "";
    }

    /** 当前曲目专辑名（无则 ""，供播放器描述行）。 */
    public static String lastAlbum() {
        if (instance == null || instance.player == null) return "";
        MediaItem cur = instance.player.getCurrentMediaItem();
        return cur != null && cur.mediaMetadata.albumTitle != null
                ? cur.mediaMetadata.albumTitle.toString() : "";
    }

    /** 当前正在播放的文件标题（真实媒体信息，非导航伪装，无则 ""）。 */
    public static String nowPlayingTitle() {
        if (instance == null || instance.player == null) return "";
        MediaItem cur = instance.player.getCurrentMediaItem();
        return cur != null && cur.mediaMetadata.title != null
                ? cur.mediaMetadata.title.toString() : "";
    }

    /** 当前正在播放的文件艺术家（真实媒体信息，非导航伪装，无则 ""）。 */
    public static String nowPlayingArtist() {
        if (instance == null || instance.player == null) return "";
        MediaItem cur = instance.player.getCurrentMediaItem();
        return cur != null && cur.mediaMetadata.artist != null
                ? cur.mediaMetadata.artist.toString() : "";
    }

    /** 设置（DisplayPrefs）变更后调用，立即按新配置重发布元数据。 */
    public static void refreshDisplay() {
        if (instance != null) instance.publish();
    }
    public static String lastTrack() { return lastTrack; }
    public static String lastLane() { return lastLaneShown; }
    public static int lastSpeed() { return lastSpeed; }
    public static String lastLaneJson() { return lastLaneJson; }

    // ---- 播放器状态（供首页 UI 轮询） ----
    public static boolean isPlaying() {
        return instance != null && instance.player != null && instance.player.isPlaying();
    }

    public static long positionMs() {
        return instance != null && instance.player != null ? instance.player.getCurrentPosition() : 0;
    }

    public static long durationMs() {
        return instance != null && instance.player != null && instance.player.getDuration() > 0
                ? instance.player.getDuration() : 0;
    }

    /** 当前曲目内嵌封面字节（无则 null，由 UI 侧解码）。 */
    public static byte[] artworkData() {
        if (instance == null || instance.player == null) return null;
        MediaItem cur = instance.player.getCurrentMediaItem();
        return cur != null && cur.mediaMetadata.artworkData != null
                ? cur.mediaMetadata.artworkData : null;
    }
    public static String lastBroadcastRaw() { return lastBroadcastRaw; }
    public static String lastKeyType() { return lastKeyType; }
    public static boolean running() { return instance != null; }
    public static boolean receiverRegistered() { return receiverRegistered; }
    public static int broadcastCount() { return broadcastCount; }
    public static long lastBroadcastMs() { return lastBroadcastMs; }
    public static String formatTime(long ms) {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date(ms));
    }

    private static volatile String lastStartError = ""; // 最近一次 start() 失败原因（诊断）
    public static String lastStartError() { return lastStartError; }

    public static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, DashMediaService.class).setAction(ACTION_START));
            lastStartError = "";
        } catch (Exception e) {
            // Android 12+：后台启动前台服务会抛 ForegroundServiceStartNotAllowedException。
            // 记录并在 UI 上提示，避免静默失败导致"必须手动点扫描音乐才监听"。
            lastStartError = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            Log.e(TAG_AMAP, "[SVC] start() 启动前台服务失败", e);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, DashMediaService.class));
    }

    /** 导航数据回调：更新元数据但不打断音乐。line1/line2 可为空（空=只更新一个字段）。 */
    public static void pushNav(Context c, String line1, String line2, boolean on) {
        Intent i = new Intent(c, DashMediaService.class)
                .setAction(ACTION_NAV)
                .putExtra(EXTRA_NAV_ON, on);
        if (line1 != null) i.putExtra(EXTRA_NAV_LINE1, line1);
        if (line2 != null) i.putExtra(EXTRA_NAV_LINE2, line2);
        if (instance != null) {
            instance.onNav(i);
        } else {
            try { c.startForegroundService(i); } catch (Exception e) {
                lastNavi = on ? (line1 + " " + line2) : "-";
                broadcast(c);
            }
        }
    }

    /** 播放控制。 */
    public static void control(Context c, String action) {
        Intent i = new Intent(c, DashMediaService.class).setAction(ACTION_MUSIC).putExtra("ctrl", action);
        if (instance != null) instance.onControl(i);
        else try { c.startForegroundService(i); } catch (Exception ignored) {}
    }

    /** 扫描本地音乐并设为播放列表。返回找到的数量。 */
    public static void scanAndPlay(Context c) {
        Intent i = new Intent(c, DashMediaService.class).setAction(ACTION_MUSIC).putExtra("ctrl", "scan");
        if (instance != null) instance.onControl(i);
        else try { c.startForegroundService(i); } catch (Exception ignored) {}
    }

    /** 触发开机自动扫描（如授予音频权限后重新扫描）。只准备不自动播放。 */
    public static void autoScan(Context c) {
        Intent i = new Intent(c, DashMediaService.class).setAction(ACTION_MUSIC).putExtra("ctrl", "autoscan");
        if (instance != null) instance.onControl(i);
        else try { c.startForegroundService(i); } catch (Exception ignored) {}
    }

    /** 字段探测：把 metadata 各字段设为不同标记值，用于在仪表盘上确认字段映射。 */
    public static void probe(Context c) {
        Intent i = new Intent(c, DashMediaService.class).setAction(ACTION_PROBE);
        if (instance != null) instance.onProbe();
        else try { c.startForegroundService(i); } catch (Exception ignored) {}
    }

    /** 字符集测试：把当前页测试串推给车机簇。每次调用翻到下一页。 */
    public static void charsetTest(Context c) {
        Intent i = new Intent(c, DashMediaService.class).setAction(ACTION_CHARSET);
        if (instance != null) instance.onCharsetTest();
        else try { c.startForegroundService(i); } catch (Exception ignored) {}
    }

    /** 结束字符集测试，恢复正常显示。 */
    public static void charsetStop(Context c) {
        Intent i = new Intent(c, DashMediaService.class).setAction(ACTION_CHARSET_STOP);
        if (instance != null) instance.onCharsetStop();
        else try { c.startForegroundService(i); } catch (Exception ignored) {}
    }

    private void onProbe() {
        probeActive = true;
        publish();
        Log.i(TAG_AMAP, "[PROBE] 字段探测: TITLE=TT ARTIST=AA ALBUM=BB ALBUM_ARTIST=CC GENRE=GG COMPOSER=HH "
                + "DISPLAY_TITLE=DT DISPLAY_SUBTITLE=DS DISPLAY_DESC=DD MEDIA_ID=MI TRACK=88 NUM_TRACKS=99 QUEUE_TITLE=PL");
        probeHandler.removeCallbacksAndMessages(null);
        probeHandler.postDelayed(() -> {
            probeActive = false;
            if (session != null) session.setQueueTitle(null);
            publish();
            Log.i(TAG_AMAP, "[PROBE] 探测结束，恢复正常显示");
        }, 15000);
    }

    private void onCharsetTest() {
        charsetIndex++;
        if (charsetIndex >= CHARSET_PAGES.length) {
            charsetActive = false;
            charsetIndex = 0;
            publish();
            Log.i(TAG_AMAP, "[CHARSET] 测试结束，恢复正常显示");
            return;
        }
        charsetActive = true;
        publish();
        Log.i(TAG_AMAP, "[CHARSET] " + CHARSET_PAGES[charsetIndex][1]
                + " -> " + CHARSET_PAGES[charsetIndex][0]);
    }

    private void onCharsetStop() {
        charsetActive = false;
        charsetIndex = 0;
        publish();
        Log.i(TAG_AMAP, "[CHARSET] 手动结束，恢复正常显示");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG_AMAP, "[SVC] onCreate 服务启动");
        createChannel();
        buildSession();
        buildPlayer();
        autoScan();                 // 开机自动扫描本地音乐：让仪表盘立即显示歌曲信息
        ensureReceiverRegistered();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 幂等保证接收器已注册：即使 onCreate 时注册失败（如首次启动时序问题），
        // 每次收到 start（包括用户点"扫描音乐"）都会重试，确保监听高德广播。
        ensureReceiverRegistered();
        if (intent != null) {
            String a = intent.getAction();
            if (ACTION_STOP.equals(a)) { stopSelf(); return START_NOT_STICKY; }
            if (ACTION_NAV.equals(a)) { onNav(intent); }
            if (ACTION_MUSIC.equals(a)) { onControl(intent); }
            if (ACTION_PROBE.equals(a)) { onProbe(); }
            if (ACTION_CHARSET.equals(a)) { onCharsetTest(); }
            if (ACTION_CHARSET_STOP.equals(a)) { onCharsetStop(); }
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception e) {
            Log.e(TAG_AMAP, "[SVC] startForeground 失败，无法保持前台运行", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    // ================= MediaSession（平台 API，方案 A 同款） =================
    private void buildSession() {
        session = new MediaSession(this, "CarDashX");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { if (player != null) player.play(); }
            @Override public void onPause() { if (player != null) player.pause(); }
            @Override public void onSkipToNext() { if (player != null) player.seekToNextMediaItem(); }
            @Override public void onSkipToPrevious() { if (player != null) player.seekToPreviousMediaItem(); }
            @Override public void onStop() { if (player != null) player.pause(); }
            @Override public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                // 必须交给 super 分发：默认实现会把 KEYCODE_MEDIA_NEXT/PREVIOUS 路由到
                // onSkipToNext()/onSkipToPrevious()。直接 return true 会吞掉方向盘按键。
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        });
        session.setActive(true);
    }

    // ================= ExoPlayer =================
    private void buildPlayer() {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(attrs, true)
                .build();
        // 循环播放：上一首/下一首在列表首尾也能循环切换（方向盘/UI 按钮均生效）
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) { publish(); }
            @Override public void onMediaItemTransition(MediaItem item, int reason) {
                lastTrack = item != null && item.mediaMetadata.title != null
                        ? item.mediaMetadata.title.toString() : "-";
                publish();
            }
        });
    }

    // ================= 车机版标准广播（输入端） =================
    private long lastNavMsgMs = 0;
    private long lastRedLightMs = 0;   // 最近一次红绿灯读秒更新时间（供解析器做新鲜度判断）
    private final Handler navStale = new Handler(Looper.getMainLooper());
    private final Runnable navStaleCheck = new Runnable() {
        @Override public void run() {
            if (navOn && System.currentTimeMillis() - lastNavMsgMs > 15000) {
                navOn = false;
                navLine1 = ""; navLine2 = "";
                navBaseLine1 = "";
                lastRedLight = ""; lastRedSec = 0; lastLane = ""; lastLaneShown = ""; lastLaneMs = 0; lastSpeed = -1;
                lastNextRoad = ""; lastCurRoad = "";
                stopNavRefresh();
                publish();
                Log.i(TAG_AMAP, "[STALE] 15s 无导航广播，判定退出导航，恢复歌曲元数据");
            }
            navStale.postDelayed(this, 3000);
        }
    };

    /** 幂等：接收器已注册则直接返回，否则注册。在 onCreate 和每次 onStartCommand 都调用。 */
    private void ensureReceiverRegistered() {
        if (receiverRegistered && amapReceiver != null) return;
        registerAmapReceiver();
    }

    private void registerAmapReceiver() {
        try {
            amapReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    Log.i(TAG_AMAP, "[onReceive] action=" + (i != null ? i.getAction() : "null"));
                    onAmapBroadcast(i);
                }
            };
            IntentFilter f = new IntentFilter(CarProtocolParser.ACTION);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(amapReceiver, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(amapReceiver, f);
            receiverRegistered = true;
            Log.i(TAG_AMAP, "[REG] 车机版标准广播接收器已注册 action=" + CarProtocolParser.ACTION
                    + " api=" + Build.VERSION.SDK_INT
                    + " exported=" + (Build.VERSION.SDK_INT >= 33));
            navStale.postDelayed(navStaleCheck, 3000);
        } catch (Exception e) {
            receiverRegistered = false;
            Log.e(TAG_AMAP, "[REG] 注册广播接收失败", e);
        }
    }

    // ================= 1s 导航刷新定时器 =================
    // 车机只在 line1 变化时才重绘 line2；红绿灯读秒在 line1 尾部每秒变化可带动重绘，
    // 但红绿灯广播可能不够密集，这里再加一个 1s 定时器兜底：导航中每秒重发一次元数据。
    private final Handler navRefresh = new Handler(Looper.getMainLooper());
    private final Runnable navRefreshTick = new Runnable() {
        @Override public void run() {
            if (navOn) publish();
            navRefresh.postDelayed(this, 1000);
        }
    };

    private void startNavRefresh() {
        navRefresh.removeCallbacks(navRefreshTick);
        navRefresh.postDelayed(navRefreshTick, 1000);
    }

    private void stopNavRefresh() {
        navRefresh.removeCallbacks(navRefreshTick);
    }

    private void onAmapBroadcast(Intent i) {
        if (i == null || i.getExtras() == null) return;
        Bundle ex = i.getExtras();
        broadcastCount++;
        lastBroadcastMs = System.currentTimeMillis();
        int keyType = CarProtocolParser.getInt(ex, "KEY_TYPE", -1);
        lastKeyType = String.valueOf(keyType);
        lastNavMsgMs = System.currentTimeMillis();

        Log.i(TAG_AMAP, "[RECV] #" + broadcastCount + " KEY_TYPE=" + keyType
                + " time=" + formatTime(lastBroadcastMs));
        try {
            logKeyFields(keyType, ex);

            // 用当前导航态做种子，让各 KEY_TYPE 广播之间保留 line1/line2/红绿灯/车道/路名
            CarProtocolParser.Result seed = new CarProtocolParser.Result();
            seed.line1 = navLine1; seed.line2 = navLine2; seed.active = navOn;
            seed.baseLine1 = navBaseLine1;
            seed.redLight = lastRedLight; seed.lane = lastLane;
            seed.redSec = lastRedSec;
            seed.lightPhase = lastLightPhase;
            seed.nextRoad = lastNextRoad; seed.curRoad = lastCurRoad;
            seed.redLightMs = lastRedLightMs;
            seed.laneMs = lastLaneMs;
            seed.speed = lastSpeed;
            CarProtocolParser.Result r = CarProtocolParser.parse(i, seed,
                    DisplayPrefs.getUseTextLight(this));

            if (r.redLightMs > 0) lastRedLightMs = r.redLightMs;
            lastRedLight = r.redLight;
            lastRedSec = r.redSec;
            lastLightPhase = r.lightPhase;
            lastLane = r.lane;
            lastLaneShown = r.lane;
            if (r.speed >= 0) lastSpeed = r.speed;
            if (r.laneMs > 0) lastLaneMs = r.laneMs;
            lastNextRoad = r.nextRoad;
            lastCurRoad = r.curRoad;
            if (r.rawChanged && r.raw != null && !r.raw.isEmpty()) lastBroadcastRaw = r.raw;

            if (!r.line1.isEmpty() || r.active) {
                navLine1 = r.line1;
                navLine2 = r.line2;
                navBaseLine1 = r.baseLine1;
                navOn = true;
                startNavRefresh();
                publish();
                Log.i(TAG_AMAP, "[PUSH] line1=" + r.line1 + " | line2=" + r.line2
                        + " | redLight=" + r.redLight + " | phase=" + r.lightPhase + " | lane=" + r.lane
                        + " | next=" + r.nextRoad + " | cur=" + r.curRoad);
            }
        } catch (Exception e) {
            Log.e(TAG_AMAP, "[PARSE] 解析广播失败 KEY_TYPE=" + keyType, e);
        }
    }

    /** 打印当前 KEY_TYPE 的关键字段，便于实测诊断；同时落盘写入 LaneLog。 */
    private void logKeyFields(int keyType, Bundle ex) {
        try {
            String line;
            switch (keyType) {
                case CarProtocolParser.TYPE_GUIDE:
                    line = "[10001] ICON=" + CarProtocolParser.getInt(ex, "ICON", -1)
                            + " CUR_ROAD=" + CarProtocolParser.getStr(ex, "CUR_ROAD_NAME")
                            + " NEXT_ROAD=" + CarProtocolParser.getStr(ex, "NEXT_ROAD_NAME")
                            + " SEG_DIS=" + CarProtocolParser.getInt(ex, "SEG_REMAIN_DIS", -1)
                            + " ROUTE_DIS=" + CarProtocolParser.getInt(ex, "ROUTE_REMAIN_DIS", -1)
                            + " SPEED=" + CarProtocolParser.getInt(ex, "CUR_SPEED", -1)
                            + " ETA=" + CarProtocolParser.getStr(ex, "ETA_TEXT");
                    break;
                case CarProtocolParser.TYPE_TRAFFIC_LIGHT:
                    line = "[60073] red=" + CarProtocolParser.getInt(ex, "redLightCountDownSeconds", -1)
                            + " green=" + CarProtocolParser.getInt(ex, "greenLightLastSecond", -1)
                            + " status=" + CarProtocolParser.getInt(ex, "trafficLightStatus", -1)
                            + " waitRound=" + CarProtocolParser.getInt(ex, "waitRound", -1)
                            + " dir=" + CarProtocolParser.getStr(ex, "dir");
                    break;
                case CarProtocolParser.TYPE_DRIVEWAY:
                    line = "[13012] EXTRA_DRIVE_WAY=" + CarProtocolParser.getStr(ex, "EXTRA_DRIVE_WAY");
                    lastLaneJson = CarProtocolParser.getStr(ex, "EXTRA_DRIVE_WAY");
                    break;
                case CarProtocolParser.TYPE_ROAD_NAME:
                    line = "[60021] CURROADNAME=" + CarProtocolParser.getStr(ex, "EXTRA_CURROADNAME");
                    break;
                default:
                    line = "[未知KEY_TYPE=" + keyType + "] extras=" + ex.toString();
                    break;
            }
            Log.i(TAG_AMAP, "  " + line);
            // 落盘日志：车道(13012)全记；其它类型节流到每 2 秒一条，避免日志爆炸
            long now = System.currentTimeMillis();
            if (keyType == CarProtocolParser.TYPE_DRIVEWAY
                    || now - lastLogMs > 2000) {
                LaneLog.log(this, line);
                lastLogMs = now;
            }
        } catch (Exception e) {
            Log.w(TAG_AMAP, "logKeyFields failed", e);
        }
    }

    // ================= 导航覆盖 =================
    private void onNav(Intent i) {
        boolean on = i.getBooleanExtra(EXTRA_NAV_ON, navOn);
        if (i.hasExtra(EXTRA_NAV_LINE1)) navLine1 = i.getStringExtra(EXTRA_NAV_LINE1);
        if (i.hasExtra(EXTRA_NAV_LINE2)) navLine2 = i.getStringExtra(EXTRA_NAV_LINE2);
        navOn = on;
        if (on) {
            startNavRefresh();
        } else {
            stopNavRefresh();
            navBaseLine1 = "";
            lastRedLight = ""; lastRedSec = 0; lastLane = ""; lastLaneShown = ""; lastLaneMs = 0; lastSpeed = -1;
            lastNextRoad = ""; lastCurRoad = "";
        }
        lastNavi = (on && !navLine1.isEmpty()) ? navLine1 + " · " + navLine2 : "-";
        publish();
    }

    // ================= 播放控制 =================
    private void onControl(Intent i) {
        String ctrl = i.getStringExtra("ctrl");
        if ("autoscan".equals(ctrl)) {
            autoScan();
        } else if ("scan".equals(ctrl)) {
            scanPlaylist();
            if (!playlist.isEmpty()) { player.setMediaItems(playlist, 0, 0); player.prepare(); player.play(); }
        } else if ("play".equals(ctrl)) {
            if (player != null) player.play();
        } else if ("pause".equals(ctrl)) {
            if (player != null) player.pause();
        } else if ("next".equals(ctrl)) {
            if (player != null) player.seekToNextMediaItem();
        } else if ("prev".equals(ctrl)) {
            if (player != null) player.seekToPreviousMediaItem();
        }
    }

    /** 扫描本地音频文件（MediaStore），生成播放列表。 */
    private void scanPlaylist() {
        playlist.clear();
        try (Cursor c = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION},
                MediaStore.Audio.Media.IS_MUSIC + " != 0",
                null,
                MediaStore.Audio.Media.DEFAULT_SORT_ORDER)) {
            if (c != null) {
                int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                while (c.moveToNext()) {
                    long id = c.getLong(idCol);
                    String title = c.getString(titleCol);
                    String artist = c.getString(artistCol);
                    if (title == null || title.isEmpty()) continue;
                    Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    playlist.add(new MediaItem.Builder()
                            .setUri(uri)
                            .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(title)
                                    .setArtist(artist)
                                    .build())
                            .build());
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * 开机自动扫描：仅当播放列表未设置时执行（避免覆盖已有播放/手动扫描）。
     * 扫描在后台线程，完成后回主线程设置播放列表并 prepare（不自动播放），让仪表盘立即显示当前歌曲。
     */
    private void autoScan() {
        if (player != null && player.getMediaItemCount() > 0) return;
        new Thread(() -> {
            scanPlaylist();
            new Handler(Looper.getMainLooper()).post(() -> {
                // 主线程再判断一次：若期间用户已手动扫描/播放（player 已有条目），则不覆盖
                if (player != null && player.getMediaItemCount() == 0 && !playlist.isEmpty()) {
                    player.setMediaItems(playlist, 0, 0);
                    player.prepare();
                    publish();
                    Log.i(TAG_AMAP, "[SCAN] 开机自动扫描完成，共 " + playlist.size() + " 首，已准备（未自动播放）");
                } else {
                    Log.i(TAG_AMAP, "[SCAN] 开机自动扫描跳过（播放列表已设置或为空，可能未授予音频权限）");
                }
            });
        }, "auto-scan").start();
    }

    /**
     * 组装仪表盘第二行（专辑区）。三选一（DisplayPrefs.getActiveMode）：
     * 道路名称 / 车道内容 / 当前车速，只输出其中一项；数据缺失时回退 "导航"。
     */
    private String buildNavLine2() {
        int mode = DisplayPrefs.getActiveMode(this);
        if (mode == DisplayPrefs.SHOW_SPEED) {
            return lastSpeed >= 0 ? (lastSpeed + "km/h") : "导航";
        }
        // 合并模式：车道信息显示到被清空为止（过路口/退出导航时 13012 发空车道、或 STALE 15s 清空），
        // 与手机 HUD 车道时机一致。不再用 8s 新鲜度——8s 会在还没过路口时就过早回落道路名称。
        if (!lastLane.isEmpty()) return lastLane;
        String road = CarProtocolParser.roadLine2(lastNextRoad, lastCurRoad);
        return road.isEmpty() ? "导航" : road;
    }

    // ================= 元数据发布 =================
    /**
     * 核心：决定当前应显示的 title/artist，并写入 session metadata。
     * 导航时用导航文字；否则用当前歌曲。音乐始终照播（不因导航而暂停）。
     */
    private void publish() {
        // 字段探测模式：各字段用不同标记值，用于在仪表盘上定位字段映射
        if (probeActive) { publishProbe(); return; }
        if (charsetActive) { publishCharset(); return; }

        String title, artist;
        if (navOn && !navLine1.isEmpty()) {
            title = navLine1;
            artist = buildNavLine2();
        } else {
            MediaItem cur = player != null ? player.getCurrentMediaItem() : null;
            if (cur != null && cur.mediaMetadata.title != null) {
                title = cur.mediaMetadata.title.toString();
                artist = cur.mediaMetadata.artist != null ? cur.mediaMetadata.artist.toString() : "";
            } else {
                title = "仪表盘导航";
                artist = "等待播放";
            }
        }
        lastTitle = title;
        lastArtist = artist;

        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, artist)      // 车机第二行=专辑区
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER,
                        // 顶部"歌曲 N"：始终显示真实曲目号。
                        // 车速已改由第二行（专辑区）显示，不再占用该字段。
                        player != null ? player.getCurrentMediaItemIndex() + 1 : 1);
        long pos = 0;
        if (player != null) {
            pos = player.getCurrentPosition();
            md.putLong(MediaMetadata.METADATA_KEY_DURATION,
                    player.getDuration() > 0 ? player.getDuration() : 0);
        }
        if (session != null) {
            // 顶部"歌曲 65535"位置：把十字路口车道指引放进 QUEUE_TITLE（播放列表名），
            // 不占用 line1(TITLE)/line2(ALBUM)。无车道信息时显示"导航"，
            // 便于判断 setQueueTitle 是否真的映射到顶部那行（能显示"导航"=字段生效）。
            session.setQueueTitle(lastLane.isEmpty() ? "导航" : lastLane);
            session.setMetadata(md.build());
            PlaybackState state = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(player != null && player.isPlaying()
                                    ? PlaybackState.STATE_PLAYING
                                    : PlaybackState.STATE_PAUSED,
                            pos, 1.0f)
                    .build();
            session.setPlaybackState(state);
        }
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        broadcast(this);
    }

    /** 字段探测：一次性把各候选字段设为不同标记值，用户在仪表盘上观察哪个字段对应哪一行。 */
    private void publishProbe() {
        lastTitle = "TT";
        lastArtist = "AA";
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "TT")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "AA")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "BB")
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, "CC")
                .putString(MediaMetadata.METADATA_KEY_GENRE, "GG")
                .putString(MediaMetadata.METADATA_KEY_COMPOSER, "HH")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, "DT")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, "DS")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, "DD")
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "MI")
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, 88)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, 99)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 0);
        if (session != null) {
            session.setQueueTitle("PL");   // 播放列表/队列名（用户猜测"默认播放列表名称"）
            session.setMetadata(md.build());
            PlaybackState state = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                    .build();
            session.setPlaybackState(state);
        }
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        broadcast(this);
    }

    /** 字符集测试：把当前页测试串推给车机簇（title=内容，artist=页码标签）。 */
    private void publishCharset() {
        String[] p = CHARSET_PAGES[charsetIndex];
        String content = p[0];
        String label = p[1];
        lastTitle = content;
        lastArtist = label;
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, content)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, label)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, label);
        if (session != null) {
            session.setMetadata(md.build());
            PlaybackState state = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                    .build();
            session.setPlaybackState(state);
        }
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        broadcast(this);
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "仪表盘导航", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("共享 MediaSession：音乐 + 导航元数据 → 车机仪表盘");
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b
                .setSmallIcon(R.drawable.ic_nav)
                .setContentTitle(lastTitle)
                .setContentText(lastArtist)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session != null ? session.getSessionToken() : null))
                .build();
    }

    private static void broadcast(Context c) {
        Intent i = new Intent(ACTION_STATUS).setPackage(c.getPackageName())
                .putExtra(EXTRA_STATUS_TITLE, lastTitle)
                .putExtra(EXTRA_STATUS_ARTIST, lastArtist)
                .putExtra(EXTRA_STATUS_NAV, lastNavi)
                .putExtra(EXTRA_STATUS_TRACK, lastTrack);
        c.sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG_AMAP, "[SVC] onDestroy 服务销毁");
        navStale.removeCallbacksAndMessages(null);
        navRefresh.removeCallbacksAndMessages(null);
        probeHandler.removeCallbacksAndMessages(null);
        if (amapReceiver != null) {
            try { unregisterReceiver(amapReceiver); } catch (Exception ignored) {}
            amapReceiver = null;
        }
        receiverRegistered = false;
        if (player != null) { player.release(); player = null; }
        if (session != null) { session.setActive(false); session.release(); session = null; }
        if (instance == this) instance = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
