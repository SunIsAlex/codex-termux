package dev.codex.phonebridge;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Executes only pre-validated typed actions. */
public final class ActionExecutor {
    public interface Callback {
        void complete(boolean success, String message);
    }

    private final ChatGptAccessibilityService service;
    private volatile boolean torchEnabled;

    public ActionExecutor(ChatGptAccessibilityService service) {
        this.service = service;
        CameraManager cameras = (CameraManager) service.getSystemService(Context.CAMERA_SERVICE);
        if (cameras != null) {
            cameras.registerTorchCallback(
                    service.getMainExecutor(),
                    new CameraManager.TorchCallback() {
                        @Override
                        public void onTorchModeChanged(String cameraId, boolean enabled) {
                            torchEnabled = enabled;
                        }
                    });
        }
    }

    public void execute(PhoneAction action, Callback callback) {
        try {
            switch (action.kind()) {
                case CLARIFY:
                    callback.complete(false, action.text());
                    return;
                case OPEN_APP:
                    openApp(action.text(), callback);
                    return;
                case SET_VOLUME:
                    setVolume(action.number(), callback);
                    return;
                case SET_BRIGHTNESS:
                    setBrightness(action.number(), callback);
                    return;
                case MEDIA_PLAY_PAUSE:
                    mediaPlayPause(callback);
                    return;
                case TOGGLE_FLASHLIGHT:
                    toggleFlashlight(callback);
                    return;
                case OPEN_SETTINGS_PAGE:
                    openSettings(action.text(), callback);
                    return;
                case LOCK_SCREEN:
                    lockScreen(callback);
                    return;
                case LIST_APPS:
                    callback.complete(false, "应用列表查询必须由会话循环处理");
                    return;
                case START_INTENT:
                    startIntent(action.intent(), callback);
                    return;
                default:
                    callback.complete(false, "不支持的动作");
            }
        } catch (RuntimeException error) {
            callback.complete(false, "执行失败：" + safeError(error));
        }
    }

    public String listApps(String requestedQuery) {
        Intent queryIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager packages = service.getPackageManager();
        String wanted = normalize(requestedQuery);
        List<ResolveInfo> matches = new ArrayList<>();
        for (ResolveInfo activity : packages.queryIntentActivities(queryIntent, 0)) {
            String label = String.valueOf(activity.loadLabel(packages));
            if (wanted.isEmpty()
                    || normalize(label).contains(wanted)
                    || normalize(activity.activityInfo.packageName).contains(wanted)) {
                matches.add(activity);
            }
        }
        matches.sort(Comparator.comparing(
                activity -> String.valueOf(activity.loadLabel(packages)),
                String.CASE_INSENSITIVE_ORDER));

        JSONArray result = new JSONArray();
        for (int i = 0; i < matches.size() && i < 100; i++) {
            ResolveInfo activity = matches.get(i);
            JSONObject app = new JSONObject();
            try {
                app.put("label", String.valueOf(activity.loadLabel(packages)));
                app.put("packageName", activity.activityInfo.packageName);
                app.put(
                        "componentName",
                        new ComponentName(
                                        activity.activityInfo.packageName,
                                        activity.activityInfo.name)
                                .flattenToShortString());
            } catch (JSONException error) {
                throw new IllegalStateException("无法序列化应用列表", error);
            }
            result.put(app);
        }
        return result.toString();
    }

    private void startIntent(IntentSpec spec, Callback callback) {
        Intent intent = new Intent();
        if (spec.action() != null && !spec.action().isEmpty()) {
            intent.setAction(spec.action());
        }
        if (spec.data() != null && !spec.data().isEmpty()) {
            intent.setData(Uri.parse(spec.data()));
        }
        if (spec.component() != null && !spec.component().isEmpty()) {
            ComponentName component = ComponentName.unflattenFromString(spec.component());
            if (component == null) {
                callback.complete(false, "Intent component 格式无效");
                return;
            }
            if (spec.packageName() != null
                    && !spec.packageName().isEmpty()
                    && !spec.packageName().equals(component.getPackageName())) {
                callback.complete(false, "Intent package 与 component 不一致");
                return;
            }
            intent.setComponent(component);
        } else if (spec.packageName() != null && !spec.packageName().isEmpty()) {
            intent.setPackage(spec.packageName());
        }
        for (Map.Entry<String, Object> extra : spec.extras().entrySet()) {
            Object value = extra.getValue();
            if (value instanceof String) {
                intent.putExtra(extra.getKey(), (String) value);
            } else if (value instanceof Boolean) {
                intent.putExtra(extra.getKey(), (Boolean) value);
            } else if (value instanceof Integer) {
                intent.putExtra(extra.getKey(), (Integer) value);
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(service.getPackageManager()) == null) {
            callback.complete(false, "没有 App 能处理这个 Intent");
            return;
        }
        service.startActivity(intent);
        callback.complete(
                true,
                spec.data() == null ? "Intent 已启动" : "Intent 已启动：" + spec.data());
    }

    private void openApp(String requestedName, Callback callback) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager packages = service.getPackageManager();
        List<ResolveInfo> activities = packages.queryIntentActivities(query, 0);
        String wanted = normalize(requestedName);
        List<ResolveInfo> exact = new ArrayList<>();
        List<ResolveInfo> partial = new ArrayList<>();
        for (ResolveInfo activity : activities) {
            String label = normalize(String.valueOf(activity.loadLabel(packages)));
            if (label.equals(wanted)) {
                exact.add(activity);
            } else if (label.contains(wanted) || wanted.contains(label)) {
                partial.add(activity);
            }
        }
        List<ResolveInfo> matches = exact.isEmpty() ? partial : exact;
        if (matches.size() != 1) {
            callback.complete(false, matches.isEmpty() ? "没有找到这个应用" : "找到多个同名应用，请说完整名称");
            return;
        }
        ResolveInfo match = matches.get(0);
        Intent launch = packages.getLaunchIntentForPackage(match.activityInfo.packageName);
        if (launch == null) {
            callback.complete(false, "这个应用没有可启动入口");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        service.startActivity(launch);
        callback.complete(true, "已打开" + match.loadLabel(packages));
    }

    private void setVolume(int percent, Callback callback) {
        AudioManager audio = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
        int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int value = Math.round(maximum * percent / 100.0f);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, AudioManager.FLAG_SHOW_UI);
        int observed = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int observedPercent = Math.round(observed * 100.0f / maximum);
        callback.complete(true, "媒体音量已设为百分之" + observedPercent);
    }

    private void setBrightness(int percent, Callback callback) {
        int value = Math.max(1, Math.round(255 * percent / 100.0f));
        if (Settings.System.canWrite(service)) {
            boolean changed = Settings.System.putInt(
                    service.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
            int observed = Settings.System.getInt(
                    service.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
            boolean verified = changed && Math.abs(observed - value) <= 1;
            callback.complete(
                    verified, verified ? "亮度已设为百分之" + percent : "系统拒绝或未应用亮度修改");
            return;
        }
        callback.complete(false, "请在 Phone Bridge 中授权修改系统设置");
    }

    private void mediaPlayPause(Callback callback) {
        AudioManager audio = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
        KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        audio.dispatchMediaKeyEvent(down);
        audio.dispatchMediaKeyEvent(up);
        callback.complete(true, "已切换播放状态");
    }

    private void toggleFlashlight(Callback callback) {
        if (service.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            callback.complete(false, "请先在 Phone Bridge 中授予相机权限");
            return;
        }
        CameraManager cameras = (CameraManager) service.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : cameras.getCameraIdList()) {
                CameraCharacteristics details = cameras.getCameraCharacteristics(cameraId);
                Boolean flash = details.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = details.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash)
                        && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    boolean next = !torchEnabled;
                    cameras.setTorchMode(cameraId, next);
                    torchEnabled = next;
                    callback.complete(true, next ? "手电筒已打开" : "手电筒已关闭");
                    return;
                }
            }
            callback.complete(false, "没有找到可用的闪光灯");
        } catch (CameraAccessException error) {
            callback.complete(false, "无法控制闪光灯");
        }
    }

    private void openSettings(String page, Callback callback) {
        String action;
        switch (page) {
            case "wifi":
                action = Settings.ACTION_WIFI_SETTINGS;
                break;
            case "bluetooth":
                action = Settings.ACTION_BLUETOOTH_SETTINGS;
                break;
            case "display":
                action = Settings.ACTION_DISPLAY_SETTINGS;
                break;
            case "accessibility":
                action = Settings.ACTION_ACCESSIBILITY_SETTINGS;
                break;
            default:
                callback.complete(false, "未知设置页面");
                return;
        }
        service.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        callback.complete(true, "已打开设置页面");
    }

    private void lockScreen(Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            callback.complete(false, "当前 Android 版本不支持安全锁屏动作");
            return;
        }
        boolean locked = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        callback.complete(locked, locked ? "屏幕已锁定" : "锁屏失败");
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private static String safeError(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.length() > 100 ? error.getClass().getSimpleName() : message;
    }
}
