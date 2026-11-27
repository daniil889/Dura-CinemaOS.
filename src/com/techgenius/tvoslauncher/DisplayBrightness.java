package com.techgenius.tvoslauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Display brightness controls for Android TV.
 *
 * Android protects system-wide brightness writes behind the special WRITE_SETTINGS access.
 * When that access is unavailable, this module adjusts the launcher Activity's window only,
 * instead of presenting a slider that silently does nothing.
 */
final class DisplayBrightness {
    private DisplayBrightness() { }

    static void show(Context context) {
        Activity activity = context instanceof Activity
                ? (Activity) context : MainActivity.activeInstance;
        if (activity == null) {
            requestSystemAccess(context);
            return;
        }

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.px(activity, 18);
        content.setPadding(pad, pad, pad, pad);

        TextView value = new TextView(activity);
        value.setTextColor(0xFFFFFFFF);
        value.setTextSize(16f);
        value.setText(currentPercent(activity) + "%");
        content.addView(value, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        SeekBar slider = new SeekBar(activity);
        slider.setMax(100);
        slider.setProgress(currentPercent(activity));
        content.addView(slider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.px(activity, 42)));

        TextView mode = new TextView(activity);
        mode.setTextColor(0xFFB8C6D8);
        mode.setTextSize(12f);
        mode.setText(Settings.System.canWrite(activity)
                ? "System brightness"
                : "Launcher window only · grant system access for full-device control");
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.px(activity, 8);
        content.addView(mode, mlp);

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(progress + "%");
                if (fromUser) apply(activity, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Brightness")
                .setView(content)
                .setPositiveButton("Done", null)
                .setNeutralButton(Settings.System.canWrite(activity) ? "System settings" : "Grant system access",
                        (d, which) -> requestSystemAccess(activity))
                .create();
        dialog.show();
    }

    static void apply(Context context, int percent) {
        int safe = Math.max(1, Math.min(100, percent));
        int value = Math.max(1, Math.min(255, Math.round(safe * 2.55f)));
        if (Settings.System.canWrite(context)) {
            try {
                Settings.System.putInt(context.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
                Activity activity = context instanceof Activity
                        ? (Activity) context : MainActivity.activeInstance;
                if (activity != null) {
                    Window window = activity.getWindow();
                    WindowManager.LayoutParams attrs = window.getAttributes();
                    attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                    window.setAttributes(attrs);
                }
                return;
            } catch (SecurityException ignored) {
                // Fall through to the app-window control below.
            }
        }
        Activity activity = context instanceof Activity
                ? (Activity) context : MainActivity.activeInstance;
        if (activity != null) {
            Window window = activity.getWindow();
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.screenBrightness = safe / 100f;
            window.setAttributes(attrs);
        }
    }

    private static int currentPercent(Context context) {
        try {
            int value = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            return Math.max(1, Math.min(100, Math.round(value * 100f / 255f)));
        } catch (Exception ignored) {
            Activity activity = context instanceof Activity
                    ? (Activity) context : MainActivity.activeInstance;
            if (activity != null) {
                float value = activity.getWindow().getAttributes().screenBrightness;
                if (value >= 0f) return Math.max(1, Math.min(100, Math.round(value * 100f)));
            }
            return 70;
        }
    }

    private static void requestSystemAccess(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
            try {
                Intent intent = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
                if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ignoredAgain) { }
        }
    }
}
