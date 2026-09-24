package org.sakus.vrcx4a;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Receives VRChat pipeline notifications while the upstream WebView is hidden. */
public final class BackgroundNotificationsService extends Service {
    private static final String TAG = "VRCXAndroid";
    private static final String SERVICE_CHANNEL = "vrcx-background-status";
    private static final String EVENTS_CHANNEL = "vrcx-background-events";
    private static final int SERVICE_NOTIFICATION_ID = 1;
    private static volatile boolean activityVisible = true;
    private static WeakReference<BackgroundNotificationsService> current = new WeakReference<>(null);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final LinkedHashSet<String> seenEvents = new LinkedHashSet<>();
    private OkHttpClient client;
    private WebSocket socket;
    private int generation;
    private int retrySeconds = 5;
    private int nextNotificationId = 100;
    private volatile boolean stopped;

    private void enqueue(Runnable work) {
        if (stopped) return;
        try {
            executor.execute(work);
        } catch (RejectedExecutionException ignored) {
            // Service was stopped while an OkHttp callback was in flight.
        }
    }

    static void setActivityVisible(boolean visible) {
        activityVisible = visible;
        BackgroundNotificationsService service = current.get();
        if (service != null) {
            service.enqueue(() -> {
                if (visible) service.disconnect();
                else service.connect();
            });
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        current = new WeakReference<>(this);
        client = new OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
            SERVICE_CHANNEL, "VRCX background connection", NotificationManager.IMPORTANCE_LOW
        ));
        manager.createNotificationChannel(new NotificationChannel(
            EVENTS_CHANNEL, "VRChat events", NotificationManager.IMPORTANCE_DEFAULT
        ));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification status = notification(SERVICE_CHANNEL, "VRCX4Android", "Background alerts are active", true);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(SERVICE_NOTIFICATION_ID, status, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, status);
        }
        enqueue(() -> {
            if (!activityVisible) connect();
        });
        // A fresh renderer login is required after process death; no token is stored in plain text.
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification(String channel, String title, String message, boolean ongoing) {
        Intent launch = new Intent(this, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(!ongoing)
            .build();
    }

    // Only the native service owns a pipeline socket while the upstream renderer is hidden.
    private void connect() {
        if (stopped || activityVisible || socket != null) return;
        final int connectionGeneration = ++generation;
        try {
            // NativeApi restores the encrypted Android Keystore cookie jar. Refresh the
            // short-lived pipeline token on every connection rather than persisting it.
            String token = new NativeApi(getApplicationContext(), true).backgroundAuthToken();
            if (stopped || activityVisible || generation != connectionGeneration) return;
            Request request = new Request.Builder()
                .url("wss://pipeline.vrchat.cloud/?auth=" + URLEncoder.encode(token, StandardCharsets.UTF_8.name()))
                .header("User-Agent", "VRCX-Android/0.2 (unofficial)")
                .build();
            socket = client.newWebSocket(request, new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    enqueue(() -> {
                        if (connectionGeneration == generation) {
                            retrySeconds = 5;
                            Log.i(TAG, "VRCX_ANDROID_BACKGROUND_CONNECTED");
                        }
                    });
                }

                @Override public void onMessage(WebSocket webSocket, String text) {
                    enqueue(() -> {
                        if (!activityVisible && connectionGeneration == generation) showEvent(text);
                    });
                }

                @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                    enqueue(() -> reconnect(connectionGeneration));
                }

                @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                    if (!stopped) {
                        Log.w(TAG, "Background pipeline connection lost", error);
                        enqueue(() -> reconnect(connectionGeneration));
                    }
                }
            });
        } catch (IOException error) {
            Log.w(TAG, "Background pipeline offline; retrying");
            reconnect(connectionGeneration);
        } catch (Exception error) {
            Log.w(TAG, "Background pipeline authentication failed: " + error.getClass().getSimpleName());
            // An invalid login cannot receive events; stop the visible service rather
            // than claiming notifications are active indefinitely.
            stopSelf();
        }
    }

    private void reconnect(int connectionGeneration) {
        if (stopped || activityVisible || connectionGeneration != generation) return;
        socket = null;
        int delay = retrySeconds;
        retrySeconds = Math.min(60, retrySeconds * 2);
        executor.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    private void disconnect() {
        generation++;
        if (socket != null) {
            WebSocket old = socket;
            socket = null;
            old.close(1000, "VRCX renderer is visible");
        }
    }

    private void showEvent(String text) {
        try {
            JSONObject event = new JSONObject(text);
            String type = event.optString("type");
            if (!type.equals("notification") && !type.equals("notification-v2")) return;
            Object rawContent = event.opt("content");
            JSONObject content = rawContent instanceof JSONObject
                ? (JSONObject) rawContent
                : new JSONObject(String.valueOf(rawContent));

            String id = content.optString("id", "");
            if (!id.isEmpty() && !seenEvents.add(id)) return;
            if (seenEvents.size() > 128) seenEvents.remove(seenEvents.iterator().next());

            String sender = content.optString("title", "");
            if (sender.isEmpty()) sender = content.optString("senderUsername", "");
            if (sender.isEmpty()) sender = content.optString("senderDisplayName", "");
            String title = sender.isEmpty() ? "VRChat notification" : sender;
            String body = content.optString("message", "");
            if (body.isEmpty()) body = content.optString("type", "New VRChat event");
            if (body.length() > 160) body = body.substring(0, 160);
            getSystemService(NotificationManager.class).notify(
                nextNotificationId++, notification(EVENTS_CHANNEL, title, body, false)
            );
            Log.i(TAG, "VRCX_ANDROID_BACKGROUND_NOTIFICATION");
        } catch (Exception error) {
            Log.w(TAG, "Unrecognized background pipeline event", error);
        }
    }

    @Override public void onDestroy() {
        stopped = true;
        if (current.get() == this) current.clear();
        executor.execute(this::disconnect);
        executor.shutdown();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        super.onDestroy();
    }
}
