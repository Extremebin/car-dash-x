package com.xtrail.navdash;

import android.content.Intent;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 高德车机版标准广播解析器。
 *
 * 车机版（amapauto）导航时通过普通广播 AUTONAVI_STANDARD_BROADCAST_SEND 对外发导航状态，
 * 任何 App 都能收到（已反编译 fd.java 确认：setAction + sendBroadcast，无 setPackage/权限）。
 * 关键 KEY_TYPE（反编译 StandardProtocolKey + 各 Dispatch 类确认）：
 *   - 10001 主导航指引：ICON/CUR_ROAD_NAME/NEXT_ROAD_NAME/SEG_REMAIN_DIS/SEG_REMAIN_TIME/...
 *   - 60073 红绿灯读秒：redLightCountDownSeconds/greenLightLastSecond/trafficLightStatus/waitRound/dir
 *   - 60021 当前道路名：EXTRA_CURROADNAME
 *   - 13012 车道指引：EXTRA_DRIVE_WAY = JSON(drive_way_enabled, drive_way_size, drive_way_info[{drive_way_number, drive_way_lane_Back_icon, drive_way_lane_Extended, trafficLaneAdvised, trafficLaneType}])
 *
 * 输出：仪表盘两行（line1=方向箭头+方向词+公里数+红绿灯读秒，line2=进入下一路），并保留原始数据供调试。
 * line1 读秒每秒变化 → 带动仪表盘重绘 line2（解决"仅 line2 变化不刷新"问题）。
 * 车道箭头（13012）仅解析供调试，不再上屏。
 */
public final class CarProtocolParser {

    public static final String ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";
    public static final int TYPE_GUIDE = 10001;
    public static final int TYPE_ROAD_NAME = 60021;
    public static final int TYPE_TRAFFIC_LIGHT = 60073;
    public static final int TYPE_DRIVEWAY = 13012;

    /** 仪表盘单行最大显示字符数（超出截断，避免显示不全/换行错位）。 */
    private static final int MAX_LINE = 14;

    /** 车道信息新鲜度（毫秒）：超过则视为已过路口，line2 回落显示"进入xxx路"。 */
    public static final long LANE_FRESH_MS = 8000;

    private CarProtocolParser() {}

    /** 安全取字符串：字段实际可能是 Integer/Long 等数字类型时转成字符串，避免 ClassCastException。 */
    public static String getStr(Bundle ex, String key) {
        Object v = ex.get(key);
        return v == null ? null : v.toString();
    }

    /** 安全取整数：兼容字符串/数字/浮点。 */
    public static int getInt(Bundle ex, String key, int def) {
        Object v = ex.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return (int) Double.parseDouble(((String) v).trim()); } catch (Exception e) { return def; }
        }
        return def;
    }

    /** 解析结果。active=false 表示暂无有效导航指引。 */
    public static final class Result {
        public boolean active;
        public String baseLine1 = "";    // line1 基础（不含读秒）：方向箭头 + 方向词 + 公里数
        public String line1 = "";        // 主指令（标题区）：baseLine1 + 红绿灯读秒
        public String line2 = "";        // 第二行（专辑区）："进入xxx路" / "沿xxx路"
        public String redLight = "";     // "红灯12秒·等1轮" / "绿灯8秒"（原始）
        public int redSec = 0;           // 当前红绿灯读秒（仅秒数，用于 line1 尾部，0=无）
        public String lightPhase = "";   // 当前相位："红"/"绿"/""（无灯），line1 读秒前标注
        public String nextRoad = "";     // 即将进入的路（NEXT_ROAD_NAME）
        public String curRoad = "";      // 当前路（CUR_ROAD_NAME）
        public String lane = "";         // 车道箭头串（如 "←←↑↑↑→"，每个车道一个箭头，调试用）
        public long laneMs = 0;          // 最近一次车道信息更新时间（新鲜度判断，超时回落"进入xxx路"）
        public int speed = -1;           // 当前车速 km/h（CUR_SPEED，10001），-1=未知
        public String raw = "";          // 原始字段（调试）
        public boolean rawChanged = true;
        public long redLightMs = 0;      // 最近一次红绿灯读秒更新时间（新鲜度判断）
    }

    /**
     * 解析一条广播。只解析已知 KEY_TYPE；未知类型返回 raw 记录（便于排查）。
     *
     * 状态设计：redLight（60073）与 lane（13012）是两份【持久】状态，各自广播更新；
     * line2 在末尾统一合成 = 红绿灯 + 车道，避免两者互相覆盖 line2（闪跳/丢失问题）。
     */
    public static Result parse(Intent i, Result last, boolean useTextLight) {
        Result r = new Result();
        if (i == null) return r;
        Bundle ex = i.getExtras();
        if (ex == null) return r;
        int keyType = getInt(ex, "KEY_TYPE", -1);

        // 继承上一帧持久状态：主指令、红绿灯/读秒、路名、车道、激活态
        if (last != null) {
            r.baseLine1 = last.baseLine1;
            r.line1 = last.line1;
            r.redLight = last.redLight;
            r.redSec = last.redSec;
            r.lightPhase = last.lightPhase;
            r.nextRoad = last.nextRoad;
            r.curRoad = last.curRoad;
            r.lane = last.lane;
            r.laneMs = last.laneMs;
            r.speed = last.speed;
            r.redLightMs = last.redLightMs;
            r.active = last.active;
        }

        // 记录原始字段（含嵌套 Bundle，供调试页展示）
        String raw = dump(ex);
        r.raw = raw;
        if (last != null) r.rawChanged = !raw.equals(last.raw);

        switch (keyType) {
            case TYPE_GUIDE: {
                r.active = true;
                int icon = getInt(ex, "ICON", 0);
                int segDis = getInt(ex, "SEG_REMAIN_DIS", -1);       // 距下一转向（米）
                int routeDis = getInt(ex, "ROUTE_REMAIN_DIS", -1);
                String dir = iconToDir(icon);
                int d = segDis >= 0 ? segDis : routeDis;
                // line1 基础 = 方向箭头 + 方向词 + 公里数（如 "↖靠左 1.9km"）
                r.baseLine1 = line1WithDist(dir, d);
                // 路名：即将进入的路 + 当前路（供 line2）
                String next = getStr(ex, "NEXT_ROAD_NAME");
                String cur = getStr(ex, "CUR_ROAD_NAME");
                if (next != null && !next.isEmpty()) r.nextRoad = next;
                if (cur != null && !cur.isEmpty()) r.curRoad = cur;
                // 当前车速（km/h，供顶部 TRACK_NUMBER）
                r.speed = getInt(ex, "CUR_SPEED", -1);
                break;
            }
            case TYPE_TRAFFIC_LIGHT: {
                int red = getInt(ex, "redLightCountDownSeconds", -1);
                int green = getInt(ex, "greenLightLastSecond", -1);
                int status = getInt(ex, "trafficLightStatus", -1);
                int waitRound = getInt(ex, "waitRound", -1);
                StringBuilder sb = new StringBuilder();
                // 实测（两次车上结果）：
                //   redLightCountDownSeconds 在红、绿期间都在倒数 = 当前相位活读秒；
                //   greenLightLastSecond 恒为 3（干扰值，不可用于读秒/相位）。
                // 故读秒一律取 red（不足用 green 兜底）。
                int sec = red >= 0 ? red : green;
                r.redSec = Math.max(sec, 0);
                // 相位判据 = trafficLightStatus（字段命名即相位）。暂按 1=红 2=绿；
                // 若实测 status 值不同，仅需翻转此映射。
                if (status == 2) r.lightPhase = "绿";
                else if (status == 1) r.lightPhase = "红";
                else r.lightPhase = (green >= 0) ? "绿" : "红";
                if (r.redSec > 0) sb.append(r.lightPhase).append("灯").append(r.redSec).append("秒");
                else sb.append(r.lightPhase).append("灯");
                if (waitRound >= 0) sb.append("·等").append(waitRound).append("轮");
                r.redLight = sb.toString();
                r.redLightMs = System.currentTimeMillis();
                break;
            }
            case TYPE_ROAD_NAME: {
                // 仅路名广播：更新当前路，不覆盖主指令（10001 已带路名）
                String cur = getStr(ex, "EXTRA_CURROADNAME");
                if (cur != null && !cur.isEmpty()) r.curRoad = cur;
                break;
            }
            case TYPE_DRIVEWAY: {
                // 车道解析后用于 line2（替换"进入xxx路"）；收到即刷新新鲜度，超时回落
                r.lane = parseDriveWay(getStr(ex, "EXTRA_DRIVE_WAY"));
                r.laneMs = System.currentTimeMillis();
                break;
            }
            default:
                break;
        }

        // 红绿灯新鲜度：超5s未更新视为过时（路口已过），清空避免"红灯XX秒"残留
        if (r.redLightMs > 0 && System.currentTimeMillis() - r.redLightMs > 5000) {
            r.redLight = "";
            r.redSec = 0;
            r.lightPhase = "";
            r.redLightMs = 0;
        }

        // line1 = baseLine1 + 红绿灯读秒。读秒每秒变化 → 带动仪表盘重绘 line2（修复"line2 不刷新"）
        r.line1 = r.baseLine1;
        if (r.redSec > 0 && !r.baseLine1.isEmpty()) {
            // 红绿灯相位标志：默认圆圈符号 ●=红灯 ◎=绿灯；开关开启时用文字"红灯/绿灯"
            String phaseSym;
            if ("绿".equals(r.lightPhase)) phaseSym = useTextLight ? "绿灯" : "◎";
            else if ("红".equals(r.lightPhase)) phaseSym = useTextLight ? "红灯" : "●";
            else phaseSym = r.lightPhase;
            r.line1 = r.baseLine1 + "·" + phaseSym + r.redSec + "秒";
        }

        // line2 = 车道信息（新鲜）时只显示车道；否则进入下一路；无下一路则沿当前路
        boolean laneFresh = r.lane != null && !r.lane.isEmpty()
                && r.laneMs > 0 && System.currentTimeMillis() - r.laneMs <= LANE_FRESH_MS;
        r.line2 = laneFresh ? r.lane : roadLine2(r.nextRoad, r.curRoad);

        // 统一截断两行，保证仪表盘显示完整（自动滚动待实测后再调）
        r.line1 = trunc(r.line1);
        r.line2 = trunc(r.line2);
        return r;
    }

    /** 合成 line2：进入下一路；无下一路则沿当前路；都空返回 "". */
    public static String roadLine2(String next, String cur) {
        if (next != null && !next.isEmpty()) return "进入" + next;
        if (cur != null && !cur.isEmpty()) return "沿" + cur;
        return "";
    }

    /** 截断到 MAX_LINE 个字符；超长时用 "…" 结尾。null → ""。 */
    public static String trunc(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.length() <= MAX_LINE) return s;
        return s.substring(0, MAX_LINE - 1) + "…";
    }

    /**
     * 车道 JSON → 箭头串（每个车道一个箭头，按车道号升序=左→右）。
     * 例：5车道 [左转,直行,直行,直行,右转] → "←↑↑↑→"。
     */
    private static String parseDriveWay(String json) {
        if (json == null || json.isEmpty()) return "";
        try {
            JSONObject o = new JSONObject(json);
            // 兼容两种键名：下划线(drive_way_info) 与 驼峰(driveWayInfo)
            JSONArray arr = o.optJSONArray("drive_way_info");
            if (arr == null) arr = o.optJSONArray("driveWayInfo");
            if (arr == null || arr.length() == 0) return "";
            // 收集 (车道号, 图标)，按车道号升序渲染（0=最左 … n=最右）
            // 实测 drive_way_lane_Back_icon 为 String；trafficLaneType 恒为 0（废弃）
            java.util.List<Object[]> lanes = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                int num;
                if (it.has("drive_way_number")) num = it.optInt("drive_way_number", i);
                else if (it.has("driveWayNumber")) num = it.optInt("driveWayNumber", i);
                else num = i;
                String icon = it.optString("drive_way_lane_Back_icon", "0");
                if (icon.isEmpty()) icon = "0";
                lanes.add(new Object[]{num, icon});
            }
            if (lanes.isEmpty()) return "";
            lanes.sort((a, b) -> Integer.compare((Integer) a[0], (Integer) b[0]));
            StringBuilder sb = new StringBuilder();
            for (Object[] l : lanes) sb.append(laneTypeToArrow((String) l[1]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** line1 基础 = 方向箭头 + 动作 + 距离（如 "←靠左 1.9km"）。到达时不加距离。 */
    private static String line1WithDist(String dir, int d) {
        String action = dirToAction(dir);
        String arrow = dirToArrow(dir);
        if ("到达".equals(action)) return arrow + "到达";
        String dist = NavText.line2(d);
        if (dist.isEmpty()) return arrow + action;
        return arrow + action + " " + dist;
    }

    /** 高德车机版 ICON 码 → 方向码（S/L/R/U/SL/SR/A）。参考 NavText.iconToDir 并补车机版码。 */
    public static String iconToDir(int icon) {
        switch (icon) {
            case 2: case 6: case 11: case 17: case 20: return "L"; // 左转、左后、环岛左/左前、进高速左
            case 3: case 7: case 12: case 18: case 21: case 23: return "R"; // 右转、右后、环岛右/右前、进高速右、分流
            case 4: case 65: return "SL";   // 靠左/左前方
            case 5: case 66: return "SR";   // 靠右/右前方
            case 8: case 10: case 14: return "U"; // 掉头、环岛掉头
            case 15: return "A";            // 到达
            default: return "S";            // 直行、环岛直行、汇流、服务区等
        }
    }

    /** 方向码 → 中文动作词。 */
    public static String dirToAction(String dir) {
        switch (dir) {
            case "L": return "左转";
            case "R": return "右转";
            case "SL": return "靠左";
            case "SR": return "靠右";
            case "U": return "掉头";
            case "A": return "到达";
            default: return "直行";
        }
    }

    /**
     * 方向码 → 仪表盘方向箭头字形（line1 首字符）。
     * 车机实测可显示 P1 基础箭头(←↑→↓) + P2 斜箭头(↖↗↘↙) + P7 圆圈(◎⊙◯○●)，
     * 故按用户规范：↖左前 ←左转 ↗右前 →右转 ↓掉头 ↑直行；到达不加箭头。
     */
    public static String dirToArrow(String dir) {
        switch (dir) {
            case "L": return "←";   // 左转
            case "R": return "→";   // 右转
            case "SL": return "↖";  // 向左前方（靠左）
            case "SR": return "↗";  // 向右前方（靠右）
            case "U": return "↓";   // 掉头
            case "A": return "";    // 到达：不加箭头
            default: return "↑";    // 直行
        }
    }

    /**
     * 车道图标码 → 箭头字形。drive_way_lane_Back_icon 为 String，实测 5 张截图映射：
     * 1→↰、3→↗、15→↑、16→↑、18→↱、31→←、43→↰、0→↑。
     * "15" 出现 4 次中 3 次为 ↑（仅 1 次为左转机动最右车道的 ↱），故按 ↑ 处理。
     */
    private static String laneTypeToArrow(String icon) {
        switch (icon) {
            case "1": return "↰";   // 左转/掉头（实测 2 次）
            case "3": return "↗";   // 右前方（实测）
            case "18": return "↱";  // 右转（实测）
            case "31": return "←";  // 左转（实测）
            case "43": return "↰";  // 左转/掉头（实测）
            case "15":              // 直行（3/4；左转机动最右道为 ↱，可接受）
            case "16":              // 直行（实测）
            case "0":               // 直行（当前车道加粗时仍为 ↑）
            default: return "↑";
        }
    }

    /** 把 extras 全部键值转成可读文本（调试）。 */
    private static String dump(Bundle ex) {
        StringBuilder sb = new StringBuilder();
        for (String k : ex.keySet()) {
            Object v = ex.get(k);
            sb.append(k).append(" = ").append(valStr(v)).append('\n');
        }
        return sb.toString();
    }

    private static String valStr(Object v) {
        if (v == null) return "null";
        if (v instanceof Bundle) {
            Bundle b = (Bundle) v;
            StringBuilder sb = new StringBuilder("{");
            for (String k : b.keySet()) sb.append(k).append('=').append(valStr(b.get(k))).append(';');
            return sb.append('}').toString();
        }
        return v.toString();
    }
}
