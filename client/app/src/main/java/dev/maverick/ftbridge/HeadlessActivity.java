package dev.maverick.ftbridge;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Field;

/**
 * An Activity instance that is never started by the system.
 *
 * The VIVE Wave OpenXR runtime requires the `applicationActivity` handed to
 * xrCreateInstance to be an android.app.Activity (it calls
 * WaveRuntime.create(Activity)), which a background service cannot provide.
 * This object satisfies that requirement: Context calls are delegated to the
 * attached base, and the framework-managed members that would otherwise be
 * null (window manager, display, application, UI thread) are backed by the
 * application context instead.
 */
public final class HeadlessActivity extends Activity {
    private static final String TAG = "ftbridge";
    private static final String PHONE_WINDOW_CLASS = "com.android.internal.policy.PhoneWindow";

    private Window window;

    /** Must be constructed on the main thread: Activity's internal handler binds to the current looper. */
    public HeadlessActivity(Context base) {
        Application application = (Application) base.getApplicationContext();
        attachBaseContext(application);

        // getApplication() is final and Activity's own methods use mWindow directly; both are
        // members only the framework sets, so fill them in through reflection (best effort:
        // they are "unsupported" hidden fields, allowed with a warning on current Android)
        window = createDetachedWindow();
        setPrivateField("mApplication", application);
        setPrivateField("mWindow", window);

        // Activity reads e.g. the theme and orientation from here; this app declares no
        // activity, so describe this one with the application defaults
        ActivityInfo info = new ActivityInfo();
        info.applicationInfo = application.getApplicationInfo();
        info.packageName = application.getPackageName();
        info.name = HeadlessActivity.class.getName();
        info.theme = info.applicationInfo.theme;
        setPrivateField("mActivityInfo", info);
    }

    // The runtime attaches its VrSurfaceView through setContentView; Activity's version also
    // initializes the action bar from framework-managed state, so go straight to the window.
    @Override
    public void setContentView(View view) {
        getWindow().setContentView(view);
    }

    @Override
    public void setContentView(int layoutResId) {
        getWindow().setContentView(layoutResId);
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        getWindow().setContentView(view, params);
    }

    @Override
    public void addContentView(View view, ViewGroup.LayoutParams params) {
        getWindow().addContentView(view, params);
    }

    private void setPrivateField(String name, Object value) {
        try {
            Field field = Activity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(this, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "cannot set Activity." + name + ": " + e);
        }
    }

    /**
     * The Wave runtime accesses the activity's decor view while creating the session. A
     * PhoneWindow that is never attached to the window manager is enough for that.
     */
    @Override
    public Window getWindow() {
        return window;
    }

    private Window createDetachedWindow() {
        try {
            Window created = (Window) Class.forName(PHONE_WINDOW_CLASS)
                    .getConstructor(Context.class)
                    .newInstance(this);
            created.setWindowManager(getWindowManager(), null, null);
            created.setCallback(this);
            return created;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "cannot create detached window: " + e);
            return null;
        }
    }

    @Override
    public Object getSystemService(String name) {
        // Activity special-cases WINDOW_SERVICE and SEARCH_SERVICE through members that are
        // only initialized when the system attaches the activity
        return getBaseContext().getSystemService(name);
    }

    @Override
    public WindowManager getWindowManager() {
        return getBaseContext().getSystemService(WindowManager.class);
    }

    @Override
    public Display getDisplay() {
        return getBaseContext().getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
    }

    @Override
    public ComponentName getComponentName() {
        return new ComponentName(getBaseContext(), HeadlessActivity.class);
    }

    @Override
    public Intent getIntent() {
        return new Intent();
    }

    @Override
    public boolean isFinishing() {
        return false;
    }

    @Override
    public boolean isDestroyed() {
        return false;
    }

    @Override
    public void finish() {
        // Nothing to finish: never started
    }
}
