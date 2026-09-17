package dev.maverick.ftbridge.settings;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
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

import dev.maverick.ftbridge.control.ControlProtocol;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings and live status of the FT Bridge service.
 *
 * This app is only the UI. The settings live in the service app and are read and written
 * through its control service, so whatever the PC setup provisioned is shown here and can be
 * changed. The service keeps running when this app is closed or killed.
 */
public final class SettingsActivity extends Activity {
    private static final long STATUS_REFRESH_MS = 500;
    private static final String NOT_INSTALLED_MESSAGE =
            "The FT Bridge service app is not installed (or has a different signature). "
                    + "Run the PC setup again.";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Messenger replyMessenger =
            new Messenger(new Handler(Looper.getMainLooper(), this::handleReply));
    // Null while not connected to the control service
    private Messenger control;
    private boolean bindRequested;
    // The fields are filled from the service once per connection, so edits are not overwritten
    private boolean fieldsLoaded;

    private EditText hostInput;
    private CheckBox eyeTrackingInput;
    private CheckBox faceTrackingInput;
    private CheckBox alwaysForwardInput;
    private CheckBox virtualDesktopInput;
    private CheckBox steamLinkInput;
    private CheckBox customAppInput;
    private EditText customAppPackageInput;
    private CheckBox autostartInput;
    private EditText portInput;
    private EditText rateInput;
    private EditText frameRateInput;
    private TextView statusView;

    private final Runnable statusRefresh = new Runnable() {
        @Override
        public void run() {
            send(ControlProtocol.MSG_GET_STATE, null);
            handler.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            control = new Messenger(service);
            fieldsLoaded = false;
            send(ControlProtocol.MSG_GET_STATE, null);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            control = null;
            statusView.setText("Lost the connection to the FT Bridge service, reconnecting ...");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            control = null;
            statusView.setText(NOT_INSTALLED_MESSAGE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int padding = dp(16);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding);

        layout.addView(label("PC address (IP of the machine running VRCFaceTracking)"));
        hostInput = input("", InputType.TYPE_CLASS_TEXT);
        layout.addView(hostInput);

        eyeTrackingInput = checkBox("Eye tracking");
        layout.addView(eyeTrackingInput);
        faceTrackingInput = checkBox("Face tracking");
        layout.addView(faceTrackingInput);

        layout.addView(heading("When to forward"));
        alwaysForwardInput = checkBox("Always forward tracking data");
        alwaysForwardInput.setOnCheckedChangeListener((view, checked) -> updateGateInputs());
        layout.addView(alwaysForwardInput);
        virtualDesktopInput = checkBox("Auto-start with Virtual Desktop");
        layout.addView(virtualDesktopInput);
        steamLinkInput = checkBox("Auto-start with Steam Link");
        layout.addView(steamLinkInput);
        customAppInput = checkBox("Auto-start with a custom app");
        customAppInput.setOnCheckedChangeListener((view, checked) -> updateGateInputs());
        layout.addView(customAppInput);
        layout.addView(label("Package name of the custom app (e.g. com.example.app)"));
        customAppPackageInput = input("", InputType.TYPE_CLASS_TEXT);
        layout.addView(customAppPackageInput);

        layout.addView(heading("Service"));
        autostartInput = checkBox("Start the service after the headset boots");
        layout.addView(autostartInput);

        layout.addView(heading("Advanced"));
        layout.addView(label("Port (the VRCFT-ALVR module listens on " + ControlProtocol.DEFAULT_PORT + ")"));
        portInput = input("", InputType.TYPE_CLASS_NUMBER);
        layout.addView(portInput);
        layout.addView(label("Poll/send rate (Hz). The VIVE trackers sample at "
                + (int) ControlProtocol.DEFAULT_RATE_HZ));
        rateInput = input("", InputType.TYPE_CLASS_NUMBER);
        layout.addView(rateInput);
        layout.addView(label("OpenXR frame rate (Hz). Keeps the session running: lower = less CPU, "
                + "0 = no frames (no tracker data on VIVE). Default "
                + (int) ControlProtocol.DEFAULT_FRAME_RATE_HZ));
        frameRateInput = input("", InputType.TYPE_CLASS_NUMBER);
        layout.addView(frameRateInput);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("Apply and start", this::apply));
        buttons.addView(button("Stop service", () -> send(ControlProtocol.MSG_STOP, null)));
        buttons.addView(button("Reload", () -> {
            fieldsLoaded = false;
            send(ControlProtocol.MSG_GET_STATE, null);
        }));
        layout.addView(buttons);

        statusView = new TextView(this);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setPadding(0, padding, 0, 0);
        statusView.setText("Connecting to the FT Bridge service ...");
        layout.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        updateGateInputs();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent().setComponent(new ComponentName(
                ControlProtocol.SERVICE_PACKAGE, ControlProtocol.CONTROL_SERVICE_CLASS));
        bindRequested = true;
        if (!bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            statusView.setText(NOT_INSTALLED_MESSAGE);
        }
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

    @Override
    protected void onStop() {
        if (bindRequested) {
            unbindService(connection);
            bindRequested = false;
        }
        control = null;
        super.onStop();
    }

    private void apply() {
        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            Toast.makeText(this, "Enter the PC address first", Toast.LENGTH_SHORT).show();
            return;
        }

        Bundle settings = new Bundle();
        settings.putString(ControlProtocol.KEY_HOST, host);
        try {
            settings.putInt(ControlProtocol.KEY_PORT,
                    Integer.parseInt(portInput.getText().toString().trim()));
            settings.putFloat(ControlProtocol.KEY_RATE_HZ,
                    Float.parseFloat(rateInput.getText().toString().trim()));
            settings.putFloat(ControlProtocol.KEY_FRAME_RATE_HZ,
                    Float.parseFloat(frameRateInput.getText().toString().trim()));
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid port or rate", Toast.LENGTH_SHORT).show();
            return;
        }
        settings.putBoolean(ControlProtocol.KEY_EYE_TRACKING, eyeTrackingInput.isChecked());
        settings.putBoolean(ControlProtocol.KEY_FACE_TRACKING, faceTrackingInput.isChecked());
        settings.putBoolean(ControlProtocol.KEY_ALWAYS_FORWARD, alwaysForwardInput.isChecked());
        settings.putBoolean(ControlProtocol.KEY_AUTOSTART, autostartInput.isChecked());

        List<String> gatePackages = new ArrayList<>();
        if (virtualDesktopInput.isChecked()) {
            gatePackages.add(ControlProtocol.VIRTUAL_DESKTOP_PACKAGE);
        }
        if (steamLinkInput.isChecked()) {
            gatePackages.add(ControlProtocol.STEAM_LINK_PACKAGE);
        }
        if (customAppInput.isChecked()) {
            gatePackages.addAll(ControlProtocol.parsePackageList(
                    customAppPackageInput.getText().toString()));
        }
        settings.putString(ControlProtocol.KEY_GATE_PACKAGES,
                ControlProtocol.joinPackageList(gatePackages));

        if (!eyeTrackingInput.isChecked() && !faceTrackingInput.isChecked()) {
            Toast.makeText(this, "Eye and face tracking are both off: nothing will be forwarded",
                    Toast.LENGTH_LONG).show();
        } else if (!alwaysForwardInput.isChecked() && gatePackages.isEmpty()) {
            Toast.makeText(this, "No app selected: forwarding all the time",
                    Toast.LENGTH_LONG).show();
        }

        if (send(ControlProtocol.MSG_APPLY, settings)) {
            Toast.makeText(this, "Applied", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean send(int what, Bundle data) {
        if (control == null) {
            return false;
        }

        Message message = Message.obtain(null, what);
        if (data != null) {
            message.setData(data);
        }
        message.replyTo = replyMessenger;
        try {
            control.send(message);
            return true;
        } catch (RemoteException e) {
            control = null;
            statusView.setText("The FT Bridge service is not responding: " + e);
            return false;
        }
    }

    private boolean handleReply(Message message) {
        if (message.what != ControlProtocol.MSG_STATE) {
            return false;
        }

        Bundle state = message.getData();
        if (!fieldsLoaded) {
            loadFields(state);
            fieldsLoaded = true;
        }
        statusView.setText(formatStatus(state));
        return true;
    }

    private void loadFields(Bundle settings) {
        hostInput.setText(settings.getString(ControlProtocol.KEY_HOST, ""));
        portInput.setText(String.valueOf(
                settings.getInt(ControlProtocol.KEY_PORT, ControlProtocol.DEFAULT_PORT)));
        rateInput.setText(formatRate(
                settings.getFloat(ControlProtocol.KEY_RATE_HZ, ControlProtocol.DEFAULT_RATE_HZ)));
        frameRateInput.setText(formatRate(settings.getFloat(
                ControlProtocol.KEY_FRAME_RATE_HZ, ControlProtocol.DEFAULT_FRAME_RATE_HZ)));
        eyeTrackingInput.setChecked(settings.getBoolean(ControlProtocol.KEY_EYE_TRACKING, true));
        faceTrackingInput.setChecked(settings.getBoolean(ControlProtocol.KEY_FACE_TRACKING, true));
        alwaysForwardInput.setChecked(settings.getBoolean(ControlProtocol.KEY_ALWAYS_FORWARD, false));
        autostartInput.setChecked(settings.getBoolean(ControlProtocol.KEY_AUTOSTART, false));

        List<String> gatePackages = ControlProtocol.parsePackageList(
                settings.getString(ControlProtocol.KEY_GATE_PACKAGES));
        virtualDesktopInput.setChecked(gatePackages.remove(ControlProtocol.VIRTUAL_DESKTOP_PACKAGE));
        steamLinkInput.setChecked(gatePackages.remove(ControlProtocol.STEAM_LINK_PACKAGE));
        customAppInput.setChecked(!gatePackages.isEmpty());
        if (!gatePackages.isEmpty()) {
            customAppPackageInput.setText(ControlProtocol.joinPackageList(gatePackages));
        }
        updateGateInputs();
    }

    /** "Always forward" overrides the app list; the custom package only matters when selected. */
    private void updateGateInputs() {
        boolean gated = !alwaysForwardInput.isChecked();
        virtualDesktopInput.setEnabled(gated);
        steamLinkInput.setEnabled(gated);
        customAppInput.setEnabled(gated);
        customAppPackageInput.setEnabled(gated && customAppInput.isChecked());
    }

    private static String formatStatus(Bundle state) {
        StringBuilder text = new StringBuilder();
        text.append("service:    ")
                .append(state.getBoolean(ControlProtocol.KEY_SERVICE_RUNNING) ? "running" : "stopped")
                .append(" (FT Bridge ").append(state.getString(ControlProtocol.KEY_VERSION, "?"))
                .append(")\n");
        if (!state.getBoolean(ControlProtocol.KEY_USAGE_ACCESS, true)) {
            text.append("usage access not granted: cannot detect the foreground app, "
                    + "run the PC setup again\n");
        }
        text.append("gate:       ").append(state.getString(ControlProtocol.KEY_GATE_STATUS, "")).append('\n');

        try {
            JSONObject status = new JSONObject(state.getString(ControlProtocol.KEY_BRIDGE_STATUS, "{}"));
            String error = status.optString("last_error");
            text.append("phase:      ").append(status.optString("phase")).append('\n')
                    .append("session:    ").append(status.optString("session_state")).append('\n')
                    .append("runtime:    ").append(status.optString("runtime_name")).append('\n')
                    .append("sources:    ").append(status.optString("sources")).append('\n')
                    .append("packets:    ").append(status.optLong("packets_sent"))
                    .append(" (changed ").append(status.optLong("changed_packets"))
                    .append(", failed ").append(status.optLong("sends_failed"))
                    .append(", idle polls ").append(status.optLong("idle_polls")).append(")\n")
                    .append("last data:  ").append(status.optString("last_segments")).append('\n');
            if (!error.isEmpty()) {
                text.append("error:      ").append(error).append('\n');
            }
        } catch (JSONException e) {
            text.append(state.getString(ControlProtocol.KEY_BRIDGE_STATUS, ""));
        }

        return text.toString();
    }

    private static String formatRate(float rate) {
        return rate == (int) rate ? String.valueOf((int) rate) : String.valueOf(rate);
    }

    private TextView heading(String text) {
        TextView view = label(text);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(12), 0, 0);
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        return view;
    }

    private CheckBox checkBox(String text) {
        CheckBox view = new CheckBox(this);
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

    private Button button(String text, Runnable action) {
        Button view = new Button(this);
        view.setText(text);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
