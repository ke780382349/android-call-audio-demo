package com.android.example.myapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PcmInjectionConfigActivity extends AppCompatActivity {

    public static final String TARGET_UPLINK = PcmFileStore.TARGET_UPLINK;
    public static final String TARGET_DOWNLINK = PcmFileStore.TARGET_DOWNLINK;

    private static final String EXTRA_TARGET = "target";

    private static final int SAMPLE_RATE_HZ = 48_000;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int READ_CHUNK_BYTES = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE / 20;

    private final Object previewLock = new Object();
    private final AtomicBoolean previewRunning = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView titleText;
    private TextView selectionText;
    private TextView operationStatusText;
    private TextView emptyText;
    private LinearLayout pcmListContainer;
    private Button refreshButton;

    private String target;
    private File selectedFile;
    private File previewFile;
    private AudioTrack previewTrack;
    private boolean receiverRegistered;

    private final Runnable statePoll = new Runnable() {
        @Override
        public void run() {
            if (!receiverRegistered) {
                return;
            }
            updateOperationState();
            mainHandler.postDelayed(this, 500);
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateOperationState();
        }
    };

    public static Intent createIntent(Context context, String target) {
        return new Intent(context, PcmInjectionConfigActivity.class)
                .putExtra(EXTRA_TARGET, target);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_pcm_injection_config);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.pcmConfigRoot),
                (view, insets) -> {
                    Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    view.setPadding(
                            systemBars.left,
                            systemBars.top,
                            systemBars.right,
                            systemBars.bottom);
                    return insets;
                });

        target = TARGET_DOWNLINK.equals(getIntent().getStringExtra(EXTRA_TARGET))
                ? TARGET_DOWNLINK : TARGET_UPLINK;
        titleText = findViewById(R.id.pcmConfigTitleText);
        selectionText = findViewById(R.id.pcmSelectionText);
        operationStatusText = findViewById(R.id.pcmOperationStatusText);
        emptyText = findViewById(R.id.pcmEmptyText);
        pcmListContainer = findViewById(R.id.pcmListContainer);
        refreshButton = findViewById(R.id.refreshPcmListButton);

        titleText.setText(isUplink()
                ? R.string.uplink_pcm_config_title : R.string.downlink_pcm_config_title);
        selectedFile = PcmFileStore.getSelectedPcm(this, target);

        refreshButton.setOnClickListener(view -> refreshPcmList());
        refreshPcmList();
    }

    private boolean isUplink() {
        return TARGET_UPLINK.equals(target);
    }

    private List<File> findAllPcmFiles() {
        return PcmFileStore.findAllPcmFiles(this);
    }

    private boolean isUsablePcm(File file) {
        return PcmFileStore.isUsablePcm(this, file);
    }

    private void refreshPcmList() {
        List<File> pcmFiles = findAllPcmFiles();
        pcmListContainer.removeAllViews();
        emptyText.setVisibility(pcmFiles.isEmpty() ? View.VISIBLE : View.GONE);
        if (selectedFile != null && !isUsablePcm(selectedFile)) {
            selectedFile = null;
        }
        for (File file : pcmFiles) {
            addPcmRow(file);
        }
        updateSelectionText();
        updateOperationState();
    }

    private void addPcmRow(File file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(8);
        row.setLayoutParams(rowParams);
        row.setBackgroundColor(0x0f000000);

        RadioButton selector = new RadioButton(this);
        selector.setText(file.getName());
        selector.setChecked(file.equals(selectedFile));
        selector.setEnabled(isUsablePcm(file));
        selector.setOnClickListener(view -> selectFile(file));
        row.addView(selector, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView metadata = new TextView(this);
        metadata.setText(buildMetadata(file));
        metadata.setTextIsSelectable(true);
        LinearLayout.LayoutParams metadataParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        metadataParams.leftMargin = dp(8);
        row.addView(metadata, metadataParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        Button previewButton = new Button(this);
        boolean thisFilePlaying = previewRunning.get() && file.equals(previewFile);
        previewButton.setText(thisFilePlaying
                ? R.string.stop_pcm_preview : R.string.preview_pcm);
        previewButton.setEnabled(isUsablePcm(file)
                && !hasAnyCallInProgress()
                && (!previewRunning.get() || thisFilePlaying));
        previewButton.setOnClickListener(view -> {
            if (thisFilePlaying) {
                stopPreview("用户停止试听");
            } else {
                startPreview(file);
            }
        });
        actions.addView(previewButton);

        Button deleteButton = new Button(this);
        deleteButton.setText(R.string.delete_pcm);
        deleteButton.setEnabled(canDeletePcmFiles());
        deleteButton.setOnClickListener(view -> confirmDelete(file));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        deleteParams.leftMargin = dp(8);
        actions.addView(deleteButton, deleteParams);
        row.addView(actions);

        if (isUsablePcm(file)) {
            row.setOnClickListener(view -> selectFile(file));
        }
        pcmListContainer.addView(row);
    }

    private String buildMetadata(File file) {
        String direction;
        if (file.getName().startsWith("uplink_")) {
            direction = "上行采样";
        } else if (file.getName().startsWith("downlink_")) {
            direction = "下行采样";
        } else {
            direction = "PCM";
        }
        double seconds = file.length() / (double) (SAMPLE_RATE_HZ * BYTES_PER_SAMPLE);
        String modified = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(file.lastModified()));
        String validity = isUsablePcm(file) ? "" : "\n不可注入：文件为空或 PCM16 字节未对齐";
        return String.format(
                Locale.US,
                "%s · %.1f 秒 · %,d 字节\n%s%s",
                direction,
                seconds,
                file.length(),
                modified,
                validity);
    }

    private void selectFile(File file) {
        if (!PcmFileStore.selectPcm(this, target, file)) {
            operationStatusText.setText(R.string.invalid_pcm_selection);
            return;
        }
        selectedFile = file;
        operationStatusText.setText(isUplink()
                ? R.string.uplink_pcm_selected : R.string.downlink_pcm_selected);
        refreshPcmList();
    }

    private boolean canDeletePcmFiles() {
        return !hasAnyCallInProgress()
                && !CallCaptureService.isCapturing()
                && !CallCaptureService.isUplinkInjectionActive()
                && !CallCaptureService.isDownlinkReplacementActive()
                && !previewRunning.get();
    }

    private void confirmDelete(File file) {
        if (!canDeletePcmFiles()) {
            operationStatusText.setText(R.string.delete_pcm_blocked);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_pcm_title)
                .setMessage(getString(R.string.delete_pcm_confirmation, file.getName()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_pcm, (dialog, which) -> deletePcm(file))
                .show();
    }

    private void deletePcm(File file) {
        if (!canDeletePcmFiles()) {
            operationStatusText.setText(R.string.delete_pcm_blocked);
            return;
        }
        if (!file.delete()) {
            operationStatusText.setText(getString(R.string.delete_pcm_failed, file.getName()));
            return;
        }
        PcmFileStore.clearSelectionsForFile(this, file);
        if (file.equals(selectedFile)) {
            selectedFile = null;
        }
        operationStatusText.setText(getString(R.string.delete_pcm_succeeded, file.getName()));
        refreshPcmList();
    }

    private void updateSelectionText() {
        if (selectedFile == null) {
            selectionText.setText(R.string.no_pcm_selected);
        } else {
            selectionText.setText(getString(
                    R.string.selected_pcm_file, selectedFile.getName()));
        }
    }

    private void updateOperationState() {
        boolean uplinkActive = CallCaptureService.isUplinkInjectionActive();
        boolean downlinkActive = CallCaptureService.isDownlinkReplacementActive();
        boolean targetActive = isUplink() ? uplinkActive : downlinkActive;
        if (targetActive) {
            operationStatusText.setText(isUplink()
                    ? CallCaptureService.getUplinkInjectionStatus()
                    : CallCaptureService.getDownlinkReplacementStatus());
        }
    }

    private static boolean hasAnyCallInProgress() {
        return CallStateInCallService.getCurrentCallState() != Call.STATE_DISCONNECTED;
    }

    private void startPreview(File file) {
        if (hasAnyCallInProgress()) {
            operationStatusText.setText(R.string.preview_blocked_by_call);
            return;
        }
        stopPreview("切换试听文件");
        int minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ, AudioFormat.CHANNEL_OUT_MONO, ENCODING);
        if (minBufferSize <= 0) {
            operationStatusText.setText(getString(
                    R.string.status_playback_buffer_failed, minBufferSize));
            return;
        }

        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(ENCODING)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBufferSize * 2, READ_CHUNK_BYTES * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (RuntimeException exception) {
            operationStatusText.setText(getString(
                    R.string.status_playback_failed, exception.getMessage()));
            return;
        }
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            operationStatusText.setText(R.string.status_playback_not_initialized);
            return;
        }
        synchronized (previewLock) {
            previewTrack = track;
            previewFile = file;
            previewRunning.set(true);
        }
        track.play();
        operationStatusText.setText(getString(R.string.previewing_pcm, file.getName()));
        refreshPcmList();
        new Thread(() -> previewLoop(track, file), "PcmInjectionPreview").start();
    }

    private void previewLoop(AudioTrack track, File file) {
        byte[] buffer = new byte[READ_CHUNK_BYTES];
        String error = null;
        try (BufferedInputStream input = new BufferedInputStream(
                new FileInputStream(file), READ_CHUNK_BYTES * 4)) {
            while (previewRunning.get()) {
                int bytesRead = input.read(buffer);
                if (bytesRead < 0) {
                    break;
                }
                int offset = 0;
                while (offset < bytesRead && previewRunning.get()) {
                    int written = track.write(
                            buffer, offset, bytesRead - offset, AudioTrack.WRITE_BLOCKING);
                    if (written < 0) {
                        error = "AudioTrack.write()=" + written;
                        break;
                    }
                    offset += written;
                }
                if (error != null) {
                    break;
                }
            }
        } catch (IOException | RuntimeException exception) {
            error = exception.getClass().getSimpleName() + "：" + exception.getMessage();
        } finally {
            synchronized (previewLock) {
                try {
                    if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop();
                    }
                } catch (IllegalStateException ignored) {
                    // release remains required.
                }
                track.release();
                if (previewTrack == track) {
                    previewTrack = null;
                    previewFile = null;
                    previewRunning.set(false);
                }
            }
        }
        String finalError = error;
        mainHandler.post(() -> {
            if (finalError == null) {
                operationStatusText.setText(R.string.preview_finished);
            } else {
                operationStatusText.setText(getString(R.string.preview_failed, finalError));
            }
            refreshPcmList();
        });
    }

    private void stopPreview(String reason) {
        if (!previewRunning.getAndSet(false)) {
            return;
        }
        AudioTrack track;
        synchronized (previewLock) {
            track = previewTrack;
        }
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
            } catch (IllegalStateException ignored) {
                // Preview thread releases the track.
            }
        }
        operationStatusText.setText(reason);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                    this,
                    statusReceiver,
                    new IntentFilter(CallCaptureService.ACTION_STATUS_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        refreshPcmList();
        mainHandler.removeCallbacks(statePoll);
        mainHandler.post(statePoll);
    }

    @Override
    protected void onStop() {
        mainHandler.removeCallbacks(statePoll);
        stopPreview("页面离开，停止试听");
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        stopPreview("页面销毁");
        super.onDestroy();
    }
}
