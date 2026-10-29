package com.xlrw.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xlrw.config.BuilderConfig;
import com.xlrw.config.WorldStateManager;
import com.xlrw.render.SchematicRenderer;
import com.xlrw.render.SchematicRenderer.Bounds;
import com.xlrw.schematic.Schematic;
import com.xlrw.schematic.SchematicManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

/**
 * Free-camera placement editor.
 *
 * <ul>
 *   <li>WASD fly, Space / Shift up-down, Ctrl faster, scroll = speed, hold RIGHT mouse to look around.</li>
 *   <li>LEFT drag on an arrow (X red, Y green, Z blue) moves the schematic along that axis (snapped to blocks).</li>
 *   <li>LEFT drag on the orange ring rotates it in 90° steps; LEFT drag on the schematic itself slides it on the
 *   ground plane.</li>
 *   <li>Enter / DONE keeps the changes, CANCEL puts everything back.</li>
 * </ul>
 * The camera is a client-only invisible armor stand used as the camera entity, so no mixins are needed.
 */
public class SchematicEditorScreen extends Screen {

    public static SchematicEditorScreen ACTIVE = null;

    private enum Handle { NONE, X, Y, Z, RING, BODY }

    private static final int INK = 0xFF141416, CREAM = 0xFFE8D8C9, ORANGE = 0xFFF3701E, SLATE = 0xFF4B607F, PANEL = 0xE61A1A1D;

    private final Screen parent;
    private ArmorStand cam;
    private Vec3 camPos;
    private float yaw, pitch;
    private float speed = 9f;
    private long lastNanos;
    private boolean looking;
    private boolean prevHideGui;

    private BlockPos originalAnchor;
    private Rotation originalRotation;

    private Handle hover = Handle.NONE, drag = Handle.NONE;
    private double dragStartT;       // axis parameter at drag start
    private int dragApplied;         // blocks already applied for the current drag
    private double ringStartAngle;
    private int ringSteps;
    private Vec3 bodyStart;
    private int bodyAppliedX, bodyAppliedZ;
    private double mouseX, mouseY;
    private final java.util.List<Object[]> buttons = new java.util.ArrayList<>();

    public SchematicEditorScreen(Screen parent) {
        super(Component.literal("XLRW Editor"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        Schematic s = SchematicManager.getInstance().getActiveSchematic();
        if (mc.level == null || mc.player == null || s == null) {
            onClose();
            return;
        }
        if (cam == null) {
            originalAnchor = s.getAnchor();
            originalRotation = s.getRotation();
            cam = new ArmorStand(mc.level, mc.player.getX(), mc.player.getY(), mc.player.getZ());
            cam.setInvisible(true);
            cam.setNoGravity(true);
            // start a bit behind and above the player, looking at the schematic
            Bounds b = Bounds.of(s);
            Vec3 target = b != null ? b.center() : mc.player.position();
            Vec3 eye = mc.player.getEyePosition();
            Vec3 back = eye.subtract(target).normalize();
            if (!Double.isFinite(back.x)) back = new Vec3(0, 0.5, 1);
            camPos = eye.add(back.scale(4)).add(0, 3, 0);
            lookAt(target);
            applyCam();
            mc.setCameraEntity(cam);
            prevHideGui = mc.options.hideGui;
            mc.options.hideGui = true;
            lastNanos = System.nanoTime();
            ACTIVE = this;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------------------------------------

    private void lookAt(Vec3 t) {
        Vec3 d = t.subtract(camPos);
        yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
    }

    private void applyCam() {
        double eye = cam.getEyeHeight();
        cam.setPos(camPos.x, camPos.y - eye, camPos.z);
        cam.setYRot(yaw);
        cam.setXRot(pitch);
        cam.setYHeadRot(yaw);
        cam.xo = cam.getX(); cam.yo = cam.getY(); cam.zo = cam.getZ();
        cam.xOld = cam.getX(); cam.yOld = cam.getY(); cam.zOld = cam.getZ();
        cam.yRotO = yaw; cam.xRotO = pitch; cam.yHeadRotO = yaw;
    }

    private void fly() {
        long n = System.nanoTime();
        double dt = Math.min(0.1, (n - lastNanos) / 1e9);
        lastNanos = n;
        double f = 0, s = 0, u = 0;
        if (com.xlrw.compat.GuiCompat.keyDown(InputConstants.KEY_W)) f += 1;
        if (com.xlrw.compat.GuiCompat.keyDown(InputConstants.KEY_S)) f -= 1;
        if (com.xlrw.compat.GuiCompat.keyDown(InputConstants.KEY_A)) s += 1;
        if (com.xlrw.compat.GuiCompat.keyDown(InputConstants.KEY_D)) s -= 1;
        if (com.xlrw.compat.GuiCompat.keyDown(InputConstants.KEY_SPACE)) u += 1;
        if (com.xlrw.compat.GuiCompat.keyDown(InputConstants.KEY_LSHIFT)) u -= 1;
        double boost = com.xlrw.compat.GuiCompat.keyDown(InputConstants.KEY_LCONTROL) ? 2.5 : 1.0;
        if (f != 0 || s != 0 || u != 0) {
            double yr = Math.toRadians(yaw);
            Vec3 fwd = new Vec3(-Math.sin(yr), 0, Math.cos(yr));
            Vec3 left = new Vec3(Math.cos(yr), 0, Math.sin(yr));
            Vec3 mv = fwd.scale(f).add(left.scale(s)).add(0, u, 0);
            if (mv.lengthSqr() > 0) camPos = camPos.add(mv.normalize().scale(speed * boost * dt));
        }
        applyCam();
    }

    /** Ray through the mouse position, matching the game's perspective projection. */
    private Vec3[] mouseRay(double mx, double my) {
        Minecraft mc = Minecraft.getInstance();
        double fov = Math.toRadians(mc.options.fov().get());
        double aspect = (double) width / Math.max(1, height);
        double nx = 2 * mx / width - 1, ny = 1 - 2 * my / height;
        Vec3 fwd = Vec3.directionFromRotation(pitch, yaw);
        Vec3 right = new Vec3(-fwd.z, 0, fwd.x).normalize();
        if (!Double.isFinite(right.x)) right = new Vec3(1, 0, 0);
        Vec3 up = right.cross(fwd).normalize();
        double t = Math.tan(fov / 2);
        Vec3 dir = fwd.add(right.scale(nx * t * aspect)).add(up.scale(ny * t)).normalize();
        return new Vec3[]{camPos, dir};
    }

    // ------------------------------------------------------------------------------------------------
    // Gizmo geometry + picking
    // ------------------------------------------------------------------------------------------------

    private Vec3 origin(Bounds b) {
        return new Vec3((b.minX + b.maxX) / 2, b.minY + 0.5, (b.minZ + b.maxZ) / 2);
    }

    private double axisLen(Bounds b) {
        return Math.max(b.maxX - b.minX, b.maxZ - b.minZ) / 2 + 3;
    }

    private double ringRadius(Bounds b) {
        return Math.max(b.maxX - b.minX, b.maxZ - b.minZ) / 2 + 1.5;
    }

    private static Vec3 axisVec(Handle h) {
        return switch (h) {
            case X -> new Vec3(1, 0, 0);
            case Y -> new Vec3(0, 1, 0);
            default -> new Vec3(0, 0, 1);
        };
    }

    /** Parameter t on line (p + t*d) closest to the ray (o + s*r). */
    private static double closestT(Vec3 p, Vec3 d, Vec3 o, Vec3 r) {
        Vec3 w = p.subtract(o);
        double a = d.dot(d), b = d.dot(r), c = r.dot(r), dd = d.dot(w), e = r.dot(w);
        double den = a * c - b * b;
        if (Math.abs(den) < 1e-9) return 0;
        return (b * e - c * dd) / den;
    }

    private static double rayPointDist(Vec3 o, Vec3 r, Vec3 q) {
        double s = Math.max(0, q.subtract(o).dot(r));
        return o.add(r.scale(s)).distanceTo(q);
    }

    private Handle pick(Bounds b, double mx, double my) {
        Vec3[] ray = mouseRay(mx, my);
        Vec3 o = ray[0], r = ray[1];
        Vec3 org = origin(b);
        double len = axisLen(b);
        Handle best = Handle.NONE;
        double bestD = Double.MAX_VALUE;
        for (Handle h : new Handle[]{Handle.X, Handle.Y, Handle.Z}) {
            Vec3 d = axisVec(h);
            double t = Mth.clamp(closestT(org, d, o, r), 0, len + 1);
            Vec3 q = org.add(d.scale(t));
            double dist = rayPointDist(o, r, q);
            double tol = 0.35 + 0.02 * q.distanceTo(o);
            if (t > 0.6 && dist < tol && dist < bestD) {
                bestD = dist;
                best = h;
            }
        }
        if (best != Handle.NONE) return best;
        // ring on the ground plane
        if (Math.abs(r.y) > 1e-4) {
            double t = (b.minY + 0.05 - o.y) / r.y;
            if (t > 0) {
                Vec3 hit = o.add(r.scale(t));
                double rr = Math.hypot(hit.x - org.x, hit.z - org.z);
                if (Math.abs(rr - ringRadius(b)) < 0.6 + 0.015 * t) return Handle.RING;
            }
        }
        // body: ray through the bounding box
        if (new net.minecraft.world.phys.AABB(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ).clip(o, o.add(r.scale(256))).isPresent()) {
            return Handle.BODY;
        }
        return Handle.NONE;
    }

    private Vec3 groundHit(Vec3 o, Vec3 r, double y) {
        if (Math.abs(r.y) < 1e-4) return null;
        double t = (y - o.y) / r.y;
        return t > 0 ? o.add(r.scale(t)) : null;
    }

    // ------------------------------------------------------------------------------------------------
    // Frame
    // ------------------------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    //? if <1.21.11 {
    @Override
    protected void renderBlurredBackground(float partialTick) {
    }
    //?}

    @Override
    protected void renderMenuBackground(GuiGraphics g) {
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        mouseX = mx;
        mouseY = my;
        if (cam == null) return;
        fly();
        Schematic s = SchematicManager.getInstance().getActiveSchematic();
        if (s == null) {
            onClose();
            return;
        }
        Bounds b = Bounds.of(s);
        if (b != null) {
            if (drag == Handle.NONE && !looking) hover = pick(b, mx, my);
            if (drag != Handle.NONE) updateDrag(b, mx, my);
        }
        drawHud(g, s, mx, my);
    }

    private void updateDrag(Bounds b, double mx, double my) {
        Vec3[] ray = mouseRay(mx, my);
        SchematicManager sm = SchematicManager.getInstance();
        switch (drag) {
            case X, Y, Z -> {
                Vec3 d = axisVec(drag);
                double t = closestT(origin(b).subtract(d.scale(dragApplied)), d, ray[0], ray[1]);
                int want = (int) Math.round(t - dragStartT);
                int step = want - dragApplied;
                if (step != 0) {
                    sm.moveAnchor((int) d.x * step, (int) d.y * step, (int) d.z * step);
                    dragApplied = want;
                }
            }
            case RING -> {
                Vec3 org = origin(b);
                Vec3 hit = groundHit(ray[0], ray[1], b.minY + 0.05);
                if (hit == null) return;
                double ang = Math.toDegrees(Math.atan2(hit.z - org.z, hit.x - org.x));
                double delta = Mth.wrapDegrees(ang - ringStartAngle);
                int want = (int) Math.round(delta / 90.0);
                int step = want - ringSteps;
                for (int i = 0; i < ((step % 4) + 4) % 4; i++) sm.rotate(); // clockwise steps (3 = one CCW)
                if (step != 0) ringSteps = want;
            }
            case BODY -> {
                Vec3 hit = groundHit(ray[0], ray[1], bodyStart.y);
                if (hit == null) return;
                int wx = (int) Math.round(hit.x - bodyStart.x), wz = (int) Math.round(hit.z - bodyStart.z);
                int sx = wx - bodyAppliedX, sz = wz - bodyAppliedZ;
                if (sx != 0 || sz != 0) {
                    sm.moveAnchor(sx, 0, sz);
                    bodyAppliedX = wx;
                    bodyAppliedZ = wz;
                }
            }
            default -> {
            }
        }
    }

    /** Called from the world renderer (depth cleared) to draw the handles. */
    public void renderGizmo(PoseStack ps, MultiBufferSource consumers, Bounds b, long now) {
        VertexConsumer q = com.xlrw.compat.RenderCompat.quads(consumers);
        Vec3 o = origin(b);
        double len = axisLen(b);
        double th = 0.09;
        for (Handle h : new Handle[]{Handle.X, Handle.Y, Handle.Z}) {
            boolean hot = hover == h || drag == h;
            float[] c = h == Handle.X ? new float[]{0.95f, 0.3f, 0.25f} : h == Handle.Y ? new float[]{0.45f, 0.9f, 0.45f} : new float[]{0.35f, 0.6f, 1f};
            float a = hot ? 1f : 0.75f;
            double w = hot ? th * 1.6 : th;
            Vec3 d = axisVec(h);
            Vec3 e = o.add(d.scale(len));
            SchematicRenderer.box(ps, q, Math.min(o.x, e.x) - w, Math.min(o.y, e.y) - w, Math.min(o.z, e.z) - w,
                    Math.max(o.x, e.x) + w, Math.max(o.y, e.y) + w, Math.max(o.z, e.z) + w, c[0], c[1], c[2], a);
            double hs = hot ? 0.42 : 0.32; // arrow head block
            SchematicRenderer.box(ps, q, e.x - hs, e.y - hs, e.z - hs, e.x + hs, e.y + hs, e.z + hs, c[0], c[1], c[2], 1f);
        }
        // centre knob
        SchematicRenderer.cube(ps, q, o.x - 0.22, o.y - 0.22, o.z - 0.22, 0.44, 0.91f, 0.85f, 0.79f, 1f);
        // rotation ring made of small segments
        boolean hotRing = hover == Handle.RING || drag == Handle.RING;
        double rad = ringRadius(b), y = b.minY + 0.05;
        int segs = 64;
        double spin = (now / 40.0) % 360;
        for (int i = 0; i < segs; i++) {
            double a0 = Math.toRadians(i * 360.0 / segs), a1 = Math.toRadians((i + 0.6) * 360.0 / segs);
            double x0 = o.x + Math.cos(a0) * rad, z0 = o.z + Math.sin(a0) * rad;
            double x1 = o.x + Math.cos(a1) * rad, z1 = o.z + Math.sin(a1) * rad;
            boolean lit = Math.abs(Mth.wrapDegrees(i * 360.0 / segs - spin)) < 30;
            float al = hotRing ? 1f : (lit ? 0.95f : 0.55f);
            double t = hotRing ? 0.14 : 0.09;
            SchematicRenderer.box(ps, q, Math.min(x0, x1) - t, y - t, Math.min(z0, z1) - t, Math.max(x0, x1) + t, y + t, Math.max(z0, z1) + t,
                    0.95f, 0.44f, 0.12f, al);
        }
        if (hover == Handle.BODY || drag == Handle.BODY) {
            VertexConsumer lines = com.xlrw.compat.RenderCompat.lines(consumers);
            com.xlrw.compat.RenderCompat.lineBox(ps, lines, b.minX - 0.05, b.minY - 0.05, b.minZ - 0.05,
                    b.maxX + 0.05, b.maxY + 0.05, b.maxZ + 0.05, 0.91f, 0.85f, 0.79f, 1f);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------------------------------------

    private void drawHud(GuiGraphics g, Schematic s, int mx, int my) {
        buttons.clear();
        // top chip
        int x = 10, y = 10;
        slant(g, x + 3, y + 2, 92, 18, ORANGE);
        slant(g, x, y, 92, 18, CREAM);
        g.drawString(font, "EDIT MODE", x + 14, y + 5, INK, false);
        BlockPos a = s.getAnchor();
        String pos = "X " + a.getX() + "   Y " + a.getY() + "   Z " + a.getZ() + "   ⟳ " + rotLabel(s.getRotation());
        g.fill(x + 104, y + 2, x + 112 + font.width(pos), y + 18, PANEL);
        g.drawString(font, pos, x + 108, y + 6, CREAM, false);
        String act = switch (drag != Handle.NONE ? drag : hover) {
            case X -> "◀ ▶  X";
            case Y -> "▲ ▼  Y";
            case Z -> "◀ ▶  Z";
            case RING -> "⟳  90°";
            case BODY -> "✥  XZ";
            default -> "";
        };
        if (!act.isEmpty()) g.drawString(font, act, mx + 12, my + 4, ORANGE, true);

        // buttons bottom-right
        int bw = 64, bh = 18, by = height - bh - 10;
        button(g, width - 10 - bw, by, bw, bh, "DONE", ORANGE, INK, this::onClose, mx, my);
        button(g, width - 16 - bw * 2, by, bw, bh, "CANCEL", SLATE, CREAM, this::cancel, mx, my);
        button(g, width - 22 - bw * 3, by, bw, bh, "TO ME", SLATE, CREAM, () -> SchematicManager.getInstance().anchorToPlayer(), mx, my);
        // hints bottom-left (short)
        String[] hints = {"WASD  bay   ·   SPACE / SHIFT  lên xuống   ·   CTRL  nhanh   ·   cuộn  tốc độ " + Math.round(speed),
                "chuột phải  nhìn   ·   kéo mũi tên  dời   ·   kéo vòng  xoay   ·   kéo khối  trượt   ·   R  xoay   ·   ENTER  xong"};
        for (int i = 0; i < hints.length; i++) {
            int ty = height - 30 + i * 11;
            g.fill(8, ty - 2, 14 + font.width(hints[i]), ty + 9, 0x99141416);
            g.drawString(font, hints[i], 11, ty, i == 0 ? 0xFFB9C0CC : CREAM, false);
        }
        // crosshair-ish centre marker while looking
        if (looking) {
            g.fill(width / 2 - 4, height / 2, width / 2 + 5, height / 2 + 1, CREAM);
            g.fill(width / 2, height / 2 - 4, width / 2 + 1, height / 2 + 5, CREAM);
        }
    }

    private static String rotLabel(Rotation r) {
        return switch (r) {
            case NONE -> "0°";
            case CLOCKWISE_90 -> "90°";
            case CLOCKWISE_180 -> "180°";
            case COUNTERCLOCKWISE_90 -> "270°";
        };
    }

    private void slant(GuiGraphics g, int x, int y, int w, int h, int c) {
        for (int i = 0; i < h; i++) {
            int off = (h - i) / 3;
            g.fill(x + off, y + i, x + off + w, y + i + 1, c);
        }
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, int bg, int fg, Runnable r, int mx, int my) {
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + h;
        slant(g, x, y, w, h, hov ? (bg == ORANGE ? 0xFFFF8F45 : 0xFF6079A0) : bg);
        g.drawCenteredString(font, label, x + w / 2 + 3, y + (h - 8) / 2, fg);
        buttons.add(new Object[]{x, y, w, h, r});
    }

    // ------------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------------

    boolean xMouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Object[] bt : buttons) {
                int x = (int) bt[0], y = (int) bt[1], w = (int) bt[2], h = (int) bt[3];
                if (mx >= x && mx < x + w && my >= y && my < y + h) {
                    ((Runnable) bt[4]).run();
                    return true;
                }
            }
            Schematic s = SchematicManager.getInstance().getActiveSchematic();
            Bounds b = Bounds.of(s);
            if (b == null) return true;
            Handle h = pick(b, mx, my);
            drag = h;
            Vec3[] ray = mouseRay(mx, my);
            switch (h) {
                case X, Y, Z -> {
                    dragStartT = closestT(origin(b), axisVec(h), ray[0], ray[1]);
                    dragApplied = 0;
                }
                case RING -> {
                    Vec3 hit = groundHit(ray[0], ray[1], b.minY + 0.05);
                    Vec3 org = origin(b);
                    ringStartAngle = hit != null ? Math.toDegrees(Math.atan2(hit.z - org.z, hit.x - org.x)) : 0;
                    ringSteps = 0;
                }
                case BODY -> {
                    bodyStart = groundHit(ray[0], ray[1], b.minY);
                    if (bodyStart == null) drag = Handle.NONE;
                    bodyAppliedX = 0;
                    bodyAppliedZ = 0;
                }
                default -> {
                }
            }
            return true;
        }
        if (button == 1) {
            looking = true;
            return true;
        }
        return superMouseClicked(mx, my, button);
    }

    boolean xMouseReleased(double mx, double my, int button) {
        if (button == 0) drag = Handle.NONE;
        if (button == 1) looking = false;
        return true;
    }

    boolean xMouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 1) {
            double sens = 0.6 * (0.2 + Minecraft.getInstance().options.sensitivity().get());
            yaw += (float) (dx * sens);
            pitch = Mth.clamp(pitch + (float) (dy * sens), -90f, 90f);
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        speed = Mth.clamp(speed * (sy > 0 ? 1.2f : 1 / 1.2f), 2f, 60f);
        return true;
    }

    boolean xKeyPressed(int key, int scan, int mods) {
        SchematicManager sm = SchematicManager.getInstance();
        switch (key) {
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                onClose();
                return true;
            }
            case InputConstants.KEY_R -> {
                sm.rotate();
                return true;
            }
            case InputConstants.KEY_LEFT -> { sm.moveAnchor(-1, 0, 0); return true; }
            case InputConstants.KEY_RIGHT -> { sm.moveAnchor(1, 0, 0); return true; }
            case InputConstants.KEY_UP -> { sm.moveAnchor(0, 0, -1); return true; }
            case InputConstants.KEY_DOWN -> { sm.moveAnchor(0, 0, 1); return true; }
            case InputConstants.KEY_PAGEUP -> { sm.moveAnchor(0, 1, 0); return true; }
            case InputConstants.KEY_PAGEDOWN -> { sm.moveAnchor(0, -1, 0); return true; }
            default -> {
            }
        }
        return superKeyPressed(key, scan, mods); // ESC -> onClose (keeps changes)
    }

    private void cancel() {
        Schematic s = SchematicManager.getInstance().getActiveSchematic();
        if (s != null && originalAnchor != null) {
            int guard = 0;
            while (s.getRotation() != originalRotation && guard++ < 4) SchematicManager.getInstance().rotate();
            BlockPos a = s.getAnchor();
            SchematicManager.getInstance().moveAnchor(originalAnchor.getX() - a.getX(), originalAnchor.getY() - a.getY(), originalAnchor.getZ() - a.getZ());
        }
        onClose();
    }

    @Override
    public void onClose() {
        restoreCamera();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void removed() {
        restoreCamera();
        super.removed();
    }

    private void restoreCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (cam != null && mc.player != null && mc.getCameraEntity() == cam) {
            mc.setCameraEntity(mc.player);
            mc.options.hideGui = prevHideGui;
        }
        if (ACTIVE == this) ACTIVE = null;
        BuilderConfig.save();
        WorldStateManager.saveCurrentSession(mc);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    //? if >=1.21.11 {
    /*private net.minecraft.client.input.MouseButtonEvent xLastMouse;
    private boolean xLastDbl;
    private net.minecraft.client.input.KeyEvent xLastKey;
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean dbl) { xLastMouse = e; xLastDbl = dbl; return xMouseClicked(e.x(), e.y(), e.button()); }
    private boolean superMouseClicked(double x, double y, int b) { return super.mouseClicked(xLastMouse, xLastDbl); }
    @Override public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent e) { xLastMouse = e; return xMouseReleased(e.x(), e.y(), e.button()); }
    private boolean superMouseReleased(double x, double y, int b) { return super.mouseReleased(xLastMouse); }
    @Override public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent e, double dx, double dy) { xLastMouse = e; return xMouseDragged(e.x(), e.y(), e.button(), dx, dy); }
    private boolean superMouseDragged(double x, double y, int b, double dx, double dy) { return super.mouseDragged(xLastMouse, dx, dy); }
    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent e) { xLastKey = e; return xKeyPressed(e.key(), e.scancode(), e.modifiers()); }
    private boolean superKeyPressed(int k, int s, int m) { return super.keyPressed(xLastKey); }
    *///?} else {
    @Override public boolean mouseClicked(double x, double y, int b) { return xMouseClicked(x, y, b); }
    private boolean superMouseClicked(double x, double y, int b) { return super.mouseClicked(x, y, b); }
    @Override public boolean mouseReleased(double x, double y, int b) { return xMouseReleased(x, y, b); }
    private boolean superMouseReleased(double x, double y, int b) { return super.mouseReleased(x, y, b); }
    @Override public boolean mouseDragged(double x, double y, int b, double dx, double dy) { return xMouseDragged(x, y, b, dx, dy); }
    private boolean superMouseDragged(double x, double y, int b, double dx, double dy) { return super.mouseDragged(x, y, b, dx, dy); }
    @Override public boolean keyPressed(int k, int s, int m) { return xKeyPressed(k, s, m); }
    private boolean superKeyPressed(int k, int s, int m) { return super.keyPressed(k, s, m); }
    //?}
}
