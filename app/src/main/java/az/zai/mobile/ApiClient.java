package az.zai.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * chat.z.ai backend API müştərisi.
 *
 * Axın (reverse-engineered):
 *  1) GET  /api/v1/auths/          — avtomatik qonaq hesabı + JWT token
 *  2) GET  /api/models             — model siyahısı (Bearer token)
 *  3) POST /api/v2/chat/completions — SSE stream (delta_content)
 */
public final class ApiClient {

    public static final String BASE = "https://chat.z.ai";
    public static final String FE_VERSION = "prod-fe-1.1.121";
    public static final String DEFAULT_MODEL = "x-preview-l"; // GLM-5.3-Flash

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ApiClient() {
    }

    /** İstifadəçi məlumatı (token + ad + rol). */
    public static class AuthResult {
        public String token;
        public String name;
        public String role;
        public String id;
    }

    /** Model məlumatı. */
    public static class ModelInfo {
        public String id;
        public String name;
        public String description;
    }

    /** SSE stream hadisələri. */
    public interface StreamListener {
        void onDelta(String fullContent);

        void onPhase(String phase);

        void onDone();

        void onError(String message);
    }

    private static Request.Builder baseGet(String path, String token) {
        Request.Builder b = new Request.Builder()
                .url(BASE + path)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("X-FE-Version", FE_VERSION)
                .header("Referer", BASE + "/");
        if (token != null && !token.isEmpty()) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    /** Token yoxdursa yeni qonaq hesabı yaradır, varsa mövcud tokeni yoxlayır. */
    public static AuthResult obtainAuth(String existingToken) throws IOException {
        // Əvvəlcə mövcud tokeni yoxla
        if (existingToken != null && !existingToken.isEmpty()) {
            Response r = null;
            try {
                Request req = baseGet("/api/v1/auths/", existingToken).build();
                r = CLIENT.newCall(req).execute();
                if (r.isSuccessful() && r.body() != null) {
                    AuthResult a = parseAuth(r.body().string());
                    if (a.token == null || a.token.isEmpty()) {
                        a.token = existingToken;
                    }
                    return a;
                }
            } catch (IOException e) {
                // şəbəkə xətası — aşağı düşüb qonaq yaradılmasını sınayaq
            } finally {
                if (r != null) r.close();
            }
        }
        // Token-siz sorğu → server yeni qonaq hesabı qaytarır
        Request req = baseGet("/api/v1/auths/", null).build();
        Response r = CLIENT.newCall(req).execute();
        try {
            if (!r.isSuccessful() || r.body() == null) {
                throw new IOException("Auth xətası: HTTP " + r.code());
            }
            return parseAuth(r.body().string());
        } finally {
            r.close();
        }
    }

    private static AuthResult parseAuth(String body) throws IOException {
        try {
            JSONObject o = new JSONObject(body);
            AuthResult a = new AuthResult();
            a.token = o.optString("token");
            a.name = o.optString("name");
            a.role = o.optString("role");
            a.id = o.optString("id");
            if (a.token.isEmpty()) {
                throw new IOException("Token cavabda yoxdur");
            }
            return a;
        } catch (Exception e) {
            throw new IOException("Auth cavabı oxuna bilmədi", e);
        }
    }

    /** Model siyahısını gətirir. */
    public static List<ModelInfo> getModels(String token) throws IOException {
        Request req = baseGet("/api/models", token).build();
        Response r = CLIENT.newCall(req).execute();
        try {
            if (!r.isSuccessful() || r.body() == null) {
                throw new IOException("Modellər alınmadı: HTTP " + r.code());
            }
            try {
                JSONObject root = new JSONObject(r.body().string());
                JSONArray arr = root.getJSONArray("data");
                List<ModelInfo> out = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.getJSONObject(i);
                    ModelInfo mi = new ModelInfo();
                    mi.id = m.optString("id");
                    mi.name = m.optString("name", mi.id);
                    try {
                        mi.description = m.getJSONObject("info").getJSONObject("meta")
                                .getJSONObject("info").getJSONObject("desc").optString("en", "");
                    } catch (Exception ignored) {
                        mi.description = "";
                    }
                    out.add(mi);
                }
                return out;
            } catch (org.json.JSONException e) {
                throw new IOException("Model cavabı oxuna bilmədi", e);
            }
        } finally {
            r.close();
        }
    }

    /**
     * SSE chat stream. history: [role, content] cütlükləri.
     * dayandırmaq üçün outRef-ə yazılan Call.cancel() çağırılır.
     */
    public static void streamChat(String token, String model, String chatId,
                                  List<String[]> history, StreamListener listener,
                                  AtomicReference<Call> outRef) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("stream", true);
            body.put("chat_id", chatId);
            body.put("id", UUID.randomUUID().toString());
            JSONArray msgs = new JSONArray();
            for (String[] pair : history) {
                JSONObject m = new JSONObject();
                m.put("role", pair[0]);
                m.put("content", pair[1]);
                msgs.put(m);
            }
            body.put("messages", msgs);

            Request req = new Request.Builder()
                    .url(BASE + "/api/v2/chat/completions")
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("User-Agent", UA)
                    .header("X-FE-Version", FE_VERSION)
                    .header("Origin", BASE)
                    .header("Referer", BASE + "/")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            Call call = CLIENT.newCall(req);
            if (outRef != null) {
                outRef.set(call);
            }
            Response resp = call.execute();
            try {
                if (!resp.isSuccessful()) {
                    String detail = "HTTP " + resp.code();
                    try {
                        if (resp.body() != null) {
                            JSONObject e = new JSONObject(resp.body().string());
                            detail = e.optString("detail", detail);
                        }
                    } catch (Exception ignored) {
                    }
                    fail(listener, detail);
                    return;
                }
                if (resp.body() == null) {
                    fail(listener, "Boş cavab");
                    return;
                }

                StringBuilder buf = new StringBuilder();
                boolean[] done = {false};

                String line;
                while ((line = resp.body().source().readUtf8Line()) != null) {
                    if (line.isEmpty()) continue;
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.equals("[DONE]")) break;

                    try {
                        JSONObject msg = new JSONObject(payload);
                        JSONObject d = msg.optJSONObject("data");
                        if (d == null) d = msg;
                        JSONObject p = d.optJSONObject("data");
                        if (p == null) p = d;

                        // xəta yoxlaması
                        JSONObject err = p.optJSONObject("error");
                        if (err == null) err = d.optJSONObject("error");
                        if (err != null && err.length() > 0) {
                            String det = err.optString("detail",
                                    err.optString("error_code", "Bilinməyən xəta"));
                            fail(listener, det);
                            return;
                        }

                        String phase = p.optString("phase", null);
                        if (phase != null && !phase.isEmpty()) {
                            final String ph = phase;
                            MAIN.post(() -> listener.onPhase(ph));
                        }

                        String delta = p.optString("delta_content", null);
                        if (delta != null && !delta.isEmpty()) {
                            buf.append(delta);
                            final String full = buf.toString();
                            MAIN.post(() -> listener.onDelta(full));
                            continue;
                        }

                        String type = p.optString("type", "");
                        String content = p.optString("content", null);
                        if (content != null && !content.isEmpty()) {
                            if (type.equals("replace")) {
                                buf.setLength(0);
                            }
                            // content tam mətndirsə (delta gəlməyibsə) üzərinə yaz
                            if (buf.length() == 0 || buf.toString().equals(content)) {
                                buf.setLength(0);
                                buf.append(content);
                            } else if (!buf.toString().contains(content)) {
                                buf.append(content);
                            }
                            final String full = buf.toString();
                            MAIN.post(() -> listener.onDelta(full));
                        }

                        if (p.optBoolean("done", false) || d.optBoolean("done", false)) {
                            done[0] = true;
                        }
                    } catch (Exception ignored) {
                        // tək sətir pozulsa da stream davam etsin
                    }
                }
                MAIN.post(listener::onDone);
            } finally {
                resp.close();
            }
        } catch (IOException e) {
            if (outRef != null && outRef.get() != null && outRef.get().isCanceled()) {
                // istifadəçi dayandırdı — xəta deyil
                MAIN.post(listener::onDone);
            } else {
                fail(listener, "Bağlantı kəsildi: " + e.getMessage());
            }
        } catch (Exception e) {
            fail(listener, "Xəta: " + e.getMessage());
        }
    }

    private static void fail(StreamListener l, String msg) {
        MAIN.post(() -> l.onError(msg));
    }
}
