package dev.sakus.vrcx.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.view.View;
import android.view.WindowInsets;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final int FILE_PICKER = 13;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff242930);
        getWindow().setNavigationBarColor(0xff16191e);
        webView = new WebView(this);
        webView.setBackgroundColor(0xff16191e);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowFileAccessFromFileURLs(false);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        webView.getSettings().setDomStorageEnabled(false);
        webView.addJavascriptInterface(new Bridge(), "VrcxAndroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme()) && uri.toString().startsWith("file:///android_asset/src/mobile/")) return false;
                if ("https".equals(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (ActivityNotFoundException ignored) { }
                }
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try { startActivityForResult(Intent.createChooser(intent, "Choose image"), FILE_PICKER); }
                catch (ActivityNotFoundException e) { fileCallback = null; callback.onReceiveValue(null); }
                return true;
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/src/mobile/index.html");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICKER && fileCallback != null) {
            fileCallback.onReceiveValue(resultCode == RESULT_OK && data != null && data.getData() != null
                ? new Uri[] { data.getData() } : null);
            fileCallback = null;
        }
    }

    @Override public void onBackPressed() {
        webView.evaluateJavascript("window.vrcxAndroidBack && window.vrcxAndroidBack()", null);
    }

    @Override protected void onDestroy() {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        worker.shutdownNow();
        webView.destroy();
        super.onDestroy();
    }

    private final class Bridge {
        private final NativeApi api = new NativeApi(MainActivity.this);

        @JavascriptInterface public void send(String json) {
            worker.execute(() -> {
                int id = 0;
                JSONObject result = new JSONObject();
                try {
                    JSONObject input = new JSONObject(json);
                    id = input.getInt("id");
                    String action = input.getString("action");
                    Object value;
                    switch (action) {
                        case "login": value = api.login(input.getString("username"), input.getString("password")); break;
                        case "request": value = api.request(input); break;
                        case "logout": api.clearSession(); value = true; break;
                        case "background": runOnUiThread(() -> moveTaskToBack(true)); value = true; break;
                        default: throw new IllegalArgumentException("Unsupported action");
                    }
                    result.put("value", value);
                } catch (Exception e) {
                    try { result.put("error", e.getMessage() == null ? "Native operation failed" : e.getMessage()); }
                    catch (Exception ignored) { result = new JSONObject(); }
                }
                final int responseId = id;
                final String response = result.toString();
                runOnUiThread(() -> webView.evaluateJavascript("window.vrcxAndroidResponse(" + responseId + "," + response + ")", null));
            });
        }
    }
}
