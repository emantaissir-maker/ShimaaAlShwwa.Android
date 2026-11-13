package com.asad.monthlyplan;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity implements RecognitionListener {
    private static final int REQ_AUDIO = 81;
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip";
    private static final String MODEL_DIR_NAME = "vosk-model-ar-mgb2-0.4";
    private WebView webView;
    private Model model;
    private SpeechService speechService;
    private int activeIndex = -1;
    private String activeField = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean modelLoading = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidVoice");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            prepareModelIfPresent();
        }
    }

    private File modelDirectory() { return new File(getFilesDir(), MODEL_DIR_NAME); }
    private void prepareModelIfPresent() {
        if (model != null || modelLoading) return;
        File dir = modelDirectory();
        if (new File(dir, "am/final.mdl").exists()) {
            new Thread(() -> {
                try { model = new Model(dir.getAbsolutePath()); }
                catch (Exception e) { notifyUser("تعذر فتح نموذج اللغة. أعد تنزيله من داخل التطبيق."); }
            }).start();
        }
    }

    private void downloadAndLoadModel() {
        if (model != null) { startSpeech(); return; }
        if (modelLoading) { notifyUser("يجري تجهيز نموذج اللغة العربية، انتظر قليلاً."); return; }
        modelLoading = true;
        notifyUser("سيتم تنزيل نموذج اللغة العربية لأول مرة (حوالي 318 ميغابايت). يلزم الإنترنت ومساحة تخزين كافية. بعد ذلك يعمل الإملاء دون إنترنت.");
        new Thread(() -> {
            File zip = new File(getCacheDir(), "arabic-vosk.zip");
            File temp = new File(getFilesDir(), "vosk-unpack");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(MODEL_URL).openConnection();
                c.setConnectTimeout(20000); c.setReadTimeout(60000); c.connect();
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                try (InputStream in = new BufferedInputStream(c.getInputStream()); FileOutputStream out = new FileOutputStream(zip)) {
                    byte[] b = new byte[64 * 1024]; int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                } finally { c.disconnect(); }
                if (temp.exists()) deleteTree(temp);
                temp.mkdirs();
                try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        File outFile = new File(temp, entry.getName());
                        if (!outFile.getCanonicalPath().startsWith(temp.getCanonicalPath() + File.separator)) throw new Exception("Invalid archive path");
                        if (entry.isDirectory()) outFile.mkdirs();
                        else {
                            File parent = outFile.getParentFile(); if (parent != null) parent.mkdirs();
                            try (FileOutputStream out = new FileOutputStream(outFile)) {
                                byte[] b = new byte[64 * 1024]; int n; while ((n = zis.read(b)) != -1) out.write(b, 0, n);
                            }
                        }
                        zis.closeEntry();
                    }
                }
                File extracted = new File(temp, MODEL_DIR_NAME);
                if (!extracted.exists()) throw new Exception("Model folder missing");
                File finalDir = modelDirectory(); deleteTree(finalDir);
                if (!extracted.renameTo(finalDir)) throw new Exception("Could not install model");
                zip.delete(); deleteTree(temp);
                model = new Model(finalDir.getAbsolutePath());
                main.post(() -> { modelLoading = false; notifyUser("تم تجهيز اللغة العربية. يمكنك الآن الإملاء دون إنترنت."); startSpeech(); });
            } catch (Exception e) {
                modelLoading = false;
                main.post(() -> notifyUser("لم يكتمل تنزيل نموذج اللغة. تحقق من الإنترنت والمساحة ثم اضغط الميكروفون مجدداً."));
            }
        }).start();
    }

    private void startSpeech() {
        if (model == null) { downloadAndLoadModel(); return; }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO); return;
        }
        try {
            stopSpeech();
            Recognizer recognizer = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            speechService.startListening(this);
            notifyUser("يستمع الآن... اضغط زر الميكروفون مرة أخرى لإيقاف الإملاء.");
        } catch (Exception e) { notifyUser("تعذر بدء الإملاء. حاول مرة أخرى."); }
    }

    private void stopSpeech() {
        if (speechService != null) { try { speechService.stop(); speechService.shutdown(); } catch (Exception ignored) {} speechService = null; }
    }

    private void notifyUser(String message) {
        main.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override public void onResult(String hypothesis) { deliverText(hypothesis); }
    @Override public void onFinalResult(String hypothesis) { deliverText(hypothesis); stopSpeech(); }
    @Override public void onPartialResult(String hypothesis) { /* لا نكتب النص الجزئي حتى لا تتكرر الكلمات */ }
    @Override public void onError(Exception e) { stopSpeech(); notifyUser("حدث خطأ في الإملاء الصوتي. حاول مجدداً."); }
    @Override public void onTimeout() { stopSpeech(); }

    private void deliverText(String json) {
        try {
            String text = new JSONObject(json).optString("text", "").trim();
            if (text.isEmpty() || activeIndex < 0) return;
            String js = "window.onNativeVoiceResult && window.onNativeVoiceResult(" + activeIndex + "," + JSONObject.quote(activeField) + "," + JSONObject.quote(text) + ",true);";
            main.post(() -> webView.evaluateJavascript(js, null));
        } catch (Exception ignored) {}
    }

    private class AndroidBridge {
        @JavascriptInterface public void startRecognition(int index, String field) {
            if (speechService != null) { stopSpeech(); notifyUser("تم إيقاف الإملاء."); return; }
            activeIndex = index; activeField = field;
            if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                main.post(() -> ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO));
                return;
            }
            startSpeech();
        }
        @JavascriptInterface public boolean isModelReady() { return model != null; }
        @JavascriptInterface public void printPdf() {
            main.post(() -> {
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                if (pm != null) pm.print("الخطة الشهرية للأنشطة التربوية", webView.createPrintDocumentAdapter("الخطة الشهرية"), new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A3).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build());
            });
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_AUDIO && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startSpeech();
        else if (requestCode == REQ_AUDIO) notifyUser("يجب السماح باستخدام الميكروفون للإملاء الصوتي.");
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles(); if (children != null) for (File child : children) deleteTree(child);
        f.delete();
    }

    @Override public void onDestroy() { stopSpeech(); if (model != null) { model.close(); model = null; } if (webView != null) webView.destroy(); super.onDestroy(); }
}
