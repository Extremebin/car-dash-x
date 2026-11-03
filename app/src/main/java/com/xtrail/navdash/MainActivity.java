package com.xtrail.navdash;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 主界面：Material Components 底部三 Tab（首页 HUD+播放器 / 设置 / 关于）。
 * 导航与音乐均在 DashMediaService 后台服务中完成，本页仅负责展示与控制。
 */
public final class MainActivity extends AppCompatActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::renderAll;

    // 缓存三个 Fragment 实例，切 Tab 时复用（保留进度/滚动状态）
    private Fragment homeFragment;
    private Fragment settingsFragment;
    private Fragment aboutFragment;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { renderAll(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setOnItemSelectedListener(item -> {
            selectFragment(item.getItemId());
            return true;
        });
        if (savedInstanceState == null) {
            selectFragment(R.id.nav_home);
        } else {
            restoreCurrent(bottomNav.getSelectedItemId());
        }
        requestNeededPermissions();
        // 自动启动仪表盘导航服务（前台 Activity 内启动前台服务合法）
        DashMediaService.start(this);
    }

    private void selectFragment(int itemId) {
        Fragment target;
        if (itemId == R.id.nav_settings) {
            if (settingsFragment == null) settingsFragment = new SettingsFragment();
            target = settingsFragment;
        } else if (itemId == R.id.nav_about) {
            if (aboutFragment == null) aboutFragment = new AboutFragment();
            target = aboutFragment;
        } else {
            if (homeFragment == null) homeFragment = new HomeFragment();
            target = homeFragment;
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, target)
                .commitAllowingStateLoss();
    }

    /** 配置变更/重建后，恢复当前 Tab 对应 Fragment。 */
    private void restoreCurrent(int itemId) {
        if (itemId == R.id.nav_settings) selectFragment(R.id.nav_settings);
        else if (itemId == R.id.nav_about) selectFragment(R.id.nav_about);
        else selectFragment(R.id.nav_home);
    }

    private void renderAll() {
        FragmentManager fm = getSupportFragmentManager();
        Fragment current = fm.findFragmentById(R.id.fragmentContainer);
        if (current instanceof HomeFragment) ((HomeFragment) current).onDataChanged();
        else if (current instanceof SettingsFragment) ((SettingsFragment) current).onDataChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(DashMediaService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
        ensureServiceRunning();
        ui.post(refresh);
    }

    private void ensureServiceRunning() {
        if (DashMediaService.running()) return;
        DashMediaService.start(this);
        ui.postDelayed(() -> {
            if (!DashMediaService.running()) DashMediaService.start(this);
        }, 800);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
        ui.removeCallbacks(refresh);
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_MEDIA_AUDIO}, 100);
        } else {
            if (Build.VERSION.SDK_INT < 29) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
            }
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
        }
    }
}
