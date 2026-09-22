package dev.sakus.vrcx.android;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Native transport for VRCX's API request shape. Cookies never cross the JS bridge. */
final class NativeApi {
    private static final String API = "https://api.vrchat.cloud/api/1/";
    private static final String KEY_ALIAS = "vrcx_android_session_v1";
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final SharedPreferences prefs;

    NativeApi(Context context) {
        prefs = context.getSharedPreferences("native_session", Context.MODE_PRIVATE);
        restoreCookies();
    }

    JSONObject login(String username, String password) throws Exception {
        clearSession();
        String pair = username + ":" + password;
        String basic = Base64.encodeToString(pair.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        JSONObject response = execute(new URL(API + "auth/user"), "GET", null, null, null, "Basic " + basic);
        int status = response.getInt("status");
        JSONObject data = new JSONObject(response.optString("body", "{}"));
        if (status != 200 && !data.has("requiresTwoFactorAuth"))
            throw new IllegalStateException(data.optJSONObject("error") != null
                ? data.getJSONObject("error").optString("message", "Login failed") : "Login failed: " + status);
        return data;
    }

    JSONObject request(JSONObject input) throws Exception {
        String url = input.getString("url");
        URL parsed = new URL(url);
        // Never let renderer supplied URLs receive the session or credentials.
        if (!"https".equals(parsed.getProtocol()) || !"api.vrchat.cloud".equals(parsed.getHost())
            || parsed.getPort() != -1 || !parsed.getPath().startsWith("/api/1/"))
            throw new SecurityException("URL outside VRChat API");
        String method = input.optString("method", "GET");
        if (!method.equals("GET") && !method.equals("POST") && !method.equals("PUT") && !method.equals("DELETE"))
            throw new SecurityException("Unsupported HTTP method");
        String body = input.optString("body", null);
        String image = input.optString("imageData", null);
        if (image != null) {
            if (!method.equals("POST") || !parsed.getPath().equals("/api/1/file/image"))
                throw new SecurityException("Image upload must use the File API");
            String tag = input.optString("tag", "");
            if (!tag.equals("worldimage") && !tag.equals("avatarimage"))
                throw new SecurityException("Invalid image tag");
            return execute(parsed, method, null, image, tag, null);
        }
        return execute(parsed, method, body, null, null, null);
    }

    void clearSession() {
        cookies.getCookieStore().removeAll();
        prefs.edit().remove("cookies").apply();
    }

    private JSONObject execute(URL url, String method, String body, String image, String tag, String auth) throws Exception {
        URI uri = url.toURI();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent", "VRCX-Android/0.1 (unofficial)");
        connection.setRequestProperty("Accept", "application/json");
        if (auth != null) connection.setRequestProperty("Authorization", auth);
        Map<String, List<String>> cookieHeaders = cookies.get(uri, Collections.emptyMap());
        for (String cookie : cookieHeaders.getOrDefault("Cookie", Collections.emptyList())) connection.addRequestProperty("Cookie", cookie);

        if (image != null) {
            byte[] bytes = Base64.decode(image, Base64.DEFAULT);
            if (bytes.length > 20_000_000) throw new IllegalArgumentException("Image too large");
            String boundary = "VRCX" + UUID.randomUUID().toString().replace("-", "");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"tag\"\r\n\r\n" + tag + "\r\n");
                write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"blob\"\r\nContent-Type: image/png\r\n\r\n");
                out.write(bytes);
                write(out, "\r\n--" + boundary + "--\r\n");
            }
        } else if (body != null && !method.equals("GET")) {
            connection.setRequestProperty("Content-Type", "application/json;charset=utf-8");
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }

        try {
            int status = connection.getResponseCode();
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() != null && header.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for (String value : header.getValue()) {
                        for (HttpCookie cookie : HttpCookie.parse(value)) cookies.getCookieStore().add(uri, cookie);
                    }
                }
            }
            saveCookies();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (stream != null) try (stream) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (output.size() + read > MAX_RESPONSE) throw new IllegalStateException("Response too large");
                    output.write(buffer, 0, read);
                }
            }
            JSONObject result = new JSONObject();
            result.put("status", status);
            result.put("body", output.toString(StandardCharsets.UTF_8.name()));
            return result;
        } finally { connection.disconnect(); }
    }

    private static void write(OutputStream stream, String value) throws Exception {
        stream.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private void saveCookies() throws Exception {
        JSONArray values = new JSONArray();
        for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
            if (cookie.hasExpired()) continue;
            JSONObject entry = new JSONObject();
            entry.put("name", cookie.getName()); entry.put("value", cookie.getValue());
            entry.put("domain", cookie.getDomain()); entry.put("path", cookie.getPath());
            entry.put("secure", cookie.getSecure()); entry.put("httpOnly", cookie.isHttpOnly());
            entry.put("expires", cookie.getMaxAge() < 0 ? -1 : System.currentTimeMillis() + cookie.getMaxAge() * 1000);
            values.put(entry);
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(values.toString().getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[cipher.getIV().length + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, combined, 0, cipher.getIV().length);
        System.arraycopy(encrypted, 0, combined, cipher.getIV().length, encrypted.length);
        if (!prefs.edit().putString("cookies", Base64.encodeToString(combined, Base64.NO_WRAP)).commit())
            throw new IllegalStateException("Could not persist session");
    }

    private void restoreCookies() {
        String encoded = prefs.getString("cookies", null);
        if (encoded == null) return;
        try {
            byte[] combined = Base64.decode(encoded, Base64.DEFAULT);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, combined, 0, 12));
            String json = new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
            JSONArray values = new JSONArray(json);
            URI origin = new URI(API);
            for (int i = 0; i < values.length(); i++) {
                JSONObject entry = values.getJSONObject(i);
                long expires = entry.getLong("expires");
                if (expires != -1 && expires <= System.currentTimeMillis()) continue;
                HttpCookie cookie = new HttpCookie(entry.getString("name"), entry.getString("value"));
                cookie.setDomain(entry.optString("domain", "api.vrchat.cloud"));
                cookie.setPath(entry.optString("path", "/"));
                cookie.setSecure(entry.optBoolean("secure", true));
                cookie.setHttpOnly(entry.optBoolean("httpOnly", true));
                if (expires != -1) cookie.setMaxAge((expires - System.currentTimeMillis()) / 1000);
                cookies.getCookieStore().add(origin, cookie);
            }
        } catch (Exception ignored) {
            // A restored backup cannot decrypt a device-bound key; drop stale session silently.
            clearSession();
        }
    }
}
