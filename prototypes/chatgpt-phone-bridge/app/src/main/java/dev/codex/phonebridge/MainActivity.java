package dev.codex.phonebridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity implements StatusBus.Listener {
    private static final int AUDIO_PERMISSION_REQUEST = 10;
    private static final int CAMERA_PERMISSION_REQUEST = 11;

    private TextView status;
    private Button physicalPtt;
    private EditText chatUrl;
    private EditText debugCommand;
    private boolean autoListen;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        VoiceFeedback.initialize(this);
        autoListen = getIntent().getBooleanExtra("autoListen", false);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        StatusBus.add(this);
        updatePhysicalPttLabel();
        if (autoListen) {
            autoListen = false;
            status.postDelayed(this::startListening, 250);
        }
    }

    @Override
    protected void onPause() {
        StatusBus.remove(this);
        super.onPause();
    }

    @Override
    public void onStatus(String message) {
        runOnUiThread(() -> status.setText(message));
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        }
    }

    private View buildUi() {
        int padding = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.rgb(250, 250, 250));

        TextView title = new TextView(this);
        title.setText("ChatGPT Phone Bridge");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        root.addView(title, matchWrap(dp(12)));

        TextView explanation = new TextView(this);
        explanation.setText("绑定一个专用 ChatGPT 对话，只发送一次初始化指令。"
                + "之后物理按键只发送精简命令，不读取凭证或开放任意 Shell。");
        explanation.setTextSize(15);
        explanation.setTextColor(Color.DKGRAY);
        root.addView(explanation, matchWrap(dp(20)));

        status = new TextView(this);
        status.setText("准备就绪");
        status.setTextSize(17);
        status.setGravity(Gravity.CENTER);
        root.addView(status, matchWrap(dp(20)));

        chatUrl = new EditText(this);
        chatUrl.setSingleLine(true);
        chatUrl.setHint("https://chatgpt.com/c/对话ID");
        chatUrl.setText(new SessionConfig(this).chatUrl());
        root.addView(chatUrl, matchWrap(dp(8)));

        Button saveChat = button("保存专用对话链接", view -> saveDedicatedChatUrl());
        root.addView(saveChat, matchWrap(dp(8)));

        Button initializeChat = button("发送一次初始化指令", view -> initializeDedicatedChat());
        root.addView(initializeChat, matchWrap(dp(16)));

        TextView debugLabel = new TextView(this);
        debugLabel.setText("纯文本调试");
        debugLabel.setTextSize(16);
        debugLabel.setTextColor(Color.DKGRAY);
        root.addView(debugLabel, matchWrap(dp(4)));

        debugCommand = new EditText(this);
        debugCommand.setSingleLine(true);
        debugCommand.setHint("例如：线上搜索一个热门 YouTube 视频并打开");
        debugCommand.setImeOptions(EditorInfo.IME_ACTION_SEND);
        debugCommand.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitDebugCommand();
                return true;
            }
            return false;
        });
        root.addView(debugCommand, matchWrap(dp(8)));

        Button sendDebug = button("发送文本指令", view -> submitDebugCommand());
        root.addView(sendDebug, matchWrap(dp(8)));

        Button cancelRequest = button("取消当前请求", view -> cancelPendingRequest());
        root.addView(cancelRequest, matchWrap(dp(16)));

        Button speak = button("按键说话", view -> startListening());
        root.addView(speak, matchWrap(dp(10)));

        physicalPtt = button("", view -> {
            boolean enabled = !ChatGptAccessibilityService.isPhysicalPttEnabled(this);
            ChatGptAccessibilityService.setPhysicalPttEnabled(this, enabled);
            updatePhysicalPttLabel();
        });
        root.addView(physicalPtt, matchWrap(dp(10)));

        Button accessibility = button(
                "启用无障碍桥",
                view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, matchWrap(dp(10)));

        Button writeSettings = button("授权亮度控制", view -> {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        root.addView(writeSettings, matchWrap(dp(10)));

        Button camera = button("授权手电筒控制", view -> requestOptionalCameraPermission());
        root.addView(camera, matchWrap(dp(10)));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
            return;
        }
        if (!ChatGptAccessibilityService.isConnected()) {
            StatusBus.post("请先启用无障碍桥");
            VoiceFeedback.speak(this, "请先启用无障碍桥");
            return;
        }
        if (!ChatGptAccessibilityService.startTapToTalk()) {
            StatusBus.post("无法启动语音识别");
        }
    }

    private void submitDebugCommand() {
        String command = debugCommand.getText().toString().trim();
        if (command.isEmpty()) {
            StatusBus.post("请输入文本指令");
            return;
        }
        if (!ChatGptAccessibilityService.isConnected()) {
            StatusBus.post("请先启用无障碍桥");
            return;
        }
        if (ChatGptAccessibilityService.beginSpokenCommand(command)) {
            debugCommand.setText("");
        }
    }

    private void cancelPendingRequest() {
        if (!ChatGptAccessibilityService.isConnected()) {
            StatusBus.post("请先启用无障碍桥");
            return;
        }
        ChatGptAccessibilityService.cancelPendingRequest();
    }

    private boolean saveDedicatedChatUrl() {
        try {
            new SessionConfig(this).saveChatUrl(chatUrl.getText().toString());
            StatusBus.post("专用对话链接已保存");
            return true;
        } catch (IllegalArgumentException error) {
            StatusBus.post(error.getMessage());
            return false;
        }
    }

    private void initializeDedicatedChat() {
        if (!saveDedicatedChatUrl()) {
            return;
        }
        if (!ChatGptAccessibilityService.isConnected()) {
            StatusBus.post("请先启用无障碍桥");
            return;
        }
        if (!ChatGptAccessibilityService.initializeDedicatedChat()) {
            StatusBus.post("无法初始化专用对话");
        }
    }

    private void updatePhysicalPttLabel() {
        if (physicalPtt == null) {
            return;
        }
        boolean enabled = ChatGptAccessibilityService.isPhysicalPttEnabled(this);
        physicalPtt.setText(
                enabled ? "物理按键：音量加 + 音量减（已启用）" : "物理按键组合（已停用）");
    }

    private void requestOptionalCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

}
