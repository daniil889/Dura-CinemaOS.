package com.techgenius.tvoslauncher;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional AI chat for Dura-CinemaOS. Connects to an Ollama-compatible server chosen by the user.
 * No model, account, API key, or paid service is bundled. The model runs wherever that server runs;
 * the TV launcher is only the client. HTTP is accepted only for local/private-network hosts.
 */
final class AssistantPanel {
    private static final String PREFS = "dura_ai_assistant";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_MODEL = "model";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final List<JSONObject> HISTORY = new ArrayList<>();
    private static AlertDialog activeDialog;
    private static TextView transcript;
    private static ScrollView scroll;
    private static EditText prompt;
    private static Button sendButton;
    private static boolean busy;

    private AssistantPanel() { }

    static void show(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String endpoint = p.getString(KEY_ENDPOINT, "");
        String model = p.getString(KEY_MODEL, "qwen2.5:0.5b");
        if (endpoint.trim().isEmpty()) {
            configure(context, endpoint, model, () -> openChat(context));
        } else {
            openChat(context);
        }
    }

    private static void configure(Context context, String oldEndpoint, String oldModel, Runnable done) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.px(context, 20);
        form.setPadding(pad, pad, pad, pad);

        TextView hint = new TextView(context);
        hint.setText("Connect an Ollama server on your local network. The model runs on that server, not inside this launcher. Example: http://192.168.1.20:11434");
        hint.setTextColor(0xFFB8C6D8);
        hint.setTextSize(13f);
        form.addView(hint);

        EditText endpoint = new EditText(context);
        endpoint.setSingleLine(true);
        endpoint.setText(oldEndpoint);
        endpoint.setHint("http://192.168.1.20:11434");
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.px(context, 48));
        ep.topMargin = Ui.px(context, 14);
        form.addView(endpoint, ep);

        EditText model = new EditText(context);
        model.setSingleLine(true);
        model.setText(oldModel);
        model.setHint("qwen2.5:0.5b");
        model.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.px(context, 48));
        mp.topMargin = Ui.px(context, 8);
        form.addView(model, mp);

        new AlertDialog.Builder(context)
                .setTitle("AI Assistant · Server")
                .setView(form)
                .setPositiveButton("Connect", (d, w) -> {
                    String url = endpoint.getText().toString().trim();
                    String name = model.getText().toString().trim();
                    String error = validate(url, name);
                    if (error != null) {
                        new AlertDialog.Builder(context).setTitle("Connection settings")
                                .setMessage(error).setPositiveButton("OK", null).show();
                        return;
                    }
                    context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(KEY_ENDPOINT, trimSlash(url))
                            .putString(KEY_MODEL, name).apply();
                    if (done != null) done.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String validate(String endpoint, String model) {
        if (endpoint.isEmpty() || model.isEmpty()) return "Enter both a server address and a model name.";
        try {
            URL u = new URL(endpoint);
            String scheme = u.getProtocol();
            if (!"https".equalsIgnoreCase(scheme)
                    && !("http".equalsIgnoreCase(scheme) && isPrivateHost(u.getHost()))) {
                return "Use HTTPS, or HTTP only for a local/private-network server.";
            }
            if (u.getHost() == null || u.getHost().isEmpty()) return "The server address is invalid.";
        } catch (Exception e) {
            return "The server address is invalid. Include http:// or https://.";
        }
        return null;
    }

    private static boolean isPrivateHost(String host) {
        String h = host == null ? "" : host.toLowerCase(java.util.Locale.US);
        if ("localhost".equals(h) || h.endsWith(".local") || h.equals("::1")) return true;
        String[] parts = h.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]), b = Integer.parseInt(parts[1]);
            int d = Integer.parseInt(parts[3]);
            for (String part : parts) {
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) return false;
            }
            return a == 10 || a == 127 || (a == 192 && b == 168)
                    || (a == 172 && b >= 16 && b <= 31);
        } catch (Exception e) {
            return false;
        }
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static void openChat(Context context) {
        HISTORY.clear();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.px(context, 16);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView status = new TextView(context);
        status.setText("Connected model · " + context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODEL, "qwen2.5:0.5b"));
        status.setTextColor(0xFFB8C6D8);
        status.setTextSize(12f);
        top.addView(status, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button settings = new Button(context);
        settings.setText("Server");
        top.addView(settings, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.px(context, 42)));
        root.addView(top);

        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0x22101825);
        transcript = new TextView(context);
        transcript.setTextColor(Color.WHITE);
        transcript.setTextSize(15f);
        transcript.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        transcript.setPadding(pad, pad, pad, pad);
        transcript.setText("Dura AI is ready. Ask a question or give a launcher-related request.\n");
        scroll.addView(transcript, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = Ui.px(context, 10);
        root.addView(scroll, slp);

        LinearLayout composer = new LinearLayout(context);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        prompt = new EditText(context);
        prompt.setTextColor(Color.WHITE);
        prompt.setHintTextColor(0xFF9EADBE);
        prompt.setHint("Message the assistant…");
        prompt.setSingleLine(false);
        prompt.setMinLines(1);
        prompt.setMaxLines(3);
        prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.addView(prompt, new LinearLayout.LayoutParams(0, Ui.px(context, 54), 1f));
        sendButton = new Button(context);
        sendButton.setText("Send");
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                Ui.px(context, 100), Ui.px(context, 48));
        sendLp.leftMargin = Ui.px(context, 8);
        composer.addView(sendButton, sendLp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.px(context, 10);
        root.addView(composer, clp);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Dura AI")
                .setView(root)
                .setNegativeButton("Close", null)
                .create();
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> {
            activeDialog = null;
            transcript = null;
            scroll = null;
            prompt = null;
            sendButton = null;
            busy = false;
        });
        settings.setOnClickListener(v -> {
            String ep = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_ENDPOINT, "");
            String model = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_MODEL, "qwen2.5:0.5b");
            configure(context, ep, model, () -> {
                if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
                openChat(context);
            });
        });
        sendButton.setOnClickListener(v -> send(context));
        prompt.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND) {
                send(context);
                return true;
            }
            return false;
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(Math.min(Ui.px(context, 900), context.getResources().getDisplayMetrics().widthPixels - Ui.px(context, 80)),
                    Math.min(Ui.px(context, 620), context.getResources().getDisplayMetrics().heightPixels - Ui.px(context, 100)));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        prompt.requestFocus();
    }

    private static void send(Context context) {
        if (busy || prompt == null || transcript == null) return;
        String message = prompt.getText().toString().trim();
        if (message.isEmpty()) return;
        prompt.setText("");
        append("You", message);
        JSONObject user = new JSONObject();
        try {
            user.put("role", "user");
            user.put("content", message);
            HISTORY.add(user);
        } catch (Exception ignored) { }
        busy = true;
        if (sendButton != null) sendButton.setEnabled(false);
        append("Dura AI", "Thinking…");
        final String endpoint = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ENDPOINT, "");
        final String model = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODEL, "qwen2.5:0.5b");
        final JSONArray messages = new JSONArray();
        try {
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", "You are Dura AI, a concise assistant for an Android TV launcher. Be accurate. Do not claim to control device features unless the launcher exposes that action. Respond in the user's language.");
            messages.put(system);
            for (JSONObject item : HISTORY) messages.put(item);
        } catch (Exception ignored) { }
        IO.execute(() -> {
            String answer;
            try {
                answer = request(endpoint, model, messages);
                JSONObject assistant = new JSONObject();
                assistant.put("role", "assistant");
                assistant.put("content", answer);
                HISTORY.add(assistant);
            } catch (Exception e) {
                answer = "Could not reach the model server: " + e.getMessage();
                if (!HISTORY.isEmpty()) HISTORY.remove(HISTORY.size() - 1);
            }
            final String result = answer;
            new android.os.Handler(context.getMainLooper()).post(() -> {
                busy = false;
                if (sendButton != null) sendButton.setEnabled(true);
                if (transcript != null) {
                    int at = transcript.getText().toString().lastIndexOf("Dura AI: Thinking…");
                    if (at >= 0) {
                        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(transcript.getText());
                        b.replace(at, b.length(), "Dura AI: " + result + "\n\n");
                        transcript.setText(b);
                    } else append("Dura AI", result);
                    if (scroll != null) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                }
            });
        });
    }

    private static String request(String endpoint, String model, JSONArray messages) throws Exception {
        String url = trimSlash(endpoint);
        if (!url.endsWith("/api/chat")) url += "/api/chat";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(90000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try {
            OutputStream out = conn.getOutputStream();
            out.write(bytes);
            out.close();
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) throw new java.io.IOException("HTTP " + code);
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) data.write(buf, 0, n);
            in.close();
            String raw = new String(data.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code + ": " + raw);
            JSONObject root = new JSONObject(raw);
            JSONObject msg = root.optJSONObject("message");
            String content = msg == null ? "" : msg.optString("content", "");
            if (content.trim().isEmpty()) throw new java.io.IOException("The model returned an empty response.");
            return content.trim();
        } finally {
            conn.disconnect();
        }
    }

    private static void append(String who, String message) {
        if (transcript == null) return;
        transcript.append(who + ": " + message + "\n\n");
        if (scroll != null) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
}
