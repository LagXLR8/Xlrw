package com.xlrw.gui;

import com.xlrw.account.AccountManager;
import com.xlrw.config.BuilderConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Xlrw account: log in / register, avatar, Plus key. Opened from the logo / name in the deck header. */
public class AccountScreen extends Screen {
    private static final int BG = 0xF21A1A1D, LINE = 0xFF33343C, CREAM = 0xFFE8D8C9, DIM = 0xFF8D97AB, ORANGE = 0xFFF3701E,
            GOLD = 0xFFF3C04E, OK = 0xFF5FD38D, BAD = 0xFFE0403A, INK = 0xFF141416;

    private final Screen parent;
    private EditBox user, pass, key;
    private String message = "";
    private boolean messageBad;
    private int px, py, pw, ph;

    public AccountScreen(Screen parent) {
        super(Component.literal("Xlrw Account"));
        this.parent = parent;
    }

    private AccountManager acc() {
        return AccountManager.get();
    }

    @Override
    protected void init() {
        pw = 300;
        ph = acc().isLoggedIn() ? 232 : 196;
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int x = px + 16, w = pw - 32;
        if (!acc().isLoggedIn()) {
            user = addRenderableWidget(new EditBox(font, x, py + 58, w, 18, Component.literal("Tên")));
            user.setMaxLength(16);
            user.setHint(Component.literal("Tên tài khoản Xlrw"));
            pass = addRenderableWidget(new EditBox(font, x, py + 96, w, 18, Component.literal("Mật khẩu")));
            pass.setMaxLength(128);
            pass.setHint(Component.literal("Mật khẩu (ít nhất 6 ký tự)"));
            int bw = (w - 8) / 2;
            addRenderableWidget(Button.builder(Component.literal("Đăng nhập"), b -> doLogin(false)).bounds(x, py + 126, bw, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Đăng ký"), b -> doLogin(true)).bounds(x + bw + 8, py + 126, bw, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Quay lại"), b -> onClose()).bounds(x, py + ph - 30, w, 20).build());
            setInitialFocus(user);
        } else {
            key = addRenderableWidget(new EditBox(font, x, py + 112, w - 88, 18, Component.literal("Key")));
            key.setMaxLength(40);
            key.setHint(Component.literal("XLRW-XXXX-XXXX-XXXX"));
            addRenderableWidget(Button.builder(Component.literal("Kích hoạt"), b -> doRedeem()).bounds(x + w - 82, py + 111, 82, 20).build());
            int bw = (w - 8) / 2;
            addRenderableWidget(Button.builder(Component.literal("Đổi avatar..."), b -> pickAvatar()).bounds(x, py + 142, bw, 20).build());
            addRenderableWidget(Button.builder(reachLabel(), b -> {
                BuilderConfig c = BuilderConfig.getInstance();
                c.plusReach = !c.plusReach;
                BuilderConfig.save();
                b.setMessage(reachLabel());
            }).bounds(x + bw + 8, py + 142, bw, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Đăng xuất"), b -> {
                acc().logout();
                rebuildWidgets();
                say("Đã đăng xuất.", false);
            }).bounds(x, py + ph - 30, bw, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Quay lại"), b -> onClose()).bounds(x + bw + 8, py + ph - 30, bw, 20).build());
        }
    }

    private Component reachLabel() {
        return Component.literal("Tầm xa (Plus): " + (BuilderConfig.getInstance().plusReach ? "BẬT" : "TẮT"));
    }

    private void say(String msg, boolean bad) {
        message = msg == null ? "" : msg;
        messageBad = bad;
    }

    private void doLogin(boolean register) {
        String u = user.getValue().trim(), p = pass.getValue();
        if (u.isEmpty() || p.isEmpty()) {
            say("Nhập tên và mật khẩu.", true);
            return;
        }
        say(register ? "Đang đăng ký..." : "Đang đăng nhập...", false);
        java.util.function.Consumer<String> done = m -> {
            say(m, !acc().isLoggedIn());
            if (acc().isLoggedIn()) rebuildWidgets();
        };
        if (register) acc().register(u, p, done);
        else acc().login(u, p, done);
    }

    private void doRedeem() {
        String k = key.getValue().trim();
        if (k.isEmpty()) {
            say("Nhập key.", true);
            return;
        }
        say("Đang kiểm tra key...", false);
        acc().redeem(k, m -> {
            boolean ok = m != null && m.startsWith("Đã kích hoạt");
            say(m, !ok);
            if (ok) key.setValue("");
        });
    }

    /** Native file picker (LWJGL tinyfd); falls back to config/xlrw/avatar.png when it is not available. */
    private void pickAvatar() {
        say("Chọn ảnh...", false);
        Thread t = new Thread(() -> {
            String path = null;
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                org.lwjgl.PointerBuffer filters = stack.mallocPointer(4);
                filters.put(stack.UTF8("*.png")).put(stack.UTF8("*.jpg")).put(stack.UTF8("*.jpeg")).put(stack.UTF8("*.gif"));
                filters.flip();
                path = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("Chọn ảnh avatar", "", filters, "Ảnh", false);
            } catch (Throwable e) {
                File f = new File(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().toFile(), "xlrw/avatar.png");
                if (f.exists()) path = f.getAbsolutePath();
                else {
                    String msg = "Không mở được hộp chọn file. Đặt ảnh vào config/xlrw/avatar.png rồi bấm lại.";
                    minecraft.execute(() -> say(msg, true));
                    return;
                }
            }
            if (path == null) {
                minecraft.execute(() -> say("Đã huỷ chọn ảnh.", false));
                return;
            }
            File file = new File(path);
            minecraft.execute(() -> {
                say("Đang tải avatar lên...", false);
                acc().uploadAvatar(file, m -> say(m, m == null || !m.startsWith("Đã đổi")));
            });
        }, "xlrw-avatar-picker");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        // panel under the widgets
        g.fill(px, py, px + pw, py + ph, BG);
        g.fill(px, py + 36, px + pw, py + 37, ORANGE);
        g.fill(px, py, px + pw, py + 1, LINE);
        g.fill(px, py + ph - 1, px + pw, py + ph, LINE);
        g.fill(px, py, px + 1, py + ph, LINE);
        g.fill(px + pw - 1, py, px + pw, py + ph, LINE);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);

        // logo / avatar
        g.fill(px + 10, py + 6, px + 34, py + 30, ORANGE);
        ResourceLocation av = acc().avatar();
        if (av != null) com.xlrw.compat.GuiCompat.blitTex(g, av, px + 11, py + 7, 22, 22, 0, 0, 64, 64, 64, 64);
        else g.drawString(font, "X", px + 19, py + 14, INK, false);
        g.drawString(font, "TÀI KHOẢN XLRW", px + 42, py + 8, CREAM, false);
        int x = px + 16;
        if (!acc().isLoggedIn()) {
            g.drawString(font, "Không cần đăng nhập vẫn dùng mod bình thường.", px + 42, py + 20, DIM, false);
            g.drawString(font, "Tên", x, py + 47, DIM, false);
            g.drawString(font, "Mật khẩu", x, py + 85, DIM, false);
            maskPassword(g);
        } else {
            g.drawString(font, acc().username(), px + 42, py + 20, ORANGE, false);
            boolean plus = acc().isPlus();
            long until = acc().plusUntil();
            String state = plus ? "PLUS đến " + new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(until))
                    : until > System.currentTimeMillis() ? "PLUS: đang xác minh..." : "Bản thường";
            g.drawString(font, "Trạng thái", x, py + 50, DIM, false);
            g.drawString(font, state, x, py + 62, plus ? GOLD : CREAM, false);
            if (plus) {
                g.drawString(font, "Đặt nhanh hơn · lập kế hoạch xa hơn · ngắm nhanh hơn", x, py + 76, DIM, false);
            } else {
                g.drawString(font, "Nhập key để mở Plus: bot đặt nhanh và mạnh hơn.", x, py + 76, DIM, false);
            }
            g.drawString(font, "Key Plus", x, py + 101, DIM, false);
        }
        if (!message.isEmpty()) {
            int c = messageBad ? BAD : OK;
            int my0 = acc().isLoggedIn() ? py + 170 : py + 154;
            for (var line : font.split(Component.literal(message), pw - 32)) {
                g.drawString(font, line, x, my0, c, false);
                my0 += 10;
            }
        } else if (acc().busy()) {
            g.drawString(font, "...", x, acc().isLoggedIn() ? py + 170 : py + 154, DIM, false);
        }
    }

    /** Draws dots over the password field (on top of its text). */
    private void maskPassword(GuiGraphics g) {
        if (pass == null) return;
        int n = pass.getValue().length();
        if (n == 0) return;
        com.xlrw.compat.GuiCompat.push(g);
        com.xlrw.compat.GuiCompat.translate(g, 0, 0, 300);
        g.fill(pass.getX() + 1, pass.getY() + 1, pass.getX() + pass.getWidth() - 1, pass.getY() + pass.getHeight() - 1, 0xFF000000);
        g.drawString(font, "•".repeat(Math.min(n, 40)), pass.getX() + 4, pass.getY() + 5, 0xFFE0E0E0, false);
        com.xlrw.compat.GuiCompat.pop(g);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
