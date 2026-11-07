package com.xlrw.account;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xlrw.config.BuilderConfig;
import com.xlrw.debug.XlrwDebug;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Xlrw account: login / register against the Xlrw server, avatar, and the Plus entitlement.
 *
 * <p>The mod works exactly as before without an account. Plus is only on while the server's signed entitlement
 * (checked with {@link #PUBLIC_KEY}) names this account AND this Minecraft account, is fresh (refreshed every
 * minute) and has not expired. Logging in on another Minecraft account ends the session here.
 */
public final class AccountManager {
    private static final AccountManager INSTANCE = new AccountManager();

    /** Server address (overridable with "accountServer" in config/xlrw.json). */
    public static final String DEFAULT_SERVER = "https://xlrw-api.lagxlr8.workers.dev";
    /** ECDSA P-256 public key of the server (X.509 / SPKI, base64). Entitlements not signed by it are ignored. */
    public static final String PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETLnjNkaTJi0eD64fnAub66hAluPAruLehlwNf9XP7ajRTXoKq6GOtOIQ718g8qX2vVJySEaOjgJK1kcok1k+1g==";

    private static final long REFRESH_MS = 60_000;
    /** How long a signed entitlement stays valid without a fresh one (server unreachable for a short while). */
    private static final long ENTITLEMENT_GRACE_MS = 15 * 60_000;

    private static final Gson GSON = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final File file = new File(FabricLoader.getInstance().getConfigDir().toFile(), "xlrw/account.json");

    private volatile String username;
    private volatile String token;
    private volatile long plusUntil;
    private volatile long entIssued;
    private volatile boolean entValid;
    private volatile int avatarVer = -1;
    private volatile int avatarLoaded = -1;
    private volatile ResourceLocation avatar;
    private volatile String status = "";
    private volatile boolean busy;
    private long nextRefresh;
    private PublicKey publicKey;

    public static AccountManager get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------------

    public boolean isLoggedIn() {
        return username != null && token != null;
    }

    public String username() {
        return username;
    }

    public String status() {
        return status;
    }

    public boolean busy() {
        return busy;
    }

    public long plusUntil() {
        return plusUntil;
    }

    /** Plus is on: valid, fresh, server-signed entitlement for this account and this Minecraft account. */
    public boolean isPlus() {
        long now = System.currentTimeMillis();
        return isLoggedIn() && entValid && now < plusUntil && now - entIssued < ENTITLEMENT_GRACE_MS;
    }

    /** Avatar texture, or null if the account has none (yet). */
    public ResourceLocation avatar() {
        return isLoggedIn() ? avatar : null;
    }

    private String server() {
        String s = BuilderConfig.getInstance().accountServer;
        if (s == null || s.isBlank()) s = DEFAULT_SERVER;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String mcUuid() {
        Minecraft mc = Minecraft.getInstance();
        try {
            return mc.getUser().getProfileId().toString().toLowerCase();
        } catch (Throwable t) {
            return ("offline-" + mc.getUser().getName()).toLowerCase();
        }
    }

    private static String mcName() {
        return Minecraft.getInstance().getUser().getName();
    }

    // ------------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------------

    public void init() {
        try {
            if (file.exists()) {
                JsonObject o = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject();
                if (o.has("username") && o.has("token")) {
                    username = o.get("username").getAsString();
                    token = o.get("token").getAsString();
                }
            }
        } catch (Exception e) {
            XlrwDebug.log("ACCOUNT", "Không đọc được account.json: " + e);
        }
        nextRefresh = 0; // refresh on the first tick
    }

    public void tick(Minecraft mc) {
        if (!isLoggedIn() || busy) return;
        long now = System.currentTimeMillis();
        if (now < nextRefresh) return;
        nextRefresh = now + REFRESH_MS;
        refresh(null);
    }

    private void save() {
        try {
            file.getParentFile().mkdirs();
            JsonObject o = new JsonObject();
            if (isLoggedIn()) {
                o.addProperty("username", username);
                o.addProperty("token", token);
            }
            Files.writeString(file.toPath(), GSON.toJson(o));
        } catch (Exception e) {
            XlrwDebug.log("ACCOUNT", "Không lưu được account.json: " + e);
        }
    }

    private void clearSession(String why) {
        username = null;
        token = null;
        plusUntil = 0;
        entValid = false;
        avatarVer = -1;
        avatar = null;
        status = why;
        save();
    }

    // ------------------------------------------------------------------------------------------------
    // Actions (all asynchronous; callbacks run on the client thread with ok / message)
    // ------------------------------------------------------------------------------------------------

    public void register(String user, String pass, Consumer<String> done) {
        JsonObject b = new JsonObject();
        b.addProperty("username", user);
        b.addProperty("password", pass);
        post("/api/register", b, false, r -> {
            if (r.ok) {
                login(user, pass, done);
            } else {
                finish(done, message(r.error));
            }
        });
    }

    public void login(String user, String pass, Consumer<String> done) {
        JsonObject b = new JsonObject();
        b.addProperty("username", user);
        b.addProperty("password", pass);
        b.addProperty("mcUuid", mcUuid());
        b.addProperty("mcName", mcName());
        post("/api/login", b, false, r -> {
            if (!r.ok) {
                finish(done, message(r.error));
                return;
            }
            token = r.json.get("token").getAsString();
            username = r.json.getAsJsonObject("profile").get("name").getAsString();
            save();
            applyProfile(r.json);
            String kicked = r.json.has("kicked") && !r.json.get("kicked").isJsonNull() ? r.json.get("kicked").getAsString() : null;
            nextRefresh = System.currentTimeMillis() + REFRESH_MS;
            finish(done, kicked != null ? "Đã đăng nhập. Phiên trên tài khoản Minecraft \"" + kicked + "\" đã bị đăng xuất." : "Đã đăng nhập: " + username);
        });
    }

    public void logout() {
        if (!isLoggedIn()) return;
        post("/api/logout", new JsonObject(), true, r -> { });
        clearSession("Đã đăng xuất.");
    }

    public void redeem(String key, Consumer<String> done) {
        JsonObject b = new JsonObject();
        b.addProperty("key", key.trim());
        post("/api/redeem", b, true, r -> {
            if (!r.ok) {
                finish(done, message(r.error));
                return;
            }
            applyProfile(r.json);
            finish(done, "Đã kích hoạt Plus thêm " + r.json.get("days").getAsInt() + " ngày!");
        });
    }

    /** Uploads an image file as the avatar (cropped to a square and scaled to 64x64 PNG). */
    public void uploadAvatar(File image, Consumer<String> done) {
        String png;
        try {
            png = Base64.getEncoder().encodeToString(AvatarImage.toPng64(image));
        } catch (Exception e) {
            finish(done, "Không đọc được ảnh: " + e.getMessage());
            return;
        }
        JsonObject b = new JsonObject();
        b.addProperty("png", png);
        post("/api/avatar", b, true, r -> {
            if (!r.ok) {
                finish(done, message(r.error));
                return;
            }
            applyProfile(r.json);
            finish(done, "Đã đổi avatar.");
        });
    }

    public void refresh(Consumer<String> done) {
        if (!isLoggedIn()) return;
        get("/api/me", r -> {
            if (r.ok) {
                applyProfile(r.json);
                finish(done, null);
            } else if ("session".equals(r.error) || "not_logged_in".equals(r.error)) {
                String reason = r.json != null && r.json.has("reason") ? r.json.get("reason").getAsString() : "";
                String who = r.json != null && r.json.has("mcName") ? r.json.get("mcName").getAsString() : "";
                String msg = "replaced".equals(reason)
                        ? "Tài khoản Xlrw đã đăng nhập ở nơi khác" + (who.isEmpty() ? "" : " (Minecraft: " + who + ")") + "."
                        : "other_minecraft".equals(reason) ? "Phiên đăng nhập thuộc tài khoản Minecraft khác, hãy đăng nhập lại."
                        : "Phiên đăng nhập đã hết, hãy đăng nhập lại.";
                clearSession(msg);
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) mc.player.displayClientMessage(Component.literal("§c[XLRW] " + msg), false);
                finish(done, msg);
            } else {
                // server unreachable: keep the last entitlement until its grace period ends
                finish(done, message(r.error));
            }
        });
    }

    private void applyProfile(JsonObject j) {
        JsonObject p = j.getAsJsonObject("profile");
        if (p != null) {
            int ver = p.has("avatarVer") ? p.get("avatarVer").getAsInt() : 0;
            avatarVer = ver;
            if (ver > 0 && ver != avatarLoaded) loadAvatar(ver);
        }
        JsonObject e = j.getAsJsonObject("entitlement");
        if (e != null) {
            String payload = e.get("payload").getAsString();
            String sig = e.get("sig").getAsString();
            String[] f = payload.split("\\|");
            boolean ok = f.length == 4 && verify(payload, sig)
                    && f[0].equalsIgnoreCase(username == null ? "" : username)
                    && f[1].equals(mcUuid());
            if (ok) {
                plusUntil = Long.parseLong(f[2]);
                entIssued = Long.parseLong(f[3]);
            }
            entValid = ok;
            if (!ok) XlrwDebug.log("ACCOUNT", "Chữ ký Plus không hợp lệ -> bỏ qua");
        }
    }

    private boolean verify(String payload, String sigB64) {
        try {
            if (publicKey == null) {
                byte[] der = Base64.getDecoder().decode(PUBLIC_KEY);
                publicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            }
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initVerify(publicKey);
            s.update(payload.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(sigB64));
        } catch (Exception e) {
            return false;
        }
    }

    private void loadAvatar(int ver) {
        String name = username;
        if (name == null) return;
        HttpRequest req = HttpRequest.newBuilder(URI.create(server() + "/api/avatar/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "?v=" + ver))
                .timeout(Duration.ofSeconds(10)).GET().build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((resp, ex) -> {
            if (ex != null || resp.statusCode() != 200) return;
            byte[] bytes = resp.body();
            Minecraft.getInstance().execute(() -> {
                try {
                    com.mojang.blaze3d.platform.NativeImage img = com.mojang.blaze3d.platform.NativeImage.read(new java.io.ByteArrayInputStream(bytes));
                    ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("xlrw", "dynamic/avatar_" + ver);
                    Minecraft.getInstance().getTextureManager().register(loc, com.xlrw.compat.GuiCompat.texture(img));
                    ResourceLocation old = avatar;
                    avatar = loc;
                    avatarLoaded = ver;
                    if (old != null && !old.equals(loc)) Minecraft.getInstance().getTextureManager().release(old);
                } catch (Exception e) {
                    XlrwDebug.log("ACCOUNT", "Avatar lỗi: " + e);
                }
            });
        });
    }

    // ------------------------------------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------------------------------------

    private record Result(boolean ok, String error, JsonObject json) {}

    private HttpRequest.Builder base(String path, boolean auth) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server() + path)).timeout(Duration.ofSeconds(12))
                .header("content-type", "application/json");
        if (auth && token != null && username != null) {
            b.header("authorization", "Bearer " + token).header("x-mc-uuid", mcUuid()).header("x-xlrw-user", username);
        }
        return b;
    }

    private void post(String path, JsonObject body, boolean auth, Consumer<Result> then) {
        send(base(path, auth).POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build(), then);
    }

    private void get(String path, Consumer<Result> then) {
        send(base(path, true).GET().build(), then);
    }

    private void send(HttpRequest req, Consumer<Result> then) {
        busy = true;
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, ex) -> {
            Result r;
            if (ex != null) {
                r = new Result(false, "network", null);
            } else {
                JsonObject j = null;
                try {
                    j = JsonParser.parseString(resp.body()).getAsJsonObject();
                } catch (Exception ignored) {
                }
                boolean ok = j != null && j.has("ok") && j.get("ok").getAsBoolean();
                String error = ok ? null : (j != null && j.has("error") ? j.get("error").getAsString() : "http_" + resp.statusCode());
                r = new Result(ok, error, j);
            }
            Result fr = r;
            Minecraft.getInstance().execute(() -> {
                busy = false;
                try {
                    then.accept(fr);
                } catch (Exception e) {
                    XlrwDebug.log("ACCOUNT", "Lỗi xử lý phản hồi: " + e);
                }
            });
        });
    }

    private void finish(Consumer<String> done, String msg) {
        if (msg != null) status = msg;
        if (done != null) done.accept(msg);
    }

    private static String message(String error) {
        if (error == null) return "Lỗi không rõ.";
        return switch (error) {
            case "network" -> "Không kết nối được máy chủ Xlrw.";
            case "bad_username" -> "Tên chỉ gồm chữ, số, _ (3–16 ký tự).";
            case "bad_password" -> "Mật khẩu cần 6–128 ký tự.";
            case "taken" -> "Tên này đã có người dùng.";
            case "wrong_login", "bad_login" -> "Sai tên hoặc mật khẩu.";
            case "bad_key" -> "Key không đúng hoặc đã bị huỷ.";
            case "used" -> "Key này đã được tài khoản khác dùng.";
            case "already_yours" -> "Bạn đã dùng key này rồi.";
            case "other_user" -> "Key này dành cho tài khoản khác.";
            case "key_expired" -> "Key đã hết hạn kích hoạt.";
            case "bad_image" -> "Ảnh không hợp lệ.";
            case "image_too_big" -> "Ảnh quá lớn.";
            case "session", "not_logged_in" -> "Phiên đăng nhập đã hết, hãy đăng nhập lại.";
            default -> "Lỗi máy chủ (" + error + ").";
        };
    }
}
