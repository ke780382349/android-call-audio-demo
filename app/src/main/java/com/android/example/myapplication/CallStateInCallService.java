package com.android.example.myapplication;

import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import android.util.Log;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Non-UI InCallService. Telecom binds this alongside the system dialer because the package holds
 * CONTROL_INCALL_EXPERIENCE. It observes call state only and never controls or renders calls.
 */
public final class CallStateInCallService extends InCallService {

    private static final String TAG = "CallStateInCallService";
    private static volatile int currentCallState = Call.STATE_DISCONNECTED;
    private static volatile CallStateInCallService instance;

    private final Map<Call, Call.Callback> callbacks = new IdentityHashMap<>();

    public static int getCurrentCallState() {
        return currentCallState;
    }

    /** Returns 1 when muted, 0 when unmuted, and -1 while Telecom is not bound. */
    public static int getCurrentMuteState() {
        CallStateInCallService service = instance;
        if (service == null) {
            return -1;
        }
        CallAudioState audioState = service.getCallAudioState();
        return audioState == null ? -1 : (audioState.isMuted() ? 1 : 0);
    }

    public static boolean requestMuted(boolean muted) {
        CallStateInCallService service = instance;
        if (service == null) {
            return false;
        }
        service.setMuted(muted);
        Log.i(TAG, "Requested Telecom microphone mute=" + muted);
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        Call.Callback callback = new Call.Callback() {
            @Override
            public void onStateChanged(Call changedCall, int state) {
                publishAggregateCallState();
            }
        };
        callbacks.put(call, callback);
        call.registerCallback(callback);
        publishAggregateCallState();
    }

    @Override
    public void onCallRemoved(Call call) {
        Call.Callback callback = callbacks.remove(call);
        if (callback != null) {
            call.unregisterCallback(callback);
        }
        publishAggregateCallState();
        super.onCallRemoved(call);
    }

    private void publishAggregateCallState() {
        int state = calculateAggregateCallState();
        if (state != currentCallState) {
            Log.i(TAG, "Telecom call state: " + callStateName(currentCallState)
                    + " -> " + callStateName(state));
        }
        currentCallState = state;
        CallCaptureService.onTelecomCallStateChanged(state);
    }

    @SuppressWarnings("deprecation")
    private int calculateAggregateCallState() {
        int fallbackState = Call.STATE_DISCONNECTED;
        for (Call call : callbacks.keySet()) {
            int state = call.getState();
            if (state == Call.STATE_ACTIVE) {
                return Call.STATE_ACTIVE;
            }
            if (state == Call.STATE_DIALING
                    || state == Call.STATE_CONNECTING
                    || state == Call.STATE_PULLING_CALL) {
                fallbackState = state;
            } else if (fallbackState == Call.STATE_DISCONNECTED
                    && (state == Call.STATE_RINGING
                    || state == Call.STATE_SIMULATED_RINGING
                    || state == Call.STATE_AUDIO_PROCESSING
                    || state == Call.STATE_SELECT_PHONE_ACCOUNT
                    || state == Call.STATE_HOLDING)) {
                fallbackState = state;
            }
        }
        return fallbackState;
    }

    private static String callStateName(int state) {
        switch (state) {
            case Call.STATE_NEW:
                return "NEW";
            case Call.STATE_DIALING:
                return "DIALING";
            case Call.STATE_RINGING:
                return "RINGING";
            case Call.STATE_HOLDING:
                return "HOLDING";
            case Call.STATE_ACTIVE:
                return "ACTIVE";
            case Call.STATE_DISCONNECTED:
                return "DISCONNECTED";
            case Call.STATE_SELECT_PHONE_ACCOUNT:
                return "SELECT_PHONE_ACCOUNT";
            case Call.STATE_CONNECTING:
                return "CONNECTING";
            case Call.STATE_DISCONNECTING:
                return "DISCONNECTING";
            case Call.STATE_PULLING_CALL:
                return "PULLING_CALL";
            case Call.STATE_AUDIO_PROCESSING:
                return "AUDIO_PROCESSING";
            case Call.STATE_SIMULATED_RINGING:
                return "SIMULATED_RINGING";
            default:
                return String.valueOf(state);
        }
    }

    @Override
    public void onDestroy() {
        for (Map.Entry<Call, Call.Callback> entry : callbacks.entrySet()) {
            entry.getKey().unregisterCallback(entry.getValue());
        }
        callbacks.clear();
        currentCallState = Call.STATE_DISCONNECTED;
        CallCaptureService.onTelecomCallStateChanged(currentCallState);
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
