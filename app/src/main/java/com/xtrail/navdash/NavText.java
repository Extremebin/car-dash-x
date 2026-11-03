package com.xtrail.navdash;

/**
 * 把高德 SDK 的结构化导航数据（NaviInfo）转成"适合奇骏仪表盘两行区"的短文本。
 *
 * 仪表盘两行区（已实测）：
 *   - 第一行 = 歌曲标题区  → 我们放"主指令"（转向 + 路名，如 "右转 中山北路"）
 *   - 第二行 = 专辑区      → 我们放"距离/副信息"（如 "500米"、"剩余 3.2km"）
 *
 * 措辞与取整参考 MotoNav NaviBridge.kt（updateTexts / roundDist / iconToDir），
 * 并已吸收到方案 A 的 NavParser.formatForDash()。
 */
public final class NavText {

    private NavText() {}

    /** 高德转向图标编号 → 方向代码（MotoNav iconToDir）。 */
    public static String iconToDir(int icon) {
        switch (icon) {
            case 2: case 6: case 21: return "L";     // 左转、左后方、环岛左转
            case 3: case 7: case 22: return "R";     // 右转、右后方、环岛右转
            case 4: case 65: return "SL";            // 左前方、靠左
            case 5: case 66: return "SR";            // 右前方、靠右
            case 8: case 19: case 24: return "U";    // 掉头
            case 15: return "A";                     // 到达目的地
            default: return "S";                     // 直行及其他
        }
    }

    /** 方向代码 → 用于仪表盘第一行的中文动作词。 */
    public static String dirToAction(String dir) {
        switch (dir) {
            case "L": return "左转";
            case "R": return "右转";
            case "SL": return "靠左";
            case "SR": return "靠右";
            case "U": return "掉头";
            case "A": return "前方为目的地";
            default: return "直行";
        }
    }

    /**
     * 组装第一行（标题区）：转向动作 + 路名。参考 MotoNav updateTexts：
     *   - 到达       → "前方为目的地"
     *   - 直行+路名  → "沿 XX路 直行"
     *   - 转向+下条路 → "右转 XX路"
     */
    public static String line1(String dir, String curRoad, String nextRoad) {
        String road = (dir.equals("A")) ? "" : dirToAction(dir);
        if (dir.equals("A")) return "前方为目的地";
        if (dir.equals("S") && curRoad != null && !curRoad.isEmpty()) {
            return "沿 " + curRoad + " 直行";
        }
        if (nextRoad != null && !nextRoad.isEmpty()) {
            return dirToAction(dir) + " " + nextRoad;
        }
        if (dir.equals("S")) return "直行";
        return dirToAction(dir);
    }

    /**
     * 组装第二行（专辑区）：距离。取整参考 MotoNav roundDist：
     *   ≥1000m → 精确到 100m，如 "1.2km"；<1000m → 精确到 1m，如 "500米"。
     */
    public static String line2(int remainM) {
        if (remainM <= 0) return "";
        int d = (remainM >= 1000) ? (remainM + 50) / 100 * 100 : remainM;
        if (d >= 1000) {
            double km = d / 1000.0;
            if (km >= 10) return String.format("%.0fkm", km);
            return String.format("%.1fkm", km);
        }
        return d + "米";
    }

    /** 组合成单行（用于"导航时把整句放标题区"的简化模式）。 */
    public static String singleLine(String dir, String curRoad, String nextRoad, int remainM) {
        String l1 = line1(dir, curRoad, nextRoad);
        String l2 = line2(remainM);
        return l2.isEmpty() ? l1 : (l1 + " " + l2);
    }
}
