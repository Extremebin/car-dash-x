package com.xtrail.navdash;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 仪表盘第二行（专辑区）显示模式：三选一（道路名称 / 车道内容 / 当前车速）。
 * 由设置页三个互斥 MaterialSwitch 控制，DashMediaService.publish() 读取后决定 line2 内容。
 */
public final class DisplayPrefs {

    public static final int SHOW_ROAD_LANE = 0;  // 合并：车道新鲜(8s)显示车道，否则回落道路名称
    public static final int SHOW_SPEED = 2;

    private static final String PREFS = "display_prefs";
    private static final String KEY_ACTIVE = "active_mode";
    private static final String KEY_TEXT_LIGHT = "use_text_light";

    private DisplayPrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 当前生效的显示模式（默认 道路+车道 合并）。旧值 1(车道) 归入合并模式。 */
    public static int getActiveMode(Context c) {
        int m = sp(c).getInt(KEY_ACTIVE, SHOW_ROAD_LANE);
        return m == SHOW_SPEED ? SHOW_SPEED : SHOW_ROAD_LANE;
    }

    /** 设置生效模式（三选一，切换即覆盖）。 */
    public static void setActiveMode(Context c, int mode) {
        sp(c).edit().putInt(KEY_ACTIVE, mode).apply();
    }

    /** 红绿灯标志是否用文字"红灯/绿灯"（默认 false=用圆圈符号 ●=红灯 ◎=绿灯）。 */
    public static boolean getUseTextLight(Context c) {
        return sp(c).getBoolean(KEY_TEXT_LIGHT, false);
    }

    /** 设置红绿灯标志样式：true=文字 红灯/绿灯，false=圆圈 ●/◎。 */
    public static void setUseTextLight(Context c, boolean useText) {
        sp(c).edit().putBoolean(KEY_TEXT_LIGHT, useText).apply();
    }
}
