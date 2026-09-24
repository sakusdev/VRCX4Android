package org.sakus.vrcx4a;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Native platform services used by the Android port.
 *
 * The desktop Vue renderer talks to this class through an Electron/.NET-compatible
 * interop facade. This keeps the upstream VRCX UI and stores reusable instead of
 * rebuilding a separate mobile client.
 */
final class NativeApi {
    private static final String API = "https://api.vrchat.cloud/api/1/";
    private static final String KEY_ALIAS = "vrcx_android_session_v1";
    private static final String SESSION_HANDLE_PREFIX = "android-session:";
    private static final String SESSION_PREF_PREFIX = "session_snapshot_";
    private static final Object SESSION_LOCK = new Object();
    private static long sessionGeneration;
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;
    private static final Pattern SQL_PARAM = Pattern.compile("@[A-Za-z0-9_]+");

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final Context context;
    private final SharedPreferences prefs;
    private final SharedPreferences desktopPrefs;
    private final boolean readOnlySession;
    private volatile SQLiteDatabase database;
    private long instanceSessionGeneration;

    NativeApi(Context context) {
        this(context, false);
    }

    NativeApi(Context context, boolean readOnlySession) {
        this.context = context;
        this.readOnlySession = readOnlySession;
        synchronized (SESSION_LOCK) {
            instanceSessionGeneration = sessionGeneration;
        }
        prefs = context.getSharedPreferences("native_session", Context.MODE_PRIVATE);
        desktopPrefs = context.getSharedPreferences("vrcx_storage", Context.MODE_PRIVATE);
        // SQLite is opened lazily. A database/OS compatibility failure must not
        // crash the Activity before the upstream VRCX UI can even render.
        database = null;
        restoreCookies();
    }

    private SQLiteDatabase db() {
        SQLiteDatabase current = database;
        if (current != null && current.isOpen()) {
            return current;
        }
        synchronized (this) {
            current = database;
            if (current == null || !current.isOpen()) {
                current = context.openOrCreateDatabase("vrcx.db", Context.MODE_PRIVATE, null);
                current.enableWriteAheadLogging();
                // Android's execSQL rejects PRAGMAs that return a row.
                try (Cursor timeout = current.rawQuery("PRAGMA busy_timeout=5000", null)) {
                    timeout.moveToFirst();
                }
                database = current;
            }
            return current;
        }
    }

    JSONObject login(String username, String password) throws Exception {
        clearSession();
        String pair = username + ":" + password;
        String basic = Base64.encodeToString(pair.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        JSONObject response = execute(new URL(API + "auth/user"), "GET", null, null, null, "Basic " + basic, null);
        int status = response.getInt("status");
        JSONObject data = new JSONObject(response.optString("body", "{}"));
        if (status != 200 && !data.has("requiresTwoFactorAuth")) {
            throw new IllegalStateException(
                data.optJSONObject("error") != null
                    ? data.getJSONObject("error").optString("message", "Login failed")
                    : "Login failed: " + status
            );
        }
        return data;
    }

    JSONObject request(JSONObject input) throws Exception {
        String url = input.getString("url");
        URL parsed = validateApiUrl(url);
        String method = validateMethod(input.optString("method", "GET"));
        String body = input.optString("body", null);
        String image = input.optString("imageData", null);
        if (image != null) {
            if (!method.equals("POST") || !parsed.getPath().equals("/api/1/file/image")) {
                throw new SecurityException("Image upload must use the File API");
            }
            String tag = input.optString("tag", "");
            if (!tag.equals("gallery") && !tag.equals("avatarimage")) {
                throw new SecurityException("Invalid image tag");
            }
            return execute(parsed, method, null, image, tag, null, null);
        }
        return execute(parsed, method, body, null, null, null, null);
    }

    Object interop(JSONObject input) throws Exception {
        String className = input.getString("className");
        String methodName = input.getString("methodName");
        JSONArray args = input.optJSONArray("args");
        if (args == null) args = new JSONArray();

        switch (className) {
            case "WebApi":
                return webApiInterop(methodName, args);
            case "SQLite":
                return sqliteInterop(methodName, args);
            case "VRCXStorage":
                return storageInterop(methodName, args);
            case "AppApiElectron":
            case "AppApi":
                return appApiInterop(methodName, args);
            case "LogWatcher":
                return logWatcherInterop(methodName);
            case "AssetBundleManager":
                return assetBundleManagerInterop(methodName);
            case "Discord":
                return JSONObject.NULL;
            case "AppApiVrElectron":
            case "SystemMonitorElectron":
                return platformNoop(methodName);
            default:
                return platformNoop(methodName);
        }
    }

    private Object webApiInterop(String methodName, JSONArray args) throws Exception {
        switch (methodName) {
            case "ClearCookies":
                clearSession();
                return JSONObject.NULL;
            case "GetCookies":
                // Return only an opaque handle. Cookie contents never enter the renderer.
                return createSessionSnapshot();
            case "SetCookies":
                restoreSessionSnapshot(args.optString(0, ""));
                return JSONObject.NULL;
            case "ExecuteJson": {
                JSONObject options = new JSONObject(args.getString(0));
                JSONObject response = executeDesktopRequest(options);
                JSONObject result = new JSONObject();
                result.put("status", response.getInt("status"));
                result.put("message", response.optString("body", ""));
                return result.toString();
            }
            default:
                return JSONObject.NULL;
        }
    }

    private Object storageInterop(String methodName, JSONArray args) {
        switch (methodName) {
            case "Get":
                return desktopPrefs.getString(args.optString(0, ""), "");
            case "Set":
                desktopPrefs.edit()
                    .putString(args.optString(0, ""), args.optString(1, ""))
                    .apply();
                return JSONObject.NULL;
            case "Remove":
                desktopPrefs.edit().remove(args.optString(0, "")).apply();
                return JSONObject.NULL;
            case "Save":
            case "Init":
                return JSONObject.NULL;
            default:
                return JSONObject.NULL;
        }
    }

    private Object sqliteInterop(String methodName, JSONArray args) throws Exception {
        switch (methodName) {
            case "ExecuteJson":
                return executeSqlQuery(args.getString(0), args.optJSONObject(1)).toString();
            case "ExecuteNonQuery":
                executeSqlNonQuery(args.getString(0), args.optJSONObject(1));
                return 0;
            case "Init":
                return JSONObject.NULL;
            default:
                return JSONObject.NULL;
        }
    }

    private Object appApiInterop(String methodName, JSONArray args) throws Exception {
        switch (methodName) {
            case "SetUserAgent":
            case "CheckGameRunning":
            case "FocusWindow":
            case "FlashWindow":
            case "ShowDevTools":
            case "PopulateImageHosts":
            case "Save":
            case "IPCAnnounceStart":
            case "SetAppLauncherSettings":
            case "ExecuteVrOverlayFunction":
            case "SetVR":
            case "SetStartup":
            case "CropAllPrints":
            case "DeleteAllScreenshotMetadata":
            case "ChangeTheme":
            case "SendIpc":
            case "SetVRChatRegistryKey":
            case "VrcClosedGracefully":
                return JSONObject.NULL;

            case "CurrentCulture":
                return Locale.getDefault().toLanguageTag();

            case "GetVersion":
                try {
                    return context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0)
                        .versionName;
                } catch (Exception ignored) {
                    return "0.1.0";
                }

            case "GetColourBulk":
                // Linux returns an iterable key/value collection. [] is a valid
                // empty iterable for Object.fromEntries() in the desktop renderer.
                return new JSONArray();

            case "GetVRChatUserModeration":
                return 0;

            case "GetLaunchCommand":
            case "GetVRChatRegistryJson":
            case "GetVRChatPath":
            case "GetSteamPath":
            case "GetPicturesFolder":
            case "GetScreenshotFolder":
            case "OpenFolderSelectorDialog":
            case "OpenUGCPhotosFolder":
            case "CustomCss":
            case "CustomScript":
                return "";

            case "GetVRChatRegistryKeyString":
                // There is no local VRChat installation on Android.
                return JSONObject.NULL;

            case "HasVRChatRegistryFolder":
            case "IsGameRunning":
            case "IsSteamVRRunning":
            case "TryOpenInstanceInVrc":
            case "StartGameFromPath":
            case "StartGame":
            case "QuitGame":
                return false;

            case "OpenLink":
                return openExternalLink(args.optString(0, ""));

            case "ResizeImageToFitLimits":
                return args.optString(0, "");

            case "FileLength":
                return Base64.decode(args.optString(0, ""), Base64.DEFAULT).length;

            case "MD5File": {
                byte[] bytes = Base64.decode(args.optString(0, ""), Base64.DEFAULT);
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                return Base64.encodeToString(md5.digest(bytes), Base64.NO_WRAP);
            }

            default:
                return platformNoop(methodName);
        }
    }

    private Object logWatcherInterop(String methodName) {
        switch (methodName) {
            case "Get":
            case "GetLogLines":
                return new JSONArray();
            case "SetDateTill":
            case "Reset":
                return JSONObject.NULL;
            default:
                return platformNoop(methodName);
        }
    }

    private Object assetBundleManagerInterop(String methodName) {
        switch (methodName) {
            case "GetCacheSize":
                return 0L;
            case "SweepCache":
                return JSONObject.NULL;
            default:
                return platformNoop(methodName);
        }
    }

    private boolean openExternalLink(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                return false;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Object platformNoop(String methodName) {
        if (methodName.startsWith("Is") || methodName.startsWith("Has") || methodName.startsWith("Can")) {
            return false;
        }
        if (methodName.startsWith("Get")) {
            return "";
        }
        return JSONObject.NULL;
    }

    private JSONObject executeDesktopRequest(JSONObject options) throws Exception {
        URL url = new URL(options.getString("url"));
        String method = validateMethod(options.optString("method", "GET"));

        boolean apiHost = "https".equals(url.getProtocol())
            && "api.vrchat.cloud".equals(url.getHost())
            && url.getPort() == -1
            && url.getPath().startsWith("/api/1/");

        boolean statusHost = "https".equals(url.getProtocol())
            && "status.vrchat.com".equals(url.getHost())
            && url.getPort() == -1
            && (url.getPath().equals("/api/v2/status.json")
                || url.getPath().equals("/api/v2/summary.json"))
            && url.getQuery() == null
            && method.equals("GET");

        if (!apiHost && !statusHost) {
            throw new SecurityException("Desktop renderer request outside VRChat API");
        }

        JSONObject headers = options.optJSONObject("headers");
        String body = options.optString("body", null);

        if (options.optBoolean("uploadImage", false)) {
            String imageData = options.optString("imageData", "");
            JSONObject postData = parseJsonObject(options.optString("postData", "{}"));
            return executeMultipart(url, postData, "file", "blob", imageData, headers);
        }

        if (options.optBoolean("uploadImageLegacy", false)) {
            String imageData = options.optString("imageData", "");
            JSONObject fields = new JSONObject();
            if (options.has("postData")) fields.put("data", options.optString("postData", ""));
            return executeMultipart(url, fields, "image", "image.png", imageData, headers);
        }

        try {
            return execute(url, method, body, null, null, null, headers);
        } catch (IOException error) {
            if (!statusHost) throw error;
            // Statuspage is optional; keep the login view usable offline.
            JSONObject unavailable = new JSONObject();
            unavailable.put("status", 503);
            unavailable.put("body", "");
            return unavailable;
        }
    }

    private JSONObject executeMultipart(
        URL url,
        JSONObject fields,
        String fileField,
        String fileName,
        String base64,
        JSONObject headers
    ) throws Exception {
        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
        if (bytes.length > 20_000_000) throw new IllegalArgumentException("Image too large");

        HttpURLConnection connection = openConnection(url, "POST", headers, null);
        String boundary = "VRCX" + UUID.randomUUID().toString().replace("-", "");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setDoOutput(true);

        try (OutputStream out = connection.getOutputStream()) {
            Iterator<String> keys = fields.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                write(
                    out,
                    "--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"" + key + "\"\r\n\r\n"
                        + fields.optString(key, "") + "\r\n"
                );
            }
            write(
                out,
                "--" + boundary
                    + "\r\nContent-Disposition: form-data; name=\"" + fileField
                    + "\"; filename=\"" + fileName
                    + "\"\r\nContent-Type: image/png\r\n\r\n"
            );
            out.write(bytes);
            write(out, "\r\n--" + boundary + "--\r\n");
        }

        return readResponse(connection, url.toURI());
    }

    private static JSONObject parseJsonObject(String value) {
        try {
            return new JSONObject(value);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private JSONArray executeSqlQuery(String sql, JSONObject params) throws Exception {
        BoundSql bound = bindSql(sql, params);
        JSONArray rows = new JSONArray();

        try (Cursor cursor = db().rawQuery(bound.sql, bound.stringArgs())) {
            while (cursor.moveToNext()) {
                JSONArray row = new JSONArray();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    switch (cursor.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL:
                            row.put(JSONObject.NULL);
                            break;
                        case Cursor.FIELD_TYPE_INTEGER:
                            row.put(cursor.getLong(i));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            row.put(cursor.getDouble(i));
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                            row.put(Base64.encodeToString(cursor.getBlob(i), Base64.NO_WRAP));
                            break;
                        default:
                            row.put(cursor.getString(i));
                            break;
                    }
                }
                rows.put(row);
            }
        }
        return rows;
    }

    private void executeSqlNonQuery(String sql, JSONObject params) {
        BoundSql bound = bindSql(sql, params);
        db().execSQL(bound.sql, bound.args.toArray());
    }

    private static BoundSql bindSql(String sql, JSONObject params) {
        // sqlite_schema is an alias added by newer SQLite releases. Android 8/9
        // can ship an older SQLite, while sqlite_master works on both old and new.
        sql = sql.replace("sqlite_schema", "sqlite_master");

        if (params == null || params.length() == 0) return new BoundSql(sql, new ArrayList<>());

        Matcher matcher = SQL_PARAM.matcher(sql);
        StringBuffer rewritten = new StringBuffer();
        List<Object> values = new ArrayList<>();

        while (matcher.find()) {
            String key = matcher.group();
            Object value = params.opt(key);
            if (value == JSONObject.NULL) value = null;
            if (value instanceof Boolean) value = ((Boolean) value) ? 1 : 0;
            values.add(value);
            matcher.appendReplacement(rewritten, "?");
        }
        matcher.appendTail(rewritten);
        return new BoundSql(rewritten.toString(), values);
    }

    private static final class BoundSql {
        final String sql;
        final List<Object> args;

        BoundSql(String sql, List<Object> args) {
            this.sql = sql;
            this.args = args;
        }

        String[] stringArgs() {
            String[] result = new String[args.size()];
            for (int i = 0; i < args.size(); i++) {
                Object value = args.get(i);
                result[i] = value == null ? null : String.valueOf(value);
            }
            return result;
        }
    }

    void clearSession() {
        cookies.getCookieStore().removeAll();
        synchronized (SESSION_LOCK) {
            instanceSessionGeneration = ++sessionGeneration;
            prefs.edit().remove("cookies").commit();
        }
        context.stopService(new Intent(context, BackgroundNotificationsService.class));
    }

    String backgroundAuthToken() throws Exception {
        JSONObject response = execute(new URL(API + "auth"), "GET", null, null, null, null, null);
        int status = response.getInt("status");
        if (status == 401 || status == 403) {
            throw new IllegalStateException("Background VRChat session expired");
        }
        if (status != 200) throw new IOException("VRChat auth temporarily unavailable: " + status);
        String token = new JSONObject(response.getString("body")).optString("token", "");
        if (token.isEmpty()) throw new IllegalStateException("VRChat pipeline token unavailable");
        return token;
    }

    private URL validateApiUrl(String url) throws Exception {
        URL parsed = new URL(url);
        if (
            !"https".equals(parsed.getProtocol())
                || !"api.vrchat.cloud".equals(parsed.getHost())
                || parsed.getPort() != -1
                || !parsed.getPath().startsWith("/api/1/")
        ) {
            throw new SecurityException("URL outside VRChat API");
        }
        return parsed;
    }

    private static String validateMethod(String method) {
        if (
            !method.equals("GET")
                && !method.equals("POST")
                && !method.equals("PUT")
                && !method.equals("DELETE")
        ) {
            throw new SecurityException("Unsupported HTTP method");
        }
        return method;
    }

    private JSONObject execute(
        URL url,
        String method,
        String body,
        String image,
        String tag,
        String auth,
        JSONObject headers
    ) throws Exception {
        HttpURLConnection connection = openConnection(url, method, headers, auth);

        if (image != null) {
            byte[] bytes = Base64.decode(image, Base64.DEFAULT);
            if (bytes.length > 20_000_000) throw new IllegalArgumentException("Image too large");
            String boundary = "VRCX" + UUID.randomUUID().toString().replace("-", "");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                write(
                    out,
                    "--" + boundary + "\r\nContent-Disposition: form-data; name=\"tag\"\r\n\r\n"
                        + tag + "\r\n"
                );
                write(
                    out,
                    "--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"blob\""
                        + "\r\nContent-Type: image/png\r\n\r\n"
                );
                out.write(bytes);
                write(out, "\r\n--" + boundary + "--\r\n");
            }
        } else if (body != null && !method.equals("GET")) {
            if (headers == null || !headers.has("Content-Type")) {
                connection.setRequestProperty("Content-Type", "application/json;charset=utf-8");
            }
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        return readResponse(connection, url.toURI());
    }

    private HttpURLConnection openConnection(URL url, String method, JSONObject headers, String auth) throws Exception {
        URI uri = url.toURI();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent", "VRCX-Android/0.2 (unofficial)");
        connection.setRequestProperty("Accept", "application/json");

        if (auth != null) connection.setRequestProperty("Authorization", auth);

        if (headers != null) {
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (
                    key.equalsIgnoreCase("Cookie")
                        || key.equalsIgnoreCase("Host")
                        || key.equalsIgnoreCase("Content-Length")
                ) continue;
                connection.setRequestProperty(key, headers.optString(key, ""));
            }
        }

        Map<String, List<String>> cookieHeaders = cookies.get(uri, Collections.emptyMap());
        for (String cookie : cookieHeaders.getOrDefault("Cookie", Collections.emptyList())) {
            connection.addRequestProperty("Cookie", cookie);
        }
        return connection;
    }

    private JSONObject readResponse(HttpURLConnection connection, URI uri) throws Exception {
        try {
            int status = connection.getResponseCode();
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() != null && header.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for (String value : header.getValue()) {
                        for (HttpCookie cookie : HttpCookie.parse(value)) {
                            cookies.getCookieStore().add(uri, cookie);
                        }
                    }
                }
            }
            saveCookies();

            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (stream != null) {
                try (stream) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        if (output.size() + read > MAX_RESPONSE) {
                            throw new IllegalStateException("Response too large");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }

            JSONObject result = new JSONObject();
            result.put("status", status);
            result.put("body", output.toString(StandardCharsets.UTF_8.name()));
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static void write(OutputStream stream, String value) throws Exception {
        stream.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        );
        generator.init(
            new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        );
        return generator.generateKey();
    }

    private JSONArray serializeCookies() throws Exception {
        JSONArray values = new JSONArray();
        for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
            if (cookie.hasExpired()) continue;
            JSONObject entry = new JSONObject();
            entry.put("name", cookie.getName());
            entry.put("value", cookie.getValue());
            entry.put("domain", cookie.getDomain());
            entry.put("path", cookie.getPath());
            entry.put("secure", cookie.getSecure());
            entry.put("httpOnly", cookie.isHttpOnly());
            entry.put(
                "expires",
                cookie.getMaxAge() < 0
                    ? -1
                    : System.currentTimeMillis() + cookie.getMaxAge() * 1000
            );
            values.put(entry);
        }
        return values;
    }

    private String encryptCookieArray(JSONArray values) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(values.toString().getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[cipher.getIV().length + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, combined, 0, cipher.getIV().length);
        System.arraycopy(encrypted, 0, combined, cipher.getIV().length, encrypted.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private JSONArray decryptCookieArray(String encoded) throws Exception {
        byte[] combined = Base64.decode(encoded, Base64.DEFAULT);
        if (combined.length < 13) {
            throw new IllegalArgumentException("Invalid encrypted session");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            new GCMParameterSpec(128, combined, 0, 12)
        );
        String json = new String(
            cipher.doFinal(combined, 12, combined.length - 12),
            StandardCharsets.UTF_8
        );
        return new JSONArray(json);
    }

    private void installCookies(JSONArray values) throws Exception {
        cookies.getCookieStore().removeAll();
        URI origin = new URI(API);
        for (int i = 0; i < values.length(); i++) {
            JSONObject entry = values.getJSONObject(i);
            long expires = entry.getLong("expires");
            if (expires != -1 && expires <= System.currentTimeMillis()) continue;

            HttpCookie cookie = new HttpCookie(
                entry.getString("name"),
                entry.getString("value")
            );
            cookie.setDomain(entry.optString("domain", "api.vrchat.cloud"));
            cookie.setPath(entry.optString("path", "/"));
            cookie.setSecure(entry.optBoolean("secure", true));
            cookie.setHttpOnly(entry.optBoolean("httpOnly", true));
            if (expires != -1) {
                cookie.setMaxAge(Math.max(0, (expires - System.currentTimeMillis()) / 1000));
            }
            cookies.getCookieStore().add(origin, cookie);
        }
    }

    private String createSessionSnapshot() throws Exception {
        String id = UUID.randomUUID().toString();
        String encoded = encryptCookieArray(serializeCookies());
        if (!prefs.edit().putString(SESSION_PREF_PREFIX + id, encoded).commit()) {
            throw new IllegalStateException("Could not persist session snapshot");
        }
        return SESSION_HANDLE_PREFIX + id;
    }

    private void restoreSessionSnapshot(String handle) throws Exception {
        if (handle == null || !handle.startsWith(SESSION_HANDLE_PREFIX)) {
            return;
        }
        String id = handle.substring(SESSION_HANDLE_PREFIX.length());
        if (id.isEmpty() || !id.matches("[0-9a-fA-F-]{36}")) {
            return;
        }
        String encoded = prefs.getString(SESSION_PREF_PREFIX + id, null);
        if (encoded == null) {
            return;
        }
        installCookies(decryptCookieArray(encoded));
        saveCookies();
    }

    private void saveCookies() throws Exception {
        // The notification service reads the latest encrypted session each time
        // it reconnects. Never let its older cookie snapshot overwrite the UI's.
        if (readOnlySession) return;
        String encoded = encryptCookieArray(serializeCookies());
        synchronized (SESSION_LOCK) {
            if (instanceSessionGeneration != sessionGeneration) return;
            if (!prefs.edit().putString("cookies", encoded).commit()) {
                throw new IllegalStateException("Could not persist session");
            }
        }
    }

    private void restoreCookies() {
        String encoded = prefs.getString("cookies", null);
        if (encoded == null) return;

        try {
            installCookies(decryptCookieArray(encoded));
        } catch (Exception ignored) {
            clearSession();
        }
    }
}
