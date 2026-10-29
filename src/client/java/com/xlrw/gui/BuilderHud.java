package com.xlrw.gui;

import com.xlrw.builder.BuilderController;
import com.xlrw.builder.PlacementCandidate;
import com.xlrw.builder.PlacementFinder;
import com.xlrw.config.BuilderConfig;
import com.xlrw.builder.InventoryHelper;
import com.xlrw.schematic.Schematic;
import com.xlrw.schematic.SchematicManager;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class BuilderHud {

    public static void init() {
        HudRenderCallback.EVENT.register(BuilderHud::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.options.hideGui) return;

        BuilderConfig config = BuilderConfig.getInstance();
        Schematic schematic = SchematicManager.getInstance().getActiveSchematic();
        if (schematic == null) return;

        BuilderController controller = BuilderController.getInstance();
        if (!config.showHud) return;

        int x = 8;
        int y = 8;
        int width = 220;
        int height = 54;

        // Background panel
        graphics.fill(x, y, x + width, y + height, 0xDD121622);
        graphics.renderOutline(x, y, width, height, 0xFF3B4866);

        // Header: Layer & State
        int activeLayerY = schematic.getMinY() + config.currentLayer;
        int totalLayers = schematic.getMaxY() - schematic.getMinY() + 1;
        String layerText = "Tầng #" + (config.currentLayer + 1) + "/" + totalLayers + " (Y=" + activeLayerY + ")";
        int stateColor = config.enabled ? 0xFF55FF55 : 0xFFFFAA00;
        String stateText = config.enabled ? "● AUTO-BUILD [B]" : "○ TẠM DỪNG [B]";

        graphics.drawString(mc.font, stateText, x + 6, y + 6, stateColor, false);
        graphics.drawString(mc.font, layerText, x + width - mc.font.width(layerText) - 6, y + 6, 0xFFAAAAFF, false);

        // Next target block detection
        PlacementCandidate candidate = PlacementFinder.findBestCandidate();
        BlockPos targetPos = null;
        BlockState targetState = null;
        String blockName = "Đang tìm kiếm...";
        ItemStack displayStack = new ItemStack(Items.BARRIER);
        String diagnostic = controller.getStatusMessage();

        if (candidate != null) {
            targetPos = candidate.targetPos;
            targetState = candidate.requiredState;
            net.minecraft.world.item.Item item = candidate.item != null ? candidate.item : InventoryHelper.getItemForBlockState(targetState);
            displayStack = new ItemStack(item);
            blockName = targetState.getBlock().getName().getString();
        } else {
            // Find next missing block on layer to inform user what's needed
            BlockPos missing = controller.findNearestMissingBlockOnLayer(mc.player, schematic, config.currentLayer, mc.level, mc.player.blockPosition());
            if (missing != null) {
                targetPos = missing;
                targetState = schematic.getBlock(missing);
                if (targetState != null) {
                    net.minecraft.world.item.Item item = InventoryHelper.getItemForBlockState(targetState);
                    displayStack = new ItemStack(item);
                    blockName = targetState.getBlock().getName().getString();
                    if (!InventoryHelper.hasItem(mc.player, item) && !mc.player.isCreative()) {
                        diagnostic = "Thiếu vật liệu: " + item.getDescription().getString();
                    } else {
                        diagnostic = "Đang tìm đường tiếp cận...";
                    }
                }
            } else {
                blockName = "Tầng này đã xong!";
                displayStack = new ItemStack(Blocks.EMERALD_BLOCK);
            }
        }

        // Render item icon
        graphics.renderItem(displayStack, x + 6, y + 20);

        // Render block name & coords
        String posText = targetPos != null ? targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() : "--";
        double dist = targetPos != null ? Math.sqrt(mc.player.distanceToSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5)) : 0.0;
        String distText = targetPos != null ? String.format("%.1fm", dist) : "";

        graphics.drawString(mc.font, blockName, x + 26, y + 20, 0xFFFFFFFF, false);
        graphics.drawString(mc.font, "Vị trí: [" + posText + "]  " + distText, x + 26, y + 30, 0xFFAAAAAA, false);

        // Diagnostic / Status message
        if (diagnostic != null && !diagnostic.isEmpty()) {
            if (diagnostic.length() > 34) {
                diagnostic = diagnostic.substring(0, 31) + "...";
            }
            int diagColor = diagnostic.startsWith("Thiếu") || diagnostic.startsWith("Missing") ? 0xFFFF5555 : 0xFFE0E0E0;
            graphics.drawString(mc.font, "► " + diagnostic, x + 6, y + 42, diagColor, false);
        }

        // Live Real-Time Debug Panel for Autonomous Navigation & Scaffolding
        int dbgY = y + height + 4;
        int dbgHeight = 44;
        graphics.fill(x, dbgY, x + width, dbgY + dbgHeight, 0xCC0D111A);
        graphics.renderOutline(x, dbgY, width, dbgHeight, 0xFF2A344D);

        boolean inShaft = mc.level.getBlockState(mc.player.blockPosition()).is(Blocks.SCAFFOLDING);
        boolean onTop = mc.level.getBlockState(mc.player.blockPosition().below()).is(Blocks.SCAFFOLDING);

        String stateStr = controller.getPrinterState() != null ? controller.getPrinterState().name() : "IDLE";
        String keysStr = String.format("W:%s S:%s A:%s D:%s | J:%s SH:%s",
                mc.options.keyUp.isDown() ? "1" : "0",
                mc.options.keyDown.isDown() ? "1" : "0",
                mc.options.keyLeft.isDown() ? "1" : "0",
                mc.options.keyRight.isDown() ? "1" : "0",
                mc.options.keyJump.isDown() ? "1" : "0",
                mc.options.keyShift.isDown() ? "1" : "0");

        String rotStr = String.format("Yaw:%.0f° Pit:%.0f° | Gnd:%s",
                mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround() ? "YES" : "NO");

        String scaffoldStr = String.format("Scaffold: in=%s top=%s | Y=%.1f",
                inShaft ? "YES" : "no", onTop ? "YES" : "no", mc.player.getY());

        graphics.drawString(mc.font, "DEBUG: " + stateStr, x + 6, dbgY + 4, 0xFF55FFFF, false);
        graphics.drawString(mc.font, keysStr, x + 6, dbgY + 14, 0xFFFFAA00, false);
        graphics.drawString(mc.font, rotStr, x + 6, dbgY + 24, 0xFFAAAAAA, false);
        graphics.drawString(mc.font, scaffoldStr, x + 6, dbgY + 34, 0xFF88FF88, false);
    }
}
