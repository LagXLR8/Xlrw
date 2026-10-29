package com.xlrw.gui;

import com.xlrw.builder.BuilderController;
import com.xlrw.config.BuilderConfig;
import com.xlrw.printer.ChestManager;
import com.xlrw.printer.ScaffoldManager;
import com.xlrw.schematic.Schematic;
import com.xlrw.schematic.SchematicManager;
import com.xlrw.schematic.MaterialReport;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;

import java.io.File;
import java.util.List;

public class XlrwScreen extends Screen {

    private enum Tab {
        SCHEMATICS,
        POSITION,
        PRINTER_ENGINE,
        VISUALS
    }

    private enum SchematicSubView {
        INFO,
        MATERIALS
    }

    private Tab currentTab = Tab.SCHEMATICS;
    private SchematicSubView schematicSubView = SchematicSubView.INFO;
    private MaterialReport materialReport = null;
    private int materialPage = 0;

    private List<File> availableFiles;
    private int selectedFileIndex = 0;

    // Window dimensions
    private int windowX, windowY, windowWidth, windowHeight;

    public XlrwScreen() {
        super(Component.literal("XLRW Client"));
    }

    @Override
    protected void init() {
        super.init();
        refreshFiles();

        this.windowWidth = Math.min(480, width - 20);
        this.windowHeight = Math.min(300, height - 20);
        this.windowX = (width - windowWidth) / 2;
        this.windowY = (height - windowHeight) / 2;
    }

    private void refreshFiles() {
        this.availableFiles = SchematicManager.getInstance().getAvailableSchematics();
        if (selectedFileIndex >= availableFiles.size()) {
            selectedFileIndex = Math.max(0, availableFiles.size() - 1);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Crisp, unblurred subtle dimming so the 3D world and ghost blocks remain 100% sharp and visible
        graphics.fill(0, 0, width, height, 0x44000000);
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
        // Intentionally empty: completely disables Minecraft 1.21's background blur shader
    }

    @Override
    protected void renderMenuBackground(GuiGraphics graphics) {
        // Intentionally empty: disables vanilla dark/dirt background
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);

        // Main Acrylic Glass Window
        graphics.fill(windowX, windowY, windowX + windowWidth, windowY + windowHeight, 0xEE0D1017); // Window body
        graphics.fill(windowX, windowY, windowX + windowWidth, windowY + 2, 0xFF3B82F6);           // Electric Blue top bar
        graphics.fill(windowX, windowY + windowHeight - 1, windowX + windowWidth, windowY + windowHeight, 0x22FFFFFF);
        graphics.fill(windowX, windowY, windowX + 1, windowY + windowHeight, 0x22FFFFFF);
        graphics.fill(windowX + windowWidth - 1, windowY, windowX + windowWidth, windowY + windowHeight, 0x22FFFFFF);

        // Header
        renderHeader(graphics, mouseX, mouseY);

        // Tab Bar
        renderTabBar(graphics, mouseX, mouseY);

        // Tab Content Area
        int contentY = windowY + 54;
        int contentHeight = windowHeight - 88;

        switch (currentTab) {
            case SCHEMATICS -> renderSchematicsTab(graphics, mouseX, mouseY, windowX + 12, contentY, windowWidth - 24, contentHeight);
            case POSITION -> renderPositionTab(graphics, mouseX, mouseY, windowX + 12, contentY, windowWidth - 24, contentHeight);
            case PRINTER_ENGINE -> renderPrinterEngineTab(graphics, mouseX, mouseY, windowX + 12, contentY, windowWidth - 24, contentHeight);
            case VISUALS -> renderVisualsTab(graphics, mouseX, mouseY, windowX + 12, contentY, windowWidth - 24, contentHeight);
        }

        // Footer Bar
        renderFooter(graphics, mouseX, mouseY);
    }

    private void renderHeader(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, "§b§l✦ XLRW §f§l3D-PRINTER", windowX + 14, windowY + 10, 0xFFFFFF, false);
        graphics.drawString(font, "§8Autonomous Schematic Printing & Material Refill", windowX + 14, windowY + 20, 0x94A3B8, false);

        BuilderConfig config = BuilderConfig.getInstance();
        int pillX = windowX + windowWidth - 100;
        int pillY = windowY + 9;
        boolean hoverPill = mouseX >= pillX && mouseX <= pillX + 88 && mouseY >= pillY && mouseY <= pillY + 16;

        if (config.enabled) {
            graphics.fill(pillX, pillY, pillX + 88, pillY + 16, hoverPill ? 0x4410B981 : 0x2A10B981);
            graphics.fill(pillX, pillY, pillX + 88, pillY + 1, 0xFF10B981);
            graphics.drawString(font, "§a● PRINT: ON", pillX + 10, pillY + 4, 0x10B981, false);
        } else {
            graphics.fill(pillX, pillY, pillX + 88, pillY + 16, hoverPill ? 0x44EF4444 : 0x2AEF4444);
            graphics.fill(pillX, pillY, pillX + 88, pillY + 1, 0xFFEF4444);
            graphics.drawString(font, "§c■ PRINT: OFF", pillX + 9, pillY + 4, 0xEF4444, false);
        }
    }

    private void renderTabBar(GuiGraphics graphics, int mouseX, int mouseY) {
        Tab[] tabs = Tab.values();
        String[] titles = {"📁 Schematics", "🎯 Position & Layer", "⚡ 3D Printer & Chest", "🔮 Visuals"};

        int tabY = windowY + 34;
        int tabWidth = (windowWidth - 24) / tabs.length;

        for (int i = 0; i < tabs.length; i++) {
            Tab tab = tabs[i];
            int tx = windowX + 12 + i * tabWidth;
            boolean active = (tab == currentTab);
            boolean hover = mouseX >= tx && mouseX < tx + tabWidth && mouseY >= tabY && mouseY < tabY + 16;

            int bg = active ? 0x333B82F6 : (hover ? 0x1AFFFFFF : 0x0AFFFFFF);
            graphics.fill(tx, tabY, tx + tabWidth - 2, tabY + 16, bg);

            if (active) {
                graphics.fill(tx, tabY + 14, tx + tabWidth - 2, tabY + 16, 0xFF3B82F6);
            }

            int textColor = active ? 0x60A5FA : (hover ? 0xFFFFFF : 0x94A3B8);
            graphics.drawCenteredString(font, titles[i], tx + tabWidth / 2, tabY + 4, textColor);
        }
    }

    private void renderSchematicsTab(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int h) {
        int listWidth = w * 44 / 100;
        int detailsWidth = w - listWidth - 10;
        int detailsX = x + listWidth + 10;

        // Left: File List
        graphics.fill(x, y, x + listWidth, y + h, 0x1AFFFFFF);
        graphics.drawString(font, "§7Files in /schematics", x + 8, y + 6, 0xCBD5E1, false);

        int itemY = y + 20;
        int itemH = 22;

        if (availableFiles == null || availableFiles.isEmpty()) {
            graphics.drawString(font, "§8No files found.", x + 8, itemY + 8, 0x64748B, false);
            graphics.drawString(font, "§8Put .litematic or .schem here", x + 8, itemY + 20, 0x64748B, false);
        } else {
            for (int i = 0; i < availableFiles.size() && itemY + itemH <= y + h - 24; i++) {
                File f = availableFiles.get(i);
                boolean selected = (i == selectedFileIndex);
                boolean hover = mouseX >= x + 4 && mouseX <= x + listWidth - 4 && mouseY >= itemY && mouseY < itemY + itemH - 2;

                int itemBg = selected ? 0x333B82F6 : (hover ? 0x22FFFFFF : 0x0DFFFFFF);
                graphics.fill(x + 4, itemY, x + listWidth - 4, itemY + itemH - 2, itemBg);

                if (selected) {
                    graphics.fill(x + 4, itemY, x + 6, itemY + itemH - 2, 0xFF3B82F6);
                }

                String fname = f.getName();
                if (fname.length() > 18) fname = fname.substring(0, 16) + "..";
                graphics.drawString(font, (selected ? "§f" : "§7") + fname, x + 10, itemY + 5, 0xFFFFFF, false);

                itemY += itemH;
            }
        }

        renderCustomButton(graphics, "↻ Refresh Folder", x + 4, y + h - 22, listWidth - 8, 18,
                mouseX, mouseY, 0xFF475569, 0xFF64748B);

        // Right: Details Card with Subtabs
        graphics.fill(detailsX, y, detailsX + detailsWidth, y + h, 0x1AFFFFFF);

        // Subtabs: [ ℹ Thông tin ] | [ 📦 Nguyên liệu ]
        int subTabW = (detailsWidth - 16) / 2;
        boolean infoActive = (schematicSubView == SchematicSubView.INFO);
        boolean matActive = (schematicSubView == SchematicSubView.MATERIALS);

        graphics.fill(detailsX + 8, y + 6, detailsX + 8 + subTabW, y + 22, infoActive ? 0xFF3B82F6 : 0x22FFFFFF);
        graphics.drawCenteredString(font, "ℹ Thông tin", detailsX + 8 + subTabW / 2, y + 10, infoActive ? 0xFFFFFF : 0x94A3B8);

        graphics.fill(detailsX + 8 + subTabW, y + 6, detailsX + detailsWidth - 8, y + 22, matActive ? 0xFF3B82F6 : 0x22FFFFFF);
        graphics.drawCenteredString(font, "📦 Nguyên liệu", detailsX + 8 + subTabW + subTabW / 2, y + 10, matActive ? 0xFFFFFF : 0x94A3B8);

        Schematic active = SchematicManager.getInstance().getActiveSchematic();

        if (schematicSubView == SchematicSubView.INFO) {
            if (availableFiles != null && !availableFiles.isEmpty() && selectedFileIndex < availableFiles.size()) {
                File sel = availableFiles.get(selectedFileIndex);
                graphics.drawString(font, "§f" + sel.getName(), detailsX + 8, y + 28, 0xFFFFFF, false);
                graphics.drawString(font, "§8Dung lượng: §7" + (sel.length() / 1024) + " KB", detailsX + 8, y + 40, 0x94A3B8, false);

                if (active != null && active.getName().equalsIgnoreCase(sel.getName())) {
                    graphics.drawString(font, "§a✔ Đang tải trong thế giới", detailsX + 8, y + 54, 0x10B981, false);
                    graphics.drawString(font, String.format("§8Kích thước: §e%dx%dx%d", active.getSize().getX(), active.getSize().getY(), active.getSize().getZ()), detailsX + 8, y + 66, 0xDDDDDD, false);
                    graphics.drawString(font, String.format("§8Tổng số khối: §f%d", active.getTotalBlocks()), detailsX + 8, y + 78, 0xDDDDDD, false);
                    graphics.drawString(font, String.format("§8Tiến độ: §a%d §8(%.1f%%)", active.getPlacedBlocks(), active.getProgressPercentage()), detailsX + 8, y + 90, 0x10B981, false);
                } else {
                    graphics.drawString(font, "§8Trạng thái: §7Chưa tải", detailsX + 8, y + 54, 0x94A3B8, false);
                }

                renderCustomButton(graphics, "✔ Load Vào Thế Giới", detailsX + 8, y + h - 46, detailsWidth - 16, 18,
                        mouseX, mouseY, 0xFF10B981, 0xFF059669);
                renderCustomButton(graphics, "✖ Hủy Tải Khỏi Thế Giới", detailsX + 8, y + h - 24, detailsWidth - 16, 18,
                        mouseX, mouseY, 0xFFEF4444, 0xFFDC2626);
            } else {
                graphics.drawString(font, "§8Chọn một file từ danh sách bên trái", detailsX + 8, y + 36, 0x64748B, false);
            }
        } else {
            // MATERIALS REPORT
            if (active == null) {
                graphics.drawString(font, "§8Chưa tải schematic vào thế giới.", detailsX + 8, y + 34, 0xEF4444, false);
                graphics.drawString(font, "§8Hãy bấm 'Load Vào Thế Giới' trước.", detailsX + 8, y + 48, 0x64748B, false);
            } else {
                if (materialReport == null) {
                    materialReport = MaterialReport.generate(minecraft, active);
                }

                String layerStatus = materialReport.isLayerReady() ? "§a✔ ĐỦ CHO TẦNG" : "§c✖ THIẾU TẦNG";
                String totalStatus = materialReport.isTotalReady() ? "§a✔ ĐỦ 100%" : "§6⚠ THIẾU TOÀN BỘ";
                graphics.drawString(font, "§8Tầng: " + layerStatus + " §8| Toàn bộ: " + totalStatus, detailsX + 8, y + 26, 0xFFFFFF, false);

                int pageSize = 4;
                List<MaterialReport.Entry> entries = materialReport.getEntries();
                int totalPages = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
                if (materialPage >= totalPages) materialPage = totalPages - 1;
                if (materialPage < 0) materialPage = 0;

                int rowY = y + 38;
                int rowH = 34;
                int startIndex = materialPage * pageSize;

                for (int i = 0; i < pageSize && startIndex + i < entries.size(); i++) {
                    MaterialReport.Entry entry = entries.get(startIndex + i);
                    int boxY = rowY + i * (rowH + 2);

                    graphics.fill(detailsX + 6, boxY, detailsX + detailsWidth - 6, boxY + rowH, 0x22000000);

                    String displayName = entry.name;
                    if (displayName.length() > 20) displayName = displayName.substring(0, 18) + "..";
                    graphics.drawString(font, "§f" + displayName, detailsX + 10, boxY + 3, 0xFFFFFF, false);

                    graphics.drawString(font, String.format("§8Cần: §e%d §8| Đã đặt: §a%d", entry.totalRequired, entry.placedCount), detailsX + 10, boxY + 13, 0xCBD5E1, false);
                    graphics.drawString(font, String.format("§8Túi: §b%d §8| Rương: §6%d", entry.inPlayerInv, entry.inChests), detailsX + 10, boxY + 23, 0xCBD5E1, false);

                    if (entry.isLayerReady() && entry.isTotalReady()) {
                        graphics.drawString(font, "§a✔ ĐỦ", detailsX + detailsWidth - 46, boxY + 12, 0x10B981, false);
                    } else if (entry.isLayerReady()) {
                        graphics.drawString(font, "§e✔ Đủ tầng", detailsX + detailsWidth - 58, boxY + 12, 0xFBBF24, false);
                    } else {
                        int def = Math.max(1, entry.missingOnCurrentLayer - entry.inPlayerInv);
                        graphics.drawString(font, "§c✖ Thiếu " + def, detailsX + detailsWidth - 65, boxY + 12, 0xEF4444, false);
                    }
                }

                // Pagination & refresh at bottom
                int botY = y + h - 22;
                renderCustomButton(graphics, "◀", detailsX + 8, botY, 24, 18, mouseX, mouseY, 0xFF334155, 0xFF475569);
                graphics.drawCenteredString(font, String.format("§7%d / %d", materialPage + 1, totalPages), detailsX + 8 + 24 + (detailsWidth - 16 - 110) / 2, botY + 5, 0xFFFFFF);
                renderCustomButton(graphics, "▶", detailsX + detailsWidth - 76, botY, 24, 18, mouseX, mouseY, 0xFF334155, 0xFF475569);
                renderCustomButton(graphics, "↻ Quét", detailsX + detailsWidth - 48, botY, 40, 18, mouseX, mouseY, 0xFF10B981, 0xFF059669);
            }
        }
    }

    private void renderPositionTab(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int h) {
        Schematic active = SchematicManager.getInstance().getActiveSchematic();
        BuilderConfig config = BuilderConfig.getInstance();

        int halfW = (w - 10) / 2;

        // Card 1: Anchor & 3D Coordinates
        graphics.fill(x, y, x + halfW, y + h, 0x1AFFFFFF);
        graphics.drawString(font, "§7Anchor & World Coordinates", x + 8, y + 6, 0xCBD5E1, false);

        if (active != null) {
            BlockPos anchor = active.getAnchor();
            graphics.drawString(font, String.format("§8Origin: §bX: §f%d §bY: §f%d §bZ: §f%d", anchor.getX(), anchor.getY(), anchor.getZ()), x + 8, y + 22, 0xFFFFFF, false);
            graphics.drawString(font, "§8Rotation: §e" + active.getRotation().name(), x + 8, y + 34, 0xFFFFFF, false);

            renderCustomButton(graphics, "⚓ Anchor to My Pos", x + 8, y + 48, halfW - 16, 18,
                    mouseX, mouseY, 0xFF3B82F6, 0xFF2563EB);

            renderCustomButton(graphics, "Rotate 90° ↻", x + 8, y + 70, halfW - 16, 18,
                    mouseX, mouseY, 0xFF8B5CF6, 0xFF7C3AED);

            // Nudge X
            graphics.drawString(font, "§7Nudge X:", x + 8, y + 98, 0xCBD5E1, false);
            renderCustomButton(graphics, "-1", x + 58, y + 94, 28, 16, mouseX, mouseY, 0xFF334155, 0xFF475569);
            renderCustomButton(graphics, "+1", x + 90, y + 94, 28, 16, mouseX, mouseY, 0xFF334155, 0xFF475569);

            // Nudge Y
            graphics.drawString(font, "§7Nudge Y:", x + 8, y + 118, 0xCBD5E1, false);
            renderCustomButton(graphics, "-1", x + 58, y + 114, 28, 16, mouseX, mouseY, 0xFF334155, 0xFF475569);
            renderCustomButton(graphics, "+1", x + 90, y + 114, 28, 16, mouseX, mouseY, 0xFF334155, 0xFF475569);

            // Nudge Z
            graphics.drawString(font, "§7Nudge Z:", x + 8, y + 138, 0xCBD5E1, false);
            renderCustomButton(graphics, "-1", x + 58, y + 134, 28, 16, mouseX, mouseY, 0xFF334155, 0xFF475569);
            renderCustomButton(graphics, "+1", x + 90, y + 134, 28, 16, mouseX, mouseY, 0xFF334155, 0xFF475569);
        } else {
            graphics.drawString(font, "§8Load a schematic first.", x + 8, y + 30, 0x64748B, false);
        }

        // Card 2: Layer Progression & Slicing
        int card2X = x + halfW + 10;
        graphics.fill(card2X, y, card2X + halfW, y + h, 0x1AFFFFFF);
        graphics.drawString(font, "§7Layer Slicing & Auto Advance", card2X + 8, y + 6, 0xCBD5E1, false);

        renderSwitchCard(graphics, "Layer Mode", "Build one floor at a time", config.layerMode, card2X + 8, y + 20, halfW - 16, mouseX, mouseY);
        renderSwitchCard(graphics, "Auto Layer Advance", "Auto next floor when layer finishes", config.autoLayer, card2X + 8, y + 52, halfW - 16, mouseX, mouseY);

        if (active != null) {
            int currentY = active.getMinY() + config.currentLayer;
            graphics.drawString(font, String.format("§8Active Layer: §e#%d §7(Y = %d)", config.currentLayer, currentY), card2X + 8, y + 90, 0xFFFFFF, false);

            renderCustomButton(graphics, "▼ Layer -1", card2X + 8, y + 104, (halfW - 20) / 2, 18, mouseX, mouseY, 0xFF334155, 0xFF475569);
            renderCustomButton(graphics, "▲ Layer +1", card2X + 8 + (halfW - 20) / 2 + 4, y + 104, (halfW - 20) / 2, 18, mouseX, mouseY, 0xFF334155, 0xFF475569);

            renderCustomButton(graphics, "Reset to Floor 0", card2X + 8, y + 126, halfW - 16, 18, mouseX, mouseY, 0xFF334155, 0xFF475569);
        }
    }

    private void renderPrinterEngineTab(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int h) {
        BuilderConfig config = BuilderConfig.getInstance();
        int halfW = (w - 10) / 2;

        // Left Column: 3D Printer Motion & Scaffolding
        graphics.fill(x, y, x + halfW, y + h, 0x1AFFFFFF);
        graphics.drawString(font, "§73D-Printer Nozzle & Elevator", x + 8, y + 6, 0xCBD5E1, false);

        renderSwitchCard(graphics, "Auto Move (Nozzle)", "Autonomous walking to missing blocks", config.autoMove, x + 8, y + 20, halfW - 16, mouseX, mouseY);
        renderSwitchCard(graphics, "Scaffolding Elevator", "Auto build/climb scaffolding pillars", config.useScaffolding, x + 8, y + 52, halfW - 16, mouseX, mouseY);
        renderSwitchCard(graphics, "Auto Demolish", "Tự đập block đặt sai bằng cúp/dụng cụ", config.autoDemolish, x + 8, y + 84, halfW - 16, mouseX, mouseY);

        BlockPos scPos = config.scaffoldPillarPos;
        String scText = scPos != null ? String.format("%d, %d, %d", scPos.getX(), scPos.getY(), scPos.getZ()) : "Auto Corner";
        graphics.drawString(font, "§8Pillar: §e" + scText, x + 8, y + 118, 0xCBD5E1, false);

        int scBtnW = (halfW - 20) / 2;
        renderCustomButton(graphics, "Set To My Pos", x + 8, y + 130, scBtnW, 16, mouseX, mouseY, 0xFF334155, 0xFF475569);
        renderCustomButton(graphics, "Auto Corner", x + 8 + scBtnW + 4, y + 130, scBtnW, 16, mouseX, mouseY, 0xFF334155, 0xFF475569);

        // Right Column: Multi-Chest Refill System
        int col2X = x + halfW + 10;
        graphics.fill(col2X, y, col2X + halfW, y + h, 0x1AFFFFFF);
        graphics.drawString(font, "§7Hệ thống Rương tiếp tế (Đa rương)", col2X + 8, y + 6, 0xCBD5E1, false);

        renderSwitchCard(graphics, "Tự động Refill", "Tự đi gom đồ khi hết nguyên liệu tầng", config.autoRefill, col2X + 8, y + 20, halfW - 16, mouseX, mouseY);

        // Multi-chest List Area
        List<BlockPos> chests = ChestManager.getInstance().getChestPositions();
        graphics.drawString(font, String.format("§8Danh sách rương (§e%d rương§8):", chests.size()), col2X + 8, y + 52, 0xCBD5E1, false);

        int chestBoxY = y + 64;
        int chestBoxH = 46;
        graphics.fill(col2X + 8, chestBoxY, col2X + halfW - 8, chestBoxY + chestBoxH, 0x22000000);

        if (chests.isEmpty()) {
            graphics.drawString(font, "§8Chưa chọn rương nào.", col2X + 14, chestBoxY + 8, 0xEF4444, false);
            graphics.drawString(font, "§8Nhìn vào rương & bấm 'Thêm rương'.", col2X + 14, chestBoxY + 20, 0x64748B, false);
        } else {
            int lineY = chestBoxY + 4;
            for (int i = 0; i < chests.size() && i < 3; i++) {
                BlockPos p = chests.get(i);
                String desc = ChestManager.getChestDescription(minecraft.level, p);
                String line = String.format("§f#%d: §7%d,%d,%d §8[§b%s§8]", (i + 1), p.getX(), p.getY(), p.getZ(), desc);
                graphics.drawString(font, line, col2X + 12, lineY, 0xFFFFFF, false);
                lineY += 13;
            }
            if (chests.size() > 3) {
                graphics.drawString(font, String.format("§8... và %d rương khác", chests.size() - 3), col2X + 12, lineY, 0x94A3B8, false);
            }
        }

        // Action Buttons: 2x2 grid
        int btnW = (halfW - 20) / 2;
        renderCustomButton(graphics, "➕ Thêm rương nhìn", col2X + 8, chestBoxY + chestBoxH + 4, btnW, 16, mouseX, mouseY, 0xFF10B981, 0xFF059669);
        renderCustomButton(graphics, "➕ Thêm vị trí đứng", col2X + 8 + btnW + 4, chestBoxY + chestBoxH + 4, btnW, 16, mouseX, mouseY, 0xFF334155, 0xFF475569);

        renderCustomButton(graphics, "➖ Xóa rương cuối", col2X + 8, chestBoxY + chestBoxH + 24, btnW, 16, mouseX, mouseY, 0xFF334155, 0xFF475569);
        renderCustomButton(graphics, "🗑 Xóa tất cả", col2X + 8 + btnW + 4, chestBoxY + chestBoxH + 24, btnW, 16, mouseX, mouseY, 0xFFEF4444, 0xFFDC2626);

        // Delay & Pacing
        graphics.drawString(font, "§8Tốc độ: §a" + config.placeDelayMs + "ms §8(±" + config.jitterMs + "ms)", col2X + 8, y + h - 18, 0xCBD5E1, false);
        renderCustomButton(graphics, "-20ms", col2X + halfW - 74, y + h - 20, 30, 14, mouseX, mouseY, 0xFF334155, 0xFF475569);
        renderCustomButton(graphics, "+20ms", col2X + halfW - 40, y + h - 20, 30, 14, mouseX, mouseY, 0xFF334155, 0xFF475569);
    }

    private void renderVisualsTab(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int h) {
        BuilderConfig config = BuilderConfig.getInstance();
        int halfW = (w - 10) / 2;

        graphics.fill(x, y, x + halfW, y + h, 0x1AFFFFFF);
        graphics.drawString(font, "§7Hologram & World Overlays", x + 8, y + 6, 0xCBD5E1, false);

        renderSwitchCard(graphics, "Hologram World", "Render 3D ghost blocks", config.renderHologram, x + 8, y + 20, halfW - 16, mouseX, mouseY);
        renderSwitchCard(graphics, "Target Box", "Highlight next block to place", config.renderTargetBox, x + 8, y + 54, halfW - 16, mouseX, mouseY);
        renderSwitchCard(graphics, "Bounding Box", "Outline schematic boundaries", config.renderBoundingBox, x + 8, y + 88, halfW - 16, mouseX, mouseY);

        int col2X = x + halfW + 10;
        graphics.fill(col2X, y, col2X + halfW, y + h, 0x1AFFFFFF);
        graphics.drawString(font, "§7Legit Pacing & Filters", col2X + 8, y + 6, 0xCBD5E1, false);

        renderSwitchCard(graphics, "Smooth Aim", "Simulate realistic mouse movement", config.smoothAim, col2X + 8, y + 20, halfW - 16, mouseX, mouseY);
        renderSwitchCard(graphics, "Strict Raycast", "No placing through walls / sight block", config.strictRaycast, col2X + 8, y + 54, halfW - 16, mouseX, mouseY);
        renderSwitchCard(graphics, "Only Active Layer", "Hide upper hologram floors", config.renderOnlyCurrentLayer, col2X + 8, y + 88, halfW - 16, mouseX, mouseY);
    }

    private void renderFooter(GuiGraphics graphics, int mouseX, int mouseY) {
        int footerY = windowY + windowHeight - 26;
        graphics.fill(windowX, footerY, windowX + windowWidth, windowY + windowHeight, 0x22FFFFFF);

        BuilderConfig config = BuilderConfig.getInstance();
        String masterText = config.enabled ? "§c■ STOP 3D-PRINTER (B)" : "§a▶ START 3D-PRINTER (B)";
        int masterBg = config.enabled ? 0xFFDC2626 : 0xFF10B981;
        renderCustomButton(graphics, masterText, windowX + 12, footerY + 4, 160, 18, mouseX, mouseY, masterBg, 0xFF3B82F6);

        renderCustomButton(graphics, "Step 1 Block (P)", windowX + 180, footerY + 4, 110, 18, mouseX, mouseY, 0xFF334155, 0xFF475569);
        renderCustomButton(graphics, "Done / Close", windowX + windowWidth - 90, footerY + 4, 78, 18, mouseX, mouseY, 0xFF1E293B, 0xFF334155);
    }

    private void renderSwitchCard(GuiGraphics graphics, String title, String desc, boolean state, int x, int y, int w, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 28;
        graphics.fill(x, y, x + w, y + 28, hover ? 0x26FFFFFF : 0x12FFFFFF);

        graphics.drawString(font, title, x + 6, y + 5, 0xFFFFFF, false);
        graphics.drawString(font, desc, x + 6, y + 16, 0x94A3B8, false);

        int pillX = x + w - 34;
        int pillY = y + 7;
        if (state) {
            graphics.fill(pillX, pillY, pillX + 28, pillY + 14, 0xFF10B981);
            graphics.fill(pillX + 14, pillY + 1, pillX + 27, pillY + 13, 0xFFFFFFFF);
        } else {
            graphics.fill(pillX, pillY, pillX + 28, pillY + 14, 0xFF334155);
            graphics.fill(pillX + 1, pillY + 1, pillX + 13, pillY + 13, 0xFF94A3B8);
        }
    }

    private void renderCustomButton(GuiGraphics graphics, String text, int x, int y, int w, int h, int mouseX, int mouseY, int normalColor, int hoverColor) {
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        graphics.fill(x, y, x + w, y + h, hover ? hoverColor : normalColor);
        graphics.drawCenteredString(font, text, x + w / 2, y + (h - 8) / 2, 0xFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        // Header Master Toggle click
        int pillX = windowX + windowWidth - 100;
        int pillY = windowY + 9;
        if (mouseX >= pillX && mouseX <= pillX + 88 && mouseY >= pillY && mouseY <= pillY + 16) {
            BuilderConfig.getInstance().enabled = !BuilderConfig.getInstance().enabled;
            BuilderConfig.save();
            return true;
        }

        // Tab selection
        Tab[] tabs = Tab.values();
        int tabY = windowY + 34;
        int tabWidth = (windowWidth - 24) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            int tx = windowX + 12 + i * tabWidth;
            if (mouseX >= tx && mouseX < tx + tabWidth && mouseY >= tabY && mouseY < tabY + 16) {
                this.currentTab = tabs[i];
                return true;
            }
        }

        int contentY = windowY + 54;
        int contentHeight = windowHeight - 88;
        int cx = windowX + 12;
        int cw = windowWidth - 24;

        BuilderConfig config = BuilderConfig.getInstance();
        Schematic active = SchematicManager.getInstance().getActiveSchematic();

        switch (currentTab) {
            case SCHEMATICS -> {
                int listWidth = cw * 44 / 100;
                int detailsWidth = cw - listWidth - 10;
                int detailsX = cx + listWidth + 10;

                // Subtab switch clicks: [ ℹ Thông tin ] | [ 📦 Nguyên liệu ]
                int subTabW = (detailsWidth - 16) / 2;
                if (mouseY >= contentY + 6 && mouseY <= contentY + 22) {
                    if (mouseX >= detailsX + 8 && mouseX <= detailsX + 8 + subTabW) {
                        schematicSubView = SchematicSubView.INFO;
                        return true;
                    }
                    if (mouseX >= detailsX + 8 + subTabW && mouseX <= detailsX + detailsWidth - 8) {
                        schematicSubView = SchematicSubView.MATERIALS;
                        materialReport = MaterialReport.generate(minecraft, active);
                        return true;
                    }
                }

                if (schematicSubView == SchematicSubView.INFO) {
                    int itemY = contentY + 20;
                    int itemH = 22;
                    if (availableFiles != null) {
                        for (int i = 0; i < availableFiles.size() && itemY + itemH <= contentY + contentHeight - 24; i++) {
                            if (mouseX >= cx + 4 && mouseX <= cx + listWidth - 4 && mouseY >= itemY && mouseY < itemY + itemH - 2) {
                                selectedFileIndex = i;
                                return true;
                            }
                            itemY += itemH;
                        }
                    }

                    if (mouseX >= cx + 4 && mouseX <= cx + listWidth - 4 && mouseY >= contentY + contentHeight - 22 && mouseY <= contentY + contentHeight - 4) {
                        refreshFiles();
                        return true;
                    }

                    if (availableFiles != null && !availableFiles.isEmpty() && selectedFileIndex < availableFiles.size()) {
                        if (mouseX >= detailsX + 8 && mouseX <= detailsX + detailsWidth - 8 && mouseY >= contentY + contentHeight - 46 && mouseY <= contentY + contentHeight - 28) {
                            SchematicManager.getInstance().loadFile(availableFiles.get(selectedFileIndex));
                            materialReport = null;
                            return true;
                        }
                        if (mouseX >= detailsX + 8 && mouseX <= detailsX + detailsWidth - 8 && mouseY >= contentY + contentHeight - 24 && mouseY <= contentY + contentHeight - 6) {
                            SchematicManager.getInstance().unload();
                            materialReport = null;
                            return true;
                        }
                    }
                } else {
                    // Materials pagination & refresh clicks
                    int botY = contentY + contentHeight - 22;
                    if (mouseY >= botY && mouseY <= botY + 18) {
                        if (mouseX >= detailsX + 8 && mouseX <= detailsX + 32) {
                            if (materialPage > 0) materialPage--;
                            return true;
                        }
                        if (mouseX >= detailsX + detailsWidth - 76 && mouseX <= detailsX + detailsWidth - 52) {
                            if (materialReport != null) {
                                int totalPages = Math.max(1, (materialReport.getEntries().size() + 3) / 4);
                                if (materialPage < totalPages - 1) materialPage++;
                            }
                            return true;
                        }
                        if (mouseX >= detailsX + detailsWidth - 48 && mouseX <= detailsX + detailsWidth - 8) {
                            materialReport = MaterialReport.generate(minecraft, active);
                            return true;
                        }
                    }
                }
            }

            case POSITION -> {
                int halfW = (cw - 10) / 2;
                if (active != null) {
                    if (mouseX >= cx + 8 && mouseX <= cx + halfW - 8 && mouseY >= contentY + 48 && mouseY <= contentY + 66) {
                        SchematicManager.getInstance().anchorToPlayer();
                        return true;
                    }
                    if (mouseX >= cx + 8 && mouseX <= cx + halfW - 8 && mouseY >= contentY + 70 && mouseY <= contentY + 88) {
                        SchematicManager.getInstance().rotate();
                        return true;
                    }
                    if (mouseY >= contentY + 94 && mouseY <= contentY + 110) {
                        if (mouseX >= cx + 58 && mouseX <= cx + 86) { SchematicManager.getInstance().moveAnchor(-1, 0, 0); return true; }
                        if (mouseX >= cx + 90 && mouseX <= cx + 118) { SchematicManager.getInstance().moveAnchor(1, 0, 0); return true; }
                    }
                    if (mouseY >= contentY + 114 && mouseY <= contentY + 130) {
                        if (mouseX >= cx + 58 && mouseX <= cx + 86) { SchematicManager.getInstance().moveAnchor(0, -1, 0); return true; }
                        if (mouseX >= cx + 90 && mouseX <= cx + 118) { SchematicManager.getInstance().moveAnchor(0, 1, 0); return true; }
                    }
                    if (mouseY >= contentY + 134 && mouseY <= contentY + 150) {
                        if (mouseX >= cx + 58 && mouseX <= cx + 86) { SchematicManager.getInstance().moveAnchor(0, 0, -1); return true; }
                        if (mouseX >= cx + 90 && mouseX <= cx + 118) { SchematicManager.getInstance().moveAnchor(0, 0, 1); return true; }
                    }
                }

                int card2X = cx + halfW + 10;
                if (mouseX >= card2X + 8 && mouseX <= card2X + halfW - 8 && mouseY >= contentY + 20 && mouseY <= contentY + 48) {
                    config.layerMode = !config.layerMode;
                    BuilderConfig.save();
                    return true;
                }
                if (mouseX >= card2X + 8 && mouseX <= card2X + halfW - 8 && mouseY >= contentY + 52 && mouseY <= contentY + 80) {
                    config.autoLayer = !config.autoLayer;
                    BuilderConfig.save();
                    return true;
                }
                if (active != null) {
                    int btnW = (halfW - 20) / 2;
                    if (mouseY >= contentY + 104 && mouseY <= contentY + 122) {
                        if (mouseX >= card2X + 8 && mouseX <= card2X + 8 + btnW) {
                            if (config.currentLayer > 0) config.currentLayer--;
                            BuilderConfig.save();
                            return true;
                        }
                        if (mouseX >= card2X + 8 + btnW + 4 && mouseX <= card2X + 8 + btnW * 2 + 4) {
                            config.currentLayer++;
                            BuilderConfig.save();
                            return true;
                        }
                    }
                    if (mouseX >= card2X + 8 && mouseX <= card2X + halfW - 8 && mouseY >= contentY + 126 && mouseY <= contentY + 144) {
                        config.currentLayer = 0;
                        BuilderConfig.save();
                        return true;
                    }
                }
            }

            case PRINTER_ENGINE -> {
                int halfW = (cw - 10) / 2;
                // Left Column: Motion, Scaffold & Demolish
                if (mouseX >= cx + 8 && mouseX <= cx + halfW - 8) {
                    if (mouseY >= contentY + 20 && mouseY <= contentY + 48) { config.autoMove = !config.autoMove; BuilderConfig.save(); return true; }
                    if (mouseY >= contentY + 52 && mouseY <= contentY + 80) { config.useScaffolding = !config.useScaffolding; BuilderConfig.save(); return true; }
                    if (mouseY >= contentY + 84 && mouseY <= contentY + 112) { config.autoDemolish = !config.autoDemolish; BuilderConfig.save(); return true; }

                    int scBtnW = (halfW - 20) / 2;
                    if (mouseY >= contentY + 130 && mouseY <= contentY + 146) {
                        if (mouseX <= cx + 8 + scBtnW) {
                            if (minecraft.player != null) {
                                ScaffoldManager.getInstance().setScaffoldPos(minecraft.player.blockPosition());
                            }
                        } else {
                            ScaffoldManager.getInstance().setScaffoldPos(null);
                        }
                        return true;
                    }
                }

                // Right Column: Multi-Chest Refill
                int col2X = cx + halfW + 10;
                int chestBoxY = contentY + 64;
                int chestBoxH = 46;
                int btnW = (halfW - 20) / 2;

                if (mouseX >= col2X + 8 && mouseX <= col2X + halfW - 8) {
                    if (mouseY >= contentY + 20 && mouseY <= contentY + 48) {
                        config.autoRefill = !config.autoRefill;
                        BuilderConfig.save();
                        return true;
                    }

                    // Row 1: ➕ Thêm rương nhìn / ➕ Thêm vị trí đứng
                    if (mouseY >= chestBoxY + chestBoxH + 4 && mouseY <= chestBoxY + chestBoxH + 20) {
                        if (mouseX <= col2X + 8 + btnW) {
                            if (minecraft.hitResult instanceof BlockHitResult bhr) {
                                BlockPos hit = bhr.getBlockPos();
                                ChestManager.getInstance().addChestPos(hit);
                                if (minecraft.player != null) {
                                    String desc = ChestManager.getChestDescription(minecraft.level, hit);
                                    minecraft.player.displayClientMessage(Component.literal("§b[XLRW] §aĐã thêm " + desc + " tại " + hit.toShortString()), true);
                                }
                            }
                            return true;
                        } else {
                            if (minecraft.player != null) {
                                BlockPos pos = minecraft.player.blockPosition();
                                ChestManager.getInstance().addChestPos(pos);
                                minecraft.player.displayClientMessage(Component.literal("§b[XLRW] §aĐã thêm vị trí rương tại " + pos.toShortString()), true);
                            }
                            return true;
                        }
                    }

                    // Row 2: ➖ Xóa rương cuối / 🗑 Xóa tất cả
                    if (mouseY >= chestBoxY + chestBoxH + 24 && mouseY <= chestBoxY + chestBoxH + 40) {
                        if (mouseX <= col2X + 8 + btnW) {
                            List<BlockPos> list = ChestManager.getInstance().getChestPositions();
                            if (!list.isEmpty()) {
                                BlockPos removed = list.get(list.size() - 1);
                                ChestManager.getInstance().removeChestPos(removed);
                                if (minecraft.player != null) {
                                    minecraft.player.displayClientMessage(Component.literal("§b[XLRW] §eĐã xóa rương tại " + removed.toShortString()), true);
                                }
                            }
                            return true;
                        } else {
                            ChestManager.getInstance().clearChestPositions();
                            if (minecraft.player != null) {
                                minecraft.player.displayClientMessage(Component.literal("§b[XLRW] §cĐã xóa toàn bộ danh sách rương tiếp tế."), true);
                            }
                            return true;
                        }
                    }

                    // Speed +/-
                    if (mouseY >= contentY + contentHeight - 20 && mouseY <= contentY + contentHeight - 6) {
                        if (mouseX >= col2X + halfW - 74 && mouseX <= col2X + halfW - 44) {
                            config.placeDelayMs = Math.max(50, config.placeDelayMs - 20);
                            BuilderConfig.save();
                            return true;
                        }
                        if (mouseX >= col2X + halfW - 40 && mouseX <= col2X + halfW - 10) {
                            config.placeDelayMs = Math.min(800, config.placeDelayMs + 20);
                            BuilderConfig.save();
                            return true;
                        }
                    }
                }
            }

            case VISUALS -> {
                int halfW = (cw - 10) / 2;
                if (mouseX >= cx + 8 && mouseX <= cx + halfW - 8) {
                    if (mouseY >= contentY + 20 && mouseY <= contentY + 48) { config.renderHologram = !config.renderHologram; BuilderConfig.save(); return true; }
                    if (mouseY >= contentY + 54 && mouseY <= contentY + 82) { config.renderTargetBox = !config.renderTargetBox; BuilderConfig.save(); return true; }
                    if (mouseY >= contentY + 88 && mouseY <= contentY + 116) { config.renderBoundingBox = !config.renderBoundingBox; BuilderConfig.save(); return true; }
                }
                int col2X = cx + halfW + 10;
                if (mouseX >= col2X + 8 && mouseX <= col2X + halfW - 8) {
                    if (mouseY >= contentY + 20 && mouseY <= contentY + 48) { config.smoothAim = !config.smoothAim; BuilderConfig.save(); return true; }
                    if (mouseY >= contentY + 54 && mouseY <= contentY + 82) { config.strictRaycast = !config.strictRaycast; BuilderConfig.save(); return true; }
                    if (mouseY >= contentY + 88 && mouseY <= contentY + 116) { config.renderOnlyCurrentLayer = !config.renderOnlyCurrentLayer; BuilderConfig.save(); return true; }
                }
            }
        }

        // Footer buttons
        int footerY = windowY + windowHeight - 26;
        if (mouseY >= footerY + 4 && mouseY <= footerY + 22) {
            // Master Toggle
            if (mouseX >= windowX + 12 && mouseX <= windowX + 172) {
                config.enabled = !config.enabled;
                BuilderConfig.save();
                return true;
            }
            // Step 1 Block
            if (mouseX >= windowX + 180 && mouseX <= windowX + 290) {
                BuilderController.getInstance().placeSingleStep(minecraft);
                return true;
            }
            // Close
            if (mouseX >= windowX + windowWidth - 90 && mouseX <= windowX + windowWidth - 12) {
                onClose();
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        BuilderConfig.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
