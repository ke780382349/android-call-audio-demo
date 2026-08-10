package com.android.example.myapplication;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.Call;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PcmPlayback";
    private static final String CAPTURE_AUDIO_OUTPUT_PERMISSION =
            "android.permission.CAPTURE_AUDIO_OUTPUT";
    private static final String CONTROL_INCALL_EXPERIENCE_PERMISSION =
            "android.permission.CONTROL_INCALL_EXPERIENCE";
    private static final String MODIFY_PHONE_STATE_PERMISSION =
            "android.permission.MODIFY_PHONE_STATE";
    private static final String CALL_AUDIO_INTERCEPTION_PERMISSION =
            "android.permission.CALL_AUDIO_INTERCEPTION";
    private static final int RECORD_AUDIO_REQUEST_CODE = 1001;
    private static final int NOTIFICATION_REQUEST_CODE = 1002;

    private static final int SAMPLE_RATE_HZ = 48_000;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int READ_CHUNK_BYTES = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE / 20;

    private final Object playbackLock = new Object();
    private final AtomicBoolean playbackRunning = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView permissionText;
    private TextView playbackStatusText;
    private TextView outputPathText;
    private TextView automaticCaptureStatusText;
    private TextView uplinkInjectionStatusText;
    private TextView downlinkReplacementStatusText;
    private TextView uplinkSelectedPcmText;
    private TextView downlinkSelectedPcmText;
    private Button playDownlinkButton;
    private Button playUplinkButton;
    private Button stopPlaybackButton;
    private Button startMonitoringButton;
    private Button stopMonitoringButton;
    private Button startUplinkInjectionButton;
    private Button stopUplinkInjectionButton;
    private Button selectUplinkPcmButton;
    private Button selectDownlinkPcmButton;
    private Button startDownlinkReplacementButton;
    private Button stopDownlinkReplacementButton;

    private AudioTrack audioTrack;
    private volatile File lastDownlinkOutputFile;
    private volatile File lastUplinkOutputFile;
    private volatile File lastCompletedDownlinkOutputFile;
    private boolean startMonitoringAfterPermissionGrant;
    private boolean startMonitoringAfterNotificationPermission;
    private boolean serviceStatusReceiverRegistered;

    private final Runnable statePoll = new Runnable() {
        @Override
        public void run() {
            if (!serviceStatusReceiverRegistered) {
                return;
            }
            updateButtons();
            mainHandler.postDelayed(this, 500);
        }
    };

    private final BroadcastReceiver serviceStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (playbackRunning.get() && isCallInProgress()) {
                stopPlayback("检测到电话，停止回放以避免干扰通话采集");
            }
            updateAutomaticCaptureDisplay();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        permissionText = findViewById(R.id.permissionText);
        playbackStatusText = findViewById(R.id.playbackStatusText);
        outputPathText = findViewById(R.id.outputPathText);
        automaticCaptureStatusText = findViewById(R.id.automaticCaptureStatusText);
        uplinkInjectionStatusText = findViewById(R.id.uplinkInjectionStatusText);
        downlinkReplacementStatusText = findViewById(R.id.downlinkReplacementStatusText);
        uplinkSelectedPcmText = findViewById(R.id.uplinkSelectedPcmText);
        downlinkSelectedPcmText = findViewById(R.id.downlinkSelectedPcmText);
        playDownlinkButton = findViewById(R.id.playDownlinkButton);
        playUplinkButton = findViewById(R.id.playUplinkButton);
        stopPlaybackButton = findViewById(R.id.stopPlaybackButton);
        startMonitoringButton = findViewById(R.id.startMonitoringButton);
        stopMonitoringButton = findViewById(R.id.stopMonitoringButton);
        startUplinkInjectionButton = findViewById(R.id.startUplinkInjectionButton);
        stopUplinkInjectionButton = findViewById(R.id.stopUplinkInjectionButton);
        selectUplinkPcmButton = findViewById(R.id.selectUplinkPcmButton);
        selectDownlinkPcmButton = findViewById(R.id.selectDownlinkPcmButton);
        startDownlinkReplacementButton = findViewById(R.id.startDownlinkReplacementButton);
        stopDownlinkReplacementButton = findViewById(R.id.stopDownlinkReplacementButton);

        playDownlinkButton.setOnClickListener(view -> startPlayback(false));
        playUplinkButton.setOnClickListener(view -> startPlayback(true));
        stopPlaybackButton.setOnClickListener(view -> stopPlayback("用户停止播放"));
        startMonitoringButton.setOnClickListener(view -> ensurePermissionAndStartMonitoring());
        stopMonitoringButton.setOnClickListener(view -> stopAutomaticMonitoring());
        selectUplinkPcmButton.setOnClickListener(view -> openPcmConfig(
                PcmInjectionConfigActivity.TARGET_UPLINK));
        startUplinkInjectionButton.setOnClickListener(view -> startSelectedInjection(
                PcmInjectionConfigActivity.TARGET_UPLINK));
        stopUplinkInjectionButton.setOnClickListener(view -> stopUplinkInjection());
        selectDownlinkPcmButton.setOnClickListener(view -> openPcmConfig(
                PcmInjectionConfigActivity.TARGET_DOWNLINK));
        startDownlinkReplacementButton.setOnClickListener(view -> startSelectedInjection(
                PcmInjectionConfigActivity.TARGET_DOWNLINK));
        stopDownlinkReplacementButton.setOnClickListener(view -> stopDownlinkReplacement());

        lastDownlinkOutputFile = findLatestOutputFile("downlink_");
        lastUplinkOutputFile = findLatestOutputFile("uplink_");
        lastCompletedDownlinkOutputFile = findLatestCompletedDownlinkFile();
        updateOutputPaths();
        updatePermissionDisplay();
        updateAutomaticCaptureDisplay();
        updateButtons();
    }

    private void ensurePermissionAndStartMonitoring() {
        if (CallCaptureService.isMonitoring()) {
            updateAutomaticCaptureDisplay();
            return;
        }
        if (!hasCaptureAudioOutputPermission() || !hasControlInCallExperiencePermission()) {
            automaticCaptureStatusText.setText(
                    R.string.auto_status_missing_platform_permissions);
            updatePermissionDisplay();
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            startMonitoringAfterPermissionGrant = true;
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    RECORD_AUDIO_REQUEST_CODE);
            return;
        }

        requestNotificationPermissionAndStartMonitoring();
    }

    private void requestNotificationPermissionAndStartMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            startMonitoringAfterNotificationPermission = true;
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_REQUEST_CODE);
            return;
        }
        startAutomaticMonitoring();
    }

    private void startAutomaticMonitoring() {
        automaticCaptureStatusText.setText(R.string.auto_status_starting);
        ContextCompat.startForegroundService(
                this, CallCaptureService.createStartIntent(this));
        updateButtons();
    }

    private void stopAutomaticMonitoring() {
        stopService(new Intent(this, CallCaptureService.class));
        automaticCaptureStatusText.setText(R.string.auto_status_stopping);
        mainHandler.postDelayed(this::updateAutomaticCaptureDisplay, 200);
        updateButtons();
    }

    private void updateAutomaticCaptureDisplay() {
        automaticCaptureStatusText.setText(CallCaptureService.getCurrentStatus());
        File downlinkOutputFile = CallCaptureService.getLastDownlinkOutputFile();
        File uplinkOutputFile = CallCaptureService.getLastUplinkOutputFile();
        File completedDownlinkOutputFile =
                CallCaptureService.getLastCompletedDownlinkOutputFile();
        if (downlinkOutputFile != null && downlinkOutputFile.isFile()) {
            lastDownlinkOutputFile = downlinkOutputFile;
        } else if (lastDownlinkOutputFile != null && !lastDownlinkOutputFile.isFile()) {
            lastDownlinkOutputFile = findLatestOutputFile("downlink_");
        }
        if (uplinkOutputFile != null && uplinkOutputFile.isFile()) {
            lastUplinkOutputFile = uplinkOutputFile;
        } else if (lastUplinkOutputFile != null && !lastUplinkOutputFile.isFile()) {
            lastUplinkOutputFile = findLatestOutputFile("uplink_");
        }
        if (completedDownlinkOutputFile != null
                && completedDownlinkOutputFile.isFile()
                && completedDownlinkOutputFile.length() > 0) {
            lastCompletedDownlinkOutputFile = completedDownlinkOutputFile;
        }
        uplinkInjectionStatusText.setText(CallCaptureService.getUplinkInjectionStatus());
        downlinkReplacementStatusText.setText(
                CallCaptureService.getDownlinkReplacementStatus());
        updateSelectedPcmDisplay();
        updateOutputPaths();
        updateButtons();
    }

    private void openPcmConfig(String target) {
        startActivity(PcmInjectionConfigActivity.createIntent(this, target));
    }

    private void updateSelectedPcmDisplay() {
        File uplinkPcm = PcmFileStore.getSelectedPcm(
                this, PcmInjectionConfigActivity.TARGET_UPLINK);
        File downlinkPcm = PcmFileStore.getSelectedPcm(
                this, PcmInjectionConfigActivity.TARGET_DOWNLINK);
        uplinkSelectedPcmText.setText(uplinkPcm == null
                ? getString(R.string.uplink_pcm_not_selected)
                : getString(R.string.uplink_pcm_selected_file, uplinkPcm.getName()));
        downlinkSelectedPcmText.setText(downlinkPcm == null
                ? getString(R.string.downlink_pcm_not_selected)
                : getString(R.string.downlink_pcm_selected_file, downlinkPcm.getName()));
    }

    private void startSelectedInjection(String target) {
        File selectedPcm = PcmFileStore.getSelectedPcm(this, target);
        boolean uplink = PcmInjectionConfigActivity.TARGET_UPLINK.equals(target);
        if (selectedPcm == null) {
            if (uplink) {
                uplinkInjectionStatusText.setText(R.string.uplink_pcm_not_selected_error);
            } else {
                downlinkReplacementStatusText.setText(R.string.downlink_pcm_not_selected_error);
            }
            updateSelectedPcmDisplay();
            updateButtons();
            return;
        }
        if (CallStateInCallService.getCurrentCallState() != Call.STATE_ACTIVE) {
            if (uplink) {
                uplinkInjectionStatusText.setText(R.string.injection_requires_active_call);
            } else {
                downlinkReplacementStatusText.setText(
                        R.string.downlink_replacement_requires_active_call);
            }
            return;
        }

        Intent serviceIntent = uplink
                ? CallCaptureService.createStartUplinkInjectionIntent(this, selectedPcm)
                : CallCaptureService.createStartDownlinkReplacementIntent(this, selectedPcm);
        ContextCompat.startForegroundService(this, serviceIntent);
        if (uplink) {
            uplinkInjectionStatusText.setText(R.string.injection_starting);
        } else {
            downlinkReplacementStatusText.setText(R.string.downlink_replacement_starting);
        }
        updateButtons();
    }

    private void stopUplinkInjection() {
        startService(CallCaptureService.createStopUplinkInjectionIntent(this));
        uplinkInjectionStatusText.setText(R.string.injection_stopping);
        updateButtons();
    }

    private void stopDownlinkReplacement() {
        startService(CallCaptureService.createStopDownlinkReplacementIntent(this));
        downlinkReplacementStatusText.setText(R.string.downlink_replacement_stopping);
        updateButtons();
    }

    private boolean hasCaptureAudioOutputPermission() {
        return ContextCompat.checkSelfPermission(this, CAPTURE_AUDIO_OUTPUT_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasControlInCallExperiencePermission() {
        return ContextCompat.checkSelfPermission(this, CONTROL_INCALL_EXPERIENCE_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasModifyPhoneStatePermission() {
        return ContextCompat.checkSelfPermission(this, MODIFY_PHONE_STATE_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCallAudioInterceptionPermission() {
        return ContextCompat.checkSelfPermission(this, CALL_AUDIO_INTERCEPTION_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void updatePermissionDisplay() {
        boolean recordAudioGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean captureOutputGranted = hasCaptureAudioOutputPermission();
        boolean controlInCallExperienceGranted = hasControlInCallExperiencePermission();
        boolean modifyPhoneStateGranted = hasModifyPhoneStatePermission();
        boolean callAudioInterceptionGranted = hasCallAudioInterceptionPermission();

        permissionText.setText(getString(
                R.string.permission_state,
                recordAudioGranted ? "已授予" : "未授予",
                captureOutputGranted ? "已授予" : "未授予",
                controlInCallExperienceGranted ? "已授予" : "未授予",
                modifyPhoneStateGranted ? "已授予" : "未授予",
                callAudioInterceptionGranted ? "已授予" : "未授予"));
    }

    private File getCaptureDirectory() {
        File baseDirectory = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (baseDirectory == null) {
            baseDirectory = getFilesDir();
        }
        return new File(baseDirectory, "call_pcm");
    }

    private File findLatestOutputFile(String filePrefix) {
        File[] files = getCaptureDirectory().listFiles(
                file -> file.isFile()
                        && file.getName().startsWith(filePrefix)
                        && file.getName().endsWith(".pcm")
                        && file.length() > 0);
        if (files == null || files.length == 0) {
            return null;
        }

        File latest = files[0];
        for (int index = 1; index < files.length; index++) {
            if (files[index].lastModified() > latest.lastModified()) {
                latest = files[index];
            }
        }
        return latest;
    }

    private File findLatestCompletedDownlinkFile() {
        File serviceCompletedFile = CallCaptureService.getLastCompletedDownlinkOutputFile();
        if (serviceCompletedFile != null
                && serviceCompletedFile.isFile()
                && serviceCompletedFile.length() > 0) {
            return serviceCompletedFile;
        }

        File activeFile = CallCaptureService.isCapturing()
                ? CallCaptureService.getLastDownlinkOutputFile()
                : null;
        File[] files = getCaptureDirectory().listFiles(
                file -> file.isFile()
                        && file.getName().startsWith("downlink_")
                        && file.getName().endsWith(".pcm")
                        && file.length() > 0
                        && (activeFile == null || !file.equals(activeFile)));
        if (files == null || files.length == 0) {
            return null;
        }
        File latest = files[0];
        for (int index = 1; index < files.length; index++) {
            if (files[index].lastModified() > latest.lastModified()) {
                latest = files[index];
            }
        }
        return latest;
    }

    private void updateOutputPaths() {
        File downlinkFile = lastDownlinkOutputFile;
        File uplinkFile = lastUplinkOutputFile;
        if (downlinkFile == null && uplinkFile == null) {
            outputPathText.setText(R.string.output_path_empty);
        } else {
            outputPathText.setText(getString(
                    R.string.output_paths,
                    downlinkFile == null ? "尚未创建" : downlinkFile.getAbsolutePath(),
                    uplinkFile == null ? "尚未创建" : uplinkFile.getAbsolutePath()));
        }
    }

    private void startPlayback(boolean playUplink) {
        if (playbackRunning.get()) {
            return;
        }
        if (isCallInProgress() || CallCaptureService.isCapturing()) {
            playbackStatusText.setText(R.string.status_playback_blocked_by_call);
            updateButtons();
            return;
        }

        String directionName = playUplink ? "上行" : "下行";
        File inputFile = playUplink ? lastUplinkOutputFile : lastDownlinkOutputFile;
        if (inputFile == null || !inputFile.isFile() || inputFile.length() == 0) {
            inputFile = findLatestOutputFile(playUplink ? "uplink_" : "downlink_");
        }
        if (inputFile == null || !inputFile.isFile() || inputFile.length() == 0) {
            playbackStatusText.setText(getString(
                    R.string.status_no_direction_pcm_to_play, directionName));
            if (playUplink) {
                lastUplinkOutputFile = null;
            } else {
                lastDownlinkOutputFile = null;
            }
            updateOutputPaths();
            updateButtons();
            return;
        }
        if (playUplink) {
            lastUplinkOutputFile = inputFile;
        } else {
            lastDownlinkOutputFile = inputFile;
        }

        int minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ, AudioFormat.CHANNEL_OUT_MONO, ENCODING);
        if (minBufferSize <= 0) {
            playbackStatusText.setText(getString(
                    R.string.status_playback_buffer_failed, minBufferSize));
            return;
        }

        int playbackBufferSize = Math.max(minBufferSize * 2, READ_CHUNK_BYTES * 4);
        final AudioTrack track;
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
                    .setBufferSizeInBytes(playbackBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (RuntimeException exception) {
            playbackStatusText.setText(getString(
                    R.string.status_playback_failed, exception.getMessage()));
            return;
        }

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            playbackStatusText.setText(R.string.status_playback_not_initialized);
            return;
        }

        synchronized (playbackLock) {
            if (audioTrack != null) {
                track.release();
                playbackStatusText.setText(R.string.status_previous_playback_not_released);
                return;
            }
            audioTrack = track;
        }

        try {
            track.setVolume(1.0f);
            track.play();
        } catch (IllegalStateException exception) {
            releaseAudioTrack(track);
            playbackStatusText.setText(getString(
                    R.string.status_playback_failed, exception.getMessage()));
            return;
        }

        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            releaseAudioTrack(track);
            playbackStatusText.setText(R.string.status_playback_not_started);
            return;
        }

        playbackRunning.set(true);
        double seconds = inputFile.length() / (double) (SAMPLE_RATE_HZ * BYTES_PER_SAMPLE);
        updateOutputPaths();
        playbackStatusText.setText(getString(
                R.string.status_playing, directionName, inputFile.getName(), seconds));
        updateButtons();
        Log.i(TAG, "Started PCM playback: " + inputFile.getAbsolutePath());

        File finalInputFile = inputFile;
        new Thread(
                () -> playbackLoop(track, finalInputFile),
                "PcmPlayback").start();
    }

    private void playbackLoop(AudioTrack track, File inputFile) {
        byte[] buffer = new byte[READ_CHUNK_BYTES];
        long totalBytes = 0;
        String terminalMessage = null;
        boolean reachedEnd = false;

        try (BufferedInputStream input = new BufferedInputStream(
                new FileInputStream(inputFile), READ_CHUNK_BYTES * 4)) {
            while (playbackRunning.get()) {
                int bytesRead = input.read(buffer);
                if (bytesRead < 0) {
                    reachedEnd = true;
                    break;
                }

                int offset = 0;
                while (offset < bytesRead && playbackRunning.get()) {
                    int bytesWritten = track.write(
                            buffer,
                            offset,
                            bytesRead - offset,
                            AudioTrack.WRITE_BLOCKING);
                    if (bytesWritten < 0) {
                        if (playbackRunning.get()) {
                            terminalMessage = "AudioTrack.write() 失败：" + bytesWritten;
                        }
                        break;
                    }
                    offset += bytesWritten;
                    totalBytes += bytesWritten;
                }
                if (terminalMessage != null) {
                    break;
                }
            }
            if (reachedEnd) {
                waitForPlaybackToDrain(track, totalBytes);
            }
        } catch (IOException exception) {
            terminalMessage = "读取 PCM 文件失败：" + exception.getMessage();
        } catch (RuntimeException exception) {
            terminalMessage = "PCM 播放异常：" + exception.getMessage();
        } finally {
            playbackRunning.set(false);
            releaseAudioTrack(track);

            final String finalMessage = terminalMessage;
            final boolean finalReachedEnd = reachedEnd;
            final long finalBytes = totalBytes;
            Log.i(TAG, "PCM playback finished: bytes=" + totalBytes
                    + ", reachedEnd=" + reachedEnd
                    + (terminalMessage == null ? "" : ", error=" + terminalMessage));
            runOnUiThread(() -> {
                updateButtons();
                if (finalMessage != null) {
                    playbackStatusText.setText(finalMessage);
                } else if (finalReachedEnd) {
                    playbackStatusText.setText(getString(
                            R.string.status_playback_completed, finalBytes));
                } else {
                    playbackStatusText.setText(R.string.status_playback_stopped);
                }
            });
        }
    }

    private void waitForPlaybackToDrain(AudioTrack track, long totalBytes) {
        long targetFrames = totalBytes / BYTES_PER_SAMPLE;
        long timeoutAtMs = SystemClock.elapsedRealtime() + 2_000;
        while (playbackRunning.get()
                && Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < targetFrames
                && SystemClock.elapsedRealtime() < timeoutAtMs) {
            SystemClock.sleep(10);
        }
    }

    private void stopPlayback(String reason) {
        if (!playbackRunning.getAndSet(false)) {
            return;
        }

        Log.i(TAG, "Stopping PCM playback: " + reason);
        playbackStatusText.setText(getString(R.string.status_stopping_playback, reason));
        AudioTrack track;
        synchronized (playbackLock) {
            track = audioTrack;
        }
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
            } catch (IllegalStateException ignored) {
                // 播放线程会在 finally 中完成 release。
            }
        }
    }

    private void releaseAudioTrack(AudioTrack track) {
        synchronized (playbackLock) {
            try {
                if (track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                    track.stop();
                }
            } catch (IllegalStateException ignored) {
                // release 仍需执行。
            }
            track.release();
            if (audioTrack == track) {
                audioTrack = null;
            }
        }
    }

    private static boolean isCallInProgress() {
        return CallStateInCallService.getCurrentCallState() != Call.STATE_DISCONNECTED;
    }

    private void updateButtons() {
        boolean playing = playbackRunning.get();
        boolean monitoring = CallCaptureService.isMonitoring();
        boolean callInProgress = isCallInProgress() || CallCaptureService.isCapturing();
        boolean injecting = CallCaptureService.isUplinkInjectionActive();
        boolean replacingDownlink = CallCaptureService.isDownlinkReplacementActive();
        boolean callActive = CallStateInCallService.getCurrentCallState() == Call.STATE_ACTIVE;
        File selectedUplinkPcm = PcmFileStore.getSelectedPcm(
                this, PcmInjectionConfigActivity.TARGET_UPLINK);
        File selectedDownlinkPcm = PcmFileStore.getSelectedPcm(
                this, PcmInjectionConfigActivity.TARGET_DOWNLINK);
        File downlinkFile = lastDownlinkOutputFile;
        File uplinkFile = lastUplinkOutputFile;
        boolean hasDownlinkFile = downlinkFile != null
                && downlinkFile.isFile()
                && downlinkFile.length() > 0;
        boolean hasUplinkFile = uplinkFile != null
                && uplinkFile.isFile()
                && uplinkFile.length() > 0;

        playDownlinkButton.setEnabled(!playing && !callInProgress && hasDownlinkFile);
        playUplinkButton.setEnabled(!playing && !callInProgress && hasUplinkFile);
        stopPlaybackButton.setEnabled(playing);
        startMonitoringButton.setEnabled(!monitoring);
        stopMonitoringButton.setEnabled(monitoring);
        selectUplinkPcmButton.setEnabled(!injecting && !replacingDownlink);
        selectDownlinkPcmButton.setEnabled(!injecting && !replacingDownlink);
        startUplinkInjectionButton.setEnabled(
                callActive && selectedUplinkPcm != null && !injecting && !replacingDownlink);
        stopUplinkInjectionButton.setEnabled(injecting);
        startDownlinkReplacementButton.setEnabled(
                callActive && selectedDownlinkPcm != null && !injecting && !replacingDownlink);
        stopDownlinkReplacementButton.setEnabled(replacingDownlink);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!serviceStatusReceiverRegistered) {
            ContextCompat.registerReceiver(
                    this,
                    serviceStatusReceiver,
                    new IntentFilter(CallCaptureService.ACTION_STATUS_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            serviceStatusReceiverRegistered = true;
        }
        updateAutomaticCaptureDisplay();
        mainHandler.removeCallbacks(statePoll);
        mainHandler.post(statePoll);
    }

    @Override
    protected void onStop() {
        mainHandler.removeCallbacks(statePoll);
        stopPlayback("页面离开，停止播放");
        if (serviceStatusReceiverRegistered) {
            unregisterReceiver(serviceStatusReceiver);
            serviceStatusReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_REQUEST_CODE) {
            if (startMonitoringAfterNotificationPermission) {
                startMonitoringAfterNotificationPermission = false;
                startAutomaticMonitoring();
            }
            return;
        }
        if (requestCode != RECORD_AUDIO_REQUEST_CODE) {
            return;
        }

        updatePermissionDisplay();
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && startMonitoringAfterPermissionGrant) {
            startMonitoringAfterPermissionGrant = false;
            requestNotificationPermissionAndStartMonitoring();
        } else if (!granted) {
            startMonitoringAfterPermissionGrant = false;
            automaticCaptureStatusText.setText(R.string.status_record_audio_denied);
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        stopPlayback("页面销毁");
        super.onDestroy();
    }
}
