// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import helium314.keyboard.keyboard.KeyboardSwitcher;
import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.latin.permissions.PermissionsUtil;
import helium314.keyboard.latin.utils.DeviceProtectedUtils;

/**
 * Minimal built-in voice input: the mic key shows a small recording strip over
 * the keyboard, audio is transcribed with OpenAI's transcription API, and the
 * result is committed as text. Model, audio quality, and endpoint are fixed;
 * only the API key is configurable (Settings → Advanced).
 */
public class VoiceInput {
    public static final String PREF_API_KEY = "openai_api_key";

    private static final String API_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final String MODEL = "gpt-transcribe";
    // "Medium" quality: mono AAC in an m4a container
    private static final int SAMPLE_RATE = 22050;
    private static final int BIT_RATE = 192000;

    private final LatinIME mLatinIme;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private MediaRecorder mRecorder;
    private File mAudioFile;
    private PopupWindow mPopup;
    private TextView mTimerText;
    private TextView mStatusText;
    private boolean mRecording = false;
    private boolean mProcessing = false;
    private long mStartTime = 0;

    private final Runnable mTimerTick = new Runnable() {
        @Override
        public void run() {
            if (!mRecording || mTimerText == null) return;
            final long seconds = (System.currentTimeMillis() - mStartTime) / 1000;
            mTimerText.setText(String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
            mHandler.postDelayed(this, 1000);
        }
    };

    public VoiceInput(final LatinIME latinIme) {
        mLatinIme = latinIme;
    }

    /** Entry point for the mic key. */
    public void onVoiceKey() {
        if (mRecording || mProcessing) return;

        final SharedPreferences prefs = DeviceProtectedUtils.getSharedPreferences(mLatinIme);
        final String apiKey = prefs.getString(PREF_API_KEY, "");
        if (apiKey == null || apiKey.isEmpty()) {
            toast("Set your OpenAI API key in HeliBoard Settings → Advanced");
            return;
        }
        if (!PermissionsUtil.checkAllPermissionsGranted(mLatinIme, Manifest.permission.RECORD_AUDIO)) {
            toast("Allow microphone for HeliBoard in system settings");
            final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", mLatinIme.getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                mLatinIme.startActivity(intent);
            } catch (Exception ignored) {}
            return;
        }
        startRecording();
    }

    /** Cancels any active recording and hides the strip; safe to call anytime. */
    public void cancel() {
        mHandler.removeCallbacks(mTimerTick);
        releaseRecorder();
        mRecording = false;
        mProcessing = false;
        if (mAudioFile != null) {
            mAudioFile.delete();
            mAudioFile = null;
        }
        dismissPopup();
    }

    private void startRecording() {
        try {
            mAudioFile = new File(mLatinIme.getCacheDir(),
                    "voice_" + System.currentTimeMillis() + ".m4a");
            mRecorder = new MediaRecorder();
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mRecorder.setAudioEncodingBitRate(BIT_RATE);
            mRecorder.setAudioSamplingRate(SAMPLE_RATE);
            mRecorder.setAudioChannels(1);
            mRecorder.setOutputFile(mAudioFile.getAbsolutePath());
            mRecorder.prepare();
            mRecorder.start();
        } catch (Exception e) {
            releaseRecorder();
            toast("Could not start recording: " + e.getMessage());
            return;
        }
        mRecording = true;
        mStartTime = System.currentTimeMillis();
        showStrip(false);
        mHandler.post(mTimerTick);
    }

    private void finishRecording() {
        if (!mRecording) return;
        mHandler.removeCallbacks(mTimerTick);
        mRecording = false;
        try {
            mRecorder.stop();
            mRecorder.release();
            mRecorder = null;
        } catch (Exception e) {
            releaseRecorder();
            toast("Recording failed");
            cancel();
            return;
        }
        mProcessing = true;
        showStrip(true);
        transcribe();
    }

    private void transcribe() {
        final File audioFile = mAudioFile;
        final SharedPreferences prefs = DeviceProtectedUtils.getSharedPreferences(mLatinIme);
        final String apiKey = prefs.getString(PREF_API_KEY, "");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = null;
                String error = null;
                try {
                    final String boundary = "----HeliBoardVoice" + System.currentTimeMillis();
                    final String CRLF = "\r\n";
                    final HttpURLConnection conn =
                            (HttpURLConnection) new URL(API_URL).openConnection();
                    conn.setDoOutput(true);
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type",
                            "multipart/form-data; boundary=" + boundary);
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(120000);

                    final DataOutputStream out = new DataOutputStream(conn.getOutputStream());
                    out.writeBytes("--" + boundary + CRLF);
                    out.writeBytes("Content-Disposition: form-data; name=\"model\"" + CRLF + CRLF);
                    out.writeBytes(MODEL + CRLF);
                    out.writeBytes("--" + boundary + CRLF);
                    out.writeBytes("Content-Disposition: form-data; name=\"response_format\"" + CRLF + CRLF);
                    out.writeBytes("json" + CRLF);
                    out.writeBytes("--" + boundary + CRLF);
                    out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\""
                            + audioFile.getName() + "\"" + CRLF);
                    out.writeBytes("Content-Type: audio/mp4" + CRLF + CRLF);
                    final FileInputStream in = new FileInputStream(audioFile);
                    final byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    in.close();
                    out.writeBytes(CRLF + "--" + boundary + "--" + CRLF);
                    out.flush();
                    out.close();

                    final int code = conn.getResponseCode();
                    final InputStream responseStream =
                            code == HttpURLConnection.HTTP_OK ? conn.getInputStream() : conn.getErrorStream();
                    final StringBuilder response = new StringBuilder();
                    if (responseStream != null) {
                        final BufferedReader reader =
                                new BufferedReader(new InputStreamReader(responseStream));
                        String line;
                        while ((line = reader.readLine()) != null) response.append(line);
                        reader.close();
                    }
                    if (code == HttpURLConnection.HTTP_OK) {
                        text = extractTextFromJson(response.toString());
                        if (text == null || text.isEmpty()) error = "No transcription in response";
                    } else {
                        error = "HTTP " + code + ": " + response;
                    }
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                }

                final String finalText = text;
                final String finalError = error;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mProcessing = false;
                        audioFile.delete();
                        mAudioFile = null;
                        dismissPopup();
                        if (finalText != null && finalError == null) {
                            mLatinIme.onTextInput(finalText);
                        } else {
                            toast("Transcription failed: " + finalError);
                        }
                    }
                });
            }
        }).start();
    }

    // ---------- UI ----------

    private void showStrip(final boolean processing) {
        dismissPopup();
        final Context ctx = mLatinIme;

        final LinearLayout strip = new LinearLayout(ctx);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(12), dp(6), dp(8), dp(6));
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EE1F1F24"));
        bg.setCornerRadius(dp(22));
        strip.setBackground(bg);

        if (processing) {
            final android.widget.ProgressBar spinner = new android.widget.ProgressBar(ctx);
            final LinearLayout.LayoutParams spinParams =
                    new LinearLayout.LayoutParams(dp(22), dp(22));
            spinParams.setMargins(0, 0, dp(10), 0);
            spinner.setLayoutParams(spinParams);
            strip.addView(spinner);

            mStatusText = new TextView(ctx);
            mStatusText.setText("Transcribing…");
            mStatusText.setTextColor(Color.parseColor("#E9E9EC"));
            mStatusText.setTextSize(15);
            strip.addView(mStatusText);
        } else {
            final TextView dot = new TextView(ctx);
            dot.setText("●");
            dot.setTextColor(Color.parseColor("#F04438"));
            dot.setTextSize(14);
            dot.setPadding(0, 0, dp(8), 0);
            strip.addView(dot);

            mTimerText = new TextView(ctx);
            mTimerText.setText("00:00");
            mTimerText.setTextColor(Color.parseColor("#E9E9EC"));
            mTimerText.setTextSize(16);
            strip.addView(mTimerText);

            final View spacer = new View(ctx);
            final LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(dp(20), 1);
            spacer.setLayoutParams(spacerParams);
            strip.addView(spacer);

            strip.addView(makeStripButton("✕", "#3B3B42", new View.OnClickListener() {
                @Override
                public void onClick(View v) { cancel(); }
            }));
            strip.addView(makeStripButton("✓", "#4C8DF6", new View.OnClickListener() {
                @Override
                public void onClick(View v) { finishRecording(); }
            }));
        }

        final View anchor = KeyboardSwitcher.getInstance().getMainKeyboardView();
        if (anchor == null || anchor.getWindowToken() == null) return;
        mPopup = new PopupWindow(strip,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mPopup.setClippingEnabled(false);
        mPopup.showAtLocation(anchor, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(6));
    }

    private Button makeStripButton(final String label, final String color,
            final View.OnClickListener listener) {
        final Button b = new Button(mLatinIme);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setElevation(0);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(color));
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(42), dp(32));
        params.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(params);
        b.setOnClickListener(listener);
        return b;
    }

    private void dismissPopup() {
        if (mPopup != null) {
            try {
                mPopup.dismiss();
            } catch (Exception ignored) {}
            mPopup = null;
        }
        mTimerText = null;
        mStatusText = null;
    }

    private void releaseRecorder() {
        if (mRecorder != null) {
            try {
                mRecorder.release();
            } catch (Exception ignored) {}
            mRecorder = null;
        }
    }

    private static String extractTextFromJson(final String json) {
        final int textIndex = json.indexOf("\"text\"");
        if (textIndex == -1) return null;
        final int colonIndex = json.indexOf(":", textIndex);
        final int startQuote = json.indexOf("\"", colonIndex);
        if (startQuote == -1) return null;
        final StringBuilder text = new StringBuilder();
        for (int i = startQuote + 1; i < json.length(); i++) {
            final char c = json.charAt(i);
            if (c == '"') return text.toString();
            if (c == '\\' && i + 1 < json.length()) {
                final char next = json.charAt(++i);
                switch (next) {
                    case 'n': text.append('\n'); break;
                    case 't': text.append('\t'); break;
                    case 'r': text.append('\r'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            text.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                        break;
                    default: text.append(next); break;
                }
            } else {
                text.append(c);
            }
        }
        return null;
    }

    private void toast(final String message) {
        Toast.makeText(mLatinIme, message, Toast.LENGTH_LONG).show();
    }

    private int dp(final int value) {
        return (int) (value * mLatinIme.getResources().getDisplayMetrics().density);
    }
}
