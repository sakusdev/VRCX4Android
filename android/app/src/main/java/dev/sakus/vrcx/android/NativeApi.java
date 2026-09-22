package dev.sakus.vrcx.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;
    private static final Pattern SQL_PARAM = Pattern.compile("@[A-Za-z0-9_]+");

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final SharedPreferences prefs;
    private final SharedPreferences desktopPrefs;
    private final SQLiteDatabase database;

    NativeApi(Context context) {
        prefs = context.getSharedPreferences("native_session", Context.MODE_PRIVATE);
        desktopPrefs = context.getSharedPreferences("vrcx_storage", Context.MODE_PRIVATE);
        database = context.openOrCreateDatabase("vrcx.db", Context.MODE_PRIVATE, null);
        database.enableWriteAheadLogging();
        database.execSQL("PRAGMA busy_timeout=5000");
        restoreCookies();
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
            if (!tag.equals("worldimage") && !tag.equals("avatarimage")) {
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
            case "Discord":
            case "AssetBundleManager":
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
                // Cookies intentionally remain native-only on Android.
                return "";
            case "SetCookies":
                // The persisted native session is already restored at startup.
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
                return JSONObject.NULL;
            case "GetLaunchCommand":
            case "GetVRChatRegistryJson":
            case "GetVRChatPath":
            case "GetSteamPath":
            case "GetPicturesFolder":
            case "GetScreenshotFolder":
                return "";
            case "HasVRChatRegistryFolder":
            case "IsGameRunning":
                return false;
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

        if (!apiHost) {
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

        return execute(url, method, body, null, null, null, headers);
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

    private JSONArray executeSqlQuery(String sql, JSONObject params) {
        BoundSql bound = bindSql(sql, params);
        JSONArray rows = new JSONArray();

        try (Cursor cursor = database.rawQuery(bound.sql, bound.stringArgs())) {
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
        database.execSQL(bound.sql, bound.args.toArray());
    }

    private static BoundSql bindSql(String sql, JSONObject params) {
        if (params == null || params.length() == 0) return new BoundSql(sql, new ArrayList<>());

        Matcher matcher = SQL_PARAM.matcher(sql);
        StringBuffer rewritten = new StringBuffer();
        List<Object> values = new ArrayList<>();

        while (matcher.find()) {
            String key = matcher.group();
            Object value = params.opt(key);
            if (value == JSONObject.NULL) value = null;
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
        prefs.edit().remove("cookies").apply();
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

    private void saveCookies() throws Exception {
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

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(values.toString().getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[cipher.getIV().length + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, combined, 0, cipher.getIV().length);
        System.arraycopy(encrypted, 0, combined, cipher.getIV().length, encrypted.length);

        if (
            !prefs.edit()
                .putString("cookies", Base64.encodeToString(combined, Base64.NO_WRAP))
                .commit()
        ) {
            throw new IllegalStateException("Could not persist session");
        }
    }

    private void restoreCookies() {
        String encoded = prefs.getString("cookies", null);
        if (encoded == null) return;

        try {
            byte[] combined = Base64.decode(encoded, Base64.DEFAULT);
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
            JSONArray values = new JSONArray(json);
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
                    cookie.setMaxAge((expires - System.currentTimeMillis()) / 1000);
                }
                cookies.getCookieStore().add(origin, cookie);
            }
        } catch (Exception ignored) {
            clearSession();
        }
    }
}
