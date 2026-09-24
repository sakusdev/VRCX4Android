package org.sakus.vrcx4a;

import android.app.Activity;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.content.pm.PackageManager;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "VRCXAndroid";
    private static final String WEB_HOST = "appassets.androidplatform.net";
    private static final String WEB_ENTRY = "https://" + WEB_HOST + "/assets/src/mobile/index.html";
    private static final int FILE_PICKER = 13;
    private static final int NOTIFICATION_PERMISSION = 14;

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private volatile String lastJsError = "";
    // Desktop VRCX performs API and database work concurrently. Keep a small
    // bounded pool so Android doesn't serialize every request through one thread.
    private final ExecutorService worker = Executors.newFixedThreadPool(4);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            initializeWebView();
        } catch (Throwable error) {
            Log.e(TAG, "Fatal startup error", error);
            showStartupError(error);
        }
    }

    private void initializeWebView() {
        getWindow().setStatusBarColor(0xff242930);
        getWindow().setNavigationBarColor(0xff16191e);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        webView = new WebView(this);
        webView.setBackgroundColor(0xff16191e);
        FrameLayout container = new FrameLayout(this);
        ViewCompat.setOnApplyWindowInsetsListener(container, (view, insets) -> {
            Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        // Required for user-selected content:// URIs returned by Android's picker.
        // Arbitrary external navigation is still blocked by WebViewClient.
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.addJavascriptInterface(new Bridge(), "VrcxAndroid");
        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && WEB_HOST.equals(uri.getHost())) return false;
                if ("https".equals(uri.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (ActivityNotFoundException ignored) {
                    }
                }
                return true;
            }

            @Override public void onReceivedError(
                WebView view,
                WebResourceRequest request,
                WebResourceErrorCompat error
            ) {
                if (request.isForMainFrame()) {
                    String detail = "WebView load failed: " + error.getDescription();
                    Log.e(TAG, detail);
                    showStartupError(new IllegalStateException(detail));
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                view.postDelayed(
                    () ->
                        view.evaluateJavascript(
                            "(function(){if(document.documentElement.dataset.vrcxMounted!=='true')return 'empty';"
                                + "var form=document.getElementById('login-form');"
                                + "var username=document.getElementById('login-form-username');"
                                + "var password=document.getElementById('login-form-password');"
                                + "var dialog=document.querySelector('[role=alertdialog],[role=dialog][data-state=open]');"
                                + "return location.hash.startsWith('#/login')&&form&&username&&password"
                                + "&&form.getBoundingClientRect().width>0&&!dialog?'login':'mounted';})()",
                            value -> {
                                if ("\"login\"".equals(value)) {
                                    Log.i(TAG, "VRCX_ANDROID_LOGIN_VISIBLE");
                                } else if ("\"mounted\"".equals(value)) {
                                    Log.e(TAG, "VRCX_ANDROID_LOGIN_FAILED: login form not visible or a dialog is open");
                                } else {
                                    String detail = "Vue renderer did not mount; root state=" + value;
                                    if (!lastJsError.isEmpty()) {
                                        detail += "\nLast JS error: " + lastJsError;
                                    }
                                    Log.e(TAG, detail);
                                    showStartupError(new IllegalStateException(detail));
                                }
                            }
                        ),
                    15000
                );
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                String detail =
                    "JS " + message.messageLevel() + ": " + message.message()
                        + " (" + message.sourceId() + ":" + message.lineNumber() + ")";
                Log.d(TAG, detail);
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    lastJsError = detail;
                }
                return true;
            }

            @Override public boolean onShowFileChooser(
                WebView view,
                ValueCallback<Uri[]> callback,
                FileChooserParams params
            ) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                String[] acceptTypes = params.getAcceptTypes();
                String primaryType = "*/*";
                if (acceptTypes != null && acceptTypes.length == 1 && !acceptTypes[0].isEmpty()) {
                    primaryType = acceptTypes[0];
                }
                intent.setType(primaryType);

                if (acceptTypes != null && acceptTypes.length > 1) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes);
                }
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }

                try {
                    startActivityForResult(Intent.createChooser(intent, "Choose file"), FILE_PICKER);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }
        });
        container.addView(webView);
        setContentView(container);
        webView.loadUrl(WEB_ENTRY);
    }

    private void showStartupError(Throwable error) {
        TextView message = new TextView(this);
        message.setTextColor(0xffeeeeee);
        message.setBackgroundColor(0xff16191e);
        message.setPadding(32, 48, 32, 32);
        message.setTextSize(16f);
        String detail = error.getClass().getSimpleName();
        if (error.getMessage() != null && !error.getMessage().isEmpty()) {
            detail += ": " + error.getMessage();
        }
        message.setText(
            "VRCX4Android failed to start.\n\n" + detail
                + "\n\nIf reporting this, include logcat tag " + TAG + "."
        );
        setContentView(message);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICKER && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clipData = data.getClipData();
                if (clipData != null && clipData.getItemCount() > 0) {
                    result = new Uri[clipData.getItemCount()];
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        result[i] = clipData.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    result = new Uri[] { data.getData() };
                }
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("window.vrcxAndroidBack && window.vrcxAndroidBack()", null);
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        BackgroundNotificationsService.setActivityVisible(true);
        if (webView != null) {
            webView.evaluateJavascript("window.vrcxAndroidResume && window.vrcxAndroidResume()", null);
        }
    }

    @Override protected void onStop() {
        if (webView != null) {
            webView.evaluateJavascript("window.vrcxAndroidHidden && window.vrcxAndroidHidden()", null);
        }
        BackgroundNotificationsService.setActivityVisible(false);
        super.onStop();
    }

    private void enableBackgroundAlerts() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, NOTIFICATION_PERMISSION);
            return;
        }
        try {
            startForegroundService(new Intent(this, BackgroundNotificationsService.class));
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot start background notifications", error);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NOTIFICATION_PERMISSION && results.length > 0
            && results[0] == PackageManager.PERMISSION_GRANTED) {
            enableBackgroundAlerts();
        }
    }

    @Override protected void onDestroy() {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        worker.shutdownNow();
        if (webView != null) {
            webView.destroy();
        }
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
                        case "login":
                            value = api.login(input.getString("username"), input.getString("password"));
                            break;
                        case "request":
                            value = api.request(input);
                            break;
                        case "interop":
                            value = api.interop(input);
                            break;
                        case "logout":
                            api.clearSession();
                            value = true;
                            break;
                        case "background":
                            runOnUiThread(() -> moveTaskToBack(true));
                            value = true;
                            break;
                        case "backgroundAlerts":
                            runOnUiThread(MainActivity.this::enableBackgroundAlerts);
                            value = true;
                            break;
                        default:
                            throw new IllegalArgumentException("Unsupported action");
                    }
                    result.put("value", value);
                } catch (Exception e) {
                    try {
                        result.put(
                            "error",
                            e.getMessage() == null ? "Native operation failed" : e.getMessage()
                        );
                    } catch (Exception ignored) {
                        result = new JSONObject();
                    }
                }
                final int responseId = id;
                final String response = result.toString();
                runOnUiThread(
                    () ->
                        webView.evaluateJavascript(
                            "window.vrcxAndroidResponse(" + responseId + "," + response + ")",
                            null
                        )
                );
            });
        }
    }
}
