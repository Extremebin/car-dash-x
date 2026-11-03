package com.xtrail.navdash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页：上半部为导航 HUD（仿高德悬浮窗），下半部为音乐播放器。
 * 数据全部来自 DashMediaService 的静态访问器（后台服务持有 ExoPlayer + MediaSession）。
 */
public final class HomeFragment extends Fragment {

    private TextView hudArrow, hudInstruction, hudDistance, hudLane, hudSpeed, hudRedLight;
    private ImageView playerAlbum;
    private TextView playerTitle, playerArtist, playerNow;
    private SeekBar playerProgress;
    private ImageButton btnPrev, btnPlay, btnNext, btnScan;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService decodePool = Executors.newSingleThreadExecutor();

    /** 进度条轮询：每 500ms 刷新播放位置/时长/播放态。 */
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updatePlayerState();
            ui.postDelayed(this, 500);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { render(); }
    };

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        hudArrow = v.findViewById(R.id.hudArrow);
        hudInstruction = v.findViewById(R.id.hudInstruction);
        hudDistance = v.findViewById(R.id.hudDistance);
        hudLane = v.findViewById(R.id.hudLane);
        hudSpeed = v.findViewById(R.id.hudSpeed);
        hudRedLight = v.findViewById(R.id.hudRedLight);
        playerAlbum = v.findViewById(R.id.playerAlbum);
        playerTitle = v.findViewById(R.id.playerTitle);
        playerArtist = v.findViewById(R.id.playerArtist);
        playerNow = v.findViewById(R.id.playerNow);
        playerProgress = v.findViewById(R.id.playerProgress);
        btnPrev = v.findViewById(R.id.btnPrev);
        btnPlay = v.findViewById(R.id.btnPlay);
        btnNext = v.findViewById(R.id.btnNext);
        btnScan = v.findViewById(R.id.btnScan);

        btnPrev.setOnClickListener(x -> DashMediaService.control(requireContext(), "prev"));
        btnNext.setOnClickListener(x -> DashMediaService.control(requireContext(), "next"));
        btnScan.setOnClickListener(x -> DashMediaService.scanAndPlay(requireContext()));
        btnPlay.setOnClickListener(x -> DashMediaService.control(
                requireContext(), DashMediaService.isPlaying() ? "pause" : "play"));
        // 进度条目前仅作显示（拖动功能后续再加），禁用触摸
        playerProgress.setClickable(false);
        playerProgress.setFocusable(false);
    }

    @Override public void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(DashMediaService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            requireContext().registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(receiver, f);
        }
        ui.post(ticker);
        render();
    }

    @Override public void onPause() {
        super.onPause();
        try { requireContext().unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
        ui.removeCallbacks(ticker);
    }

    /** MainActivity 广播回调时调用，刷新 HUD/播放器。 */
    public void onDataChanged() { render(); }

    private void render() {
        // ---- HUD ----
        String line1 = DashMediaService.lastNaviLine1();
        String line2 = DashMediaService.lastNaviLine2();
        if (line1.isEmpty()) {
            hudArrow.setText("↑");
            hudInstruction.setText(R.string.hud_idle);
            hudDistance.setText("");
        } else {
            String[] parts = splitArrow(line1);
            hudArrow.setText(parts[0]);
            hudInstruction.setText(parts[1]);
            hudDistance.setText(line2);
        }
        String lane = DashMediaService.lastLane();
        hudLane.setText(lane == null ? "" : lane);
        int sp = DashMediaService.lastSpeed();
        hudSpeed.setText(sp >= 0 ? sp + " km/h" : getString(R.string.hud_speed_unknown));
        String red = DashMediaService.lastRedLight();
        hudRedLight.setText(red == null ? "" : red);

        // ---- 播放器 ----
        // 标题/艺术家取当前真实播放文件，避免与上方 HUD 的导航伪装信息重复
        String npTitle = DashMediaService.nowPlayingTitle();
        String npArtist = DashMediaService.nowPlayingArtist();
        playerTitle.setText(npTitle.isEmpty() ? getString(R.string.player_default_title) : npTitle);
        playerArtist.setText(npArtist.isEmpty() ? getString(R.string.player_default_artist) : npArtist);
        playerNow.setText(DashMediaService.lastAlbum());
        updatePlayIcon();
        updateArtwork();
    }

    /** 刷新播放进度/时长/播放图标。 */
    private void updatePlayerState() {
        long pos = DashMediaService.positionMs();
        long dur = DashMediaService.durationMs();
        if (dur > 0) {
            playerProgress.setProgress((int) (pos * 1000 / dur));
        } else {
            playerProgress.setProgress(0);
        }
        updatePlayIcon();
    }

    private void updatePlayIcon() {
        boolean playing = DashMediaService.isPlaying();
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        btnPlay.setContentDescription(getString(
                playing ? R.string.player_pause : R.string.player_play));
    }

    /** 后台解码封面字节，避免主线程解码大图卡顿。 */
    private void updateArtwork() {
        byte[] data = DashMediaService.artworkData();
        if (data == null) {
            playerAlbum.setImageDrawable(null);
            playerAlbum.setBackgroundResource(R.drawable.bg_album_placeholder);
            return;
        }
        decodePool.execute(() -> {
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            ui.post(() -> {
                if (!isAdded() || playerAlbum == null) return;
                if (bmp != null) playerAlbum.setImageBitmap(bmp);
                else playerAlbum.setImageDrawable(null);
            });
        });
    }

    /** 拆出指令行首个箭头字形；若不以箭头开头则默认 ↑，全文当文字。 */
    private static String[] splitArrow(String line1) {
        if (line1.isEmpty()) return new String[]{"↑", ""};
        char c = line1.charAt(0);
        switch (c) {
            case '←': case '→': case '↑': case '↓':
            case '↖': case '↗': case '↘': case '↙':
            case '↰': case '↱': case '↩': case '↪':
                return new String[]{String.valueOf(c), line1.substring(1).trim()};
            default:
                return new String[]{"↑", line1};
        }
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        ui.removeCallbacksAndMessages(null);
    }

    @Override public void onDestroy() {
        super.onDestroy();
        decodePool.shutdownNow();
    }
}
