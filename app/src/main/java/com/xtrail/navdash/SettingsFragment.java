package com.xtrail.navdash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 设置页：控制仪表盘第二行（专辑区）显示内容，三选一（道路名称/车道内容/当前车速），
 * 开关互斥，打开即生效；下方输出最近收到的车机版广播诊断信息。
 */
public final class SettingsFragment extends Fragment {

    private MaterialSwitch switchRoad, switchSpeed, switchTextLight;
    private TextView settingsLog;

    /** 程序化同步开关状态时置真，避免回环触发监听。 */
    private boolean updating = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { onDataChanged(); }
    };

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        switchRoad = v.findViewById(R.id.switchRoad);
        switchSpeed = v.findViewById(R.id.switchSpeed);
        switchTextLight = v.findViewById(R.id.switchTextLight);
        settingsLog = v.findViewById(R.id.settingsLog);

        // 初始状态：按当前生效模式设置三个开关
        syncSwitches(DisplayPrefs.getActiveMode(requireContext()));
        switchTextLight.setChecked(DisplayPrefs.getUseTextLight(requireContext()));

        // 二选一：打开某个开关 → 其余自动关闭；关闭当前生效开关 → 弹回开启（保持至少一个）
        switchRoad.setOnCheckedChangeListener((b, on) -> {
            if (updating) return;
            if (on) setActive(DisplayPrefs.SHOW_ROAD_LANE);
            else b.setChecked(true);
        });
        switchSpeed.setOnCheckedChangeListener((b, on) -> {
            if (updating) return;
            if (on) setActive(DisplayPrefs.SHOW_SPEED);
            else b.setChecked(true);
        });

        // 红绿灯标志样式：圆圈符号 ●/◎  ↔  文字 红灯/绿灯（打开即生效）
        switchTextLight.setOnCheckedChangeListener((b, on) -> {
            if (updating) return;
            DisplayPrefs.setUseTextLight(requireContext(), on);
            DashMediaService.refreshDisplay();
        });
    }

    /** 设置生效模式：持久化 + 同步开关 + 立即重发布（打开即生效）。 */
    private void setActive(int mode) {
        Context c = requireContext();
        DisplayPrefs.setActiveMode(c, mode);
        syncSwitches(mode);
        DashMediaService.refreshDisplay();
    }

    /** 同步开关的勾选状态到指定模式（带回环保护）。 */
    private void syncSwitches(int mode) {
        updating = true;
        switchRoad.setChecked(mode == DisplayPrefs.SHOW_ROAD_LANE);
        switchSpeed.setChecked(mode == DisplayPrefs.SHOW_SPEED);
        updating = false;
    }

    @Override public void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(DashMediaService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            requireContext().registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(receiver, f);
        }
        onDataChanged();
    }

    @Override public void onPause() {
        super.onPause();
        try { requireContext().unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
    }

    /** MainActivity 广播回调时调用，刷新诊断日志。 */
    public void onDataChanged() {
        if (settingsLog == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("接收器已注册: ").append(DashMediaService.receiverRegistered() ? "是 ✓" : "否 ✗").append('\n');
        sb.append("服务运行: ").append(DashMediaService.running() ? "是 ✓" : "否 ✗").append('\n');
        String err = DashMediaService.lastStartError();
        if (err != null && !err.isEmpty()) sb.append("启动错误: ").append(err).append('\n');
        sb.append("收到广播: ").append(DashMediaService.broadcastCount()).append(" 次").append('\n');
        sb.append("最近广播: ").append(DashMediaService.lastBroadcastMs() > 0
                ? DashMediaService.formatTime(DashMediaService.lastBroadcastMs()) : "从未收到").append('\n');
        sb.append("最近 KEY_TYPE: ").append(DashMediaService.lastKeyType()).append('\n');
        sb.append("----------------------------------------\n");
        String raw = DashMediaService.lastBroadcastRaw();
        if (raw != null && !raw.isEmpty()) {
            sb.append("【最近一条广播原始字段】\n").append(raw);
        } else {
            sb.append("暂无。请启动仪表盘导航服务并打开高德车机版开始导航。");
        }
        settingsLog.setText(sb.toString());
    }
}
