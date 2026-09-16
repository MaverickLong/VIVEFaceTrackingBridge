package dev.maverick.ftbridge;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Control panel: target settings, start/stop, and a live status readout. */
public final class MainActivity extends Activity {
    private static final long STATUS_REFRESH_MS = 500;
    private static final int PERMISSION_REQUEST_CODE = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText hostInput;
    private EditText portInput;
    private EditText rateInput;
    private CheckBox autostartInput;
    private EditText frameRateInput;
    private TextView statusView;

    private final Runnable statusRefresh = new Runnable() {
        @Override
        public void run() {
            statusView.setText(formatStatus(NativeCore.status()));
            handler.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Settings settings = new Settings(this);

        int padding = dp(16);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding);

        layout.addView(label("PC address (IP of the machine running VRCFaceTracking)"));
        hostInput = input(settings.host(), InputType.TYPE_CLASS_TEXT);
        layout.addView(hostInput);

        layout.addView(label("Port (VRCFT-ALVR module listens on " + Settings.DEFAULT_PORT + ")"));
        portInput = input(String.valueOf(settings.port()), InputType.TYPE_CLASS_NUMBER);
        layout.addView(portInput);

        layout.addView(label("Poll/send rate (Hz). The VIVE trackers sample at " + (int) Settings.DEFAULT_RATE_HZ));
        rateInput = input(String.valueOf((int) settings.rateHz()), InputType.TYPE_CLASS_NUMBER);
        layout.addView(rateInput);

        autostartInput = new CheckBox(this);
        autostartInput.setText("Start automatically after boot");
        autostartInput.setChecked(settings.autostart());
        layout.addView(autostartInput);

        layout.addView(label("OpenXR frame rate (Hz). Keeps the session running: lower = less CPU, "
                + "0 = no frames (no tracker data on VIVE). Default " + (int) Settings.DEFAULT_FRAME_RATE_HZ));
        frameRateInput = input(String.valueOf((int) settings.frameRateHz()), InputType.TYPE_CLASS_NUMBER);
        layout.addView(frameRateInput);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button startButton = new Button(this);
        startButton.setText("Start");
        startButton.setOnClickListener(v -> startBridge());
        buttons.addView(startButton);
        Button stopButton = new Button(this);
        stopButton.setText("Stop");
        stopButton.setOnClickListener(v -> TrackingService.stop(this));
        buttons.addView(stopButton);
        layout.addView(buttons);

        statusView = new TextView(this);
        statusView.setTypeface(android.graphics.Typeface.MONOSPACE);
        statusView.setPadding(0, padding, 0, 0);
        layout.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        requestMissingPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(statusRefresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(statusRefresh);
        super.onPause();
    }

    private void startBridge() {
        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            Toast.makeText(this, "Enter the PC address first", Toast.LENGTH_SHORT).show();
            return;
        }

        int port;
        float rateHz;
        float frameRateHz;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
            rateHz = Float.parseFloat(rateInput.getText().toString().trim());
            frameRateHz = Float.parseFloat(frameRateInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid port or rate", Toast.LENGTH_SHORT).show();
            return;
        }

        new Settings(this).save(host, port, rateHz, autostartInput.isChecked(), frameRateHz);
        TrackingService.start(this);
    }

    /** Meta and Pico gate eye/face tracking behind runtime permissions; ask for whatever is declared and not granted. */
    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_PERMISSIONS);
            for (String permission : info.requestedPermissions) {
                boolean trackingPermission = permission.endsWith("EYE_TRACKING")
                        || permission.endsWith("FACE_TRACKING")
                        || permission.equals(android.Manifest.permission.RECORD_AUDIO);
                if (trackingPermission
                        && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(permission);
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            return;
        }

        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    private static String formatStatus(String json) {
        try {
            JSONObject status = new JSONObject(json);
            String error = status.optString("last_error");

            return "phase:      " + status.optString("phase") + "\n"
                    + "session:    " + status.optString("session_state") + "\n"
                    + "runtime:    " + status.optString("runtime_name") + "\n"
                    + "sources:    " + status.optString("sources") + "\n"
                    + "packets:    " + status.optLong("packets_sent")
                    + " (failed " + status.optLong("sends_failed")
                    + ", idle polls " + status.optLong("idle_polls") + ")\n"
                    + "last data:  " + status.optString("last_segments") + "\n"
                    + (error.isEmpty() ? "" : "error:      " + error + "\n");
        } catch (JSONException e) {
            return json;
        }
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        return view;
    }

    private EditText input(String value, int inputType) {
        EditText view = new EditText(this);
        view.setInputType(inputType);
        view.setSingleLine();
        view.setText(value);
        return view;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
