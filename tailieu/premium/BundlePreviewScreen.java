package com.nnpg.alice.premium;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

final class BundlePreviewScreen extends Screen {
    private static final int COLUMNS = 9;
    private static final int SLOT_SIZE = 18;
    private static final int PADDING = 8;
    private static final int TITLE_HEIGHT = 22;
    private static final int FOOTER_HEIGHT = 16;
    private static final int PANEL_WIDTH = PADDING * 2 + COLUMNS * SLOT_SIZE;

    private final BundlePreview owner;
    private final Screen parent;
    private final List<ItemStack> items;

    private ItemStack hoveredStack = ItemStack.EMPTY;

    BundlePreviewScreen(BundlePreview owner, ItemStack bundle, Screen parent) {
        super(Text.literal("Bundle Contents"));
        this.owner = owner;
        this.parent = parent;
        this.items = readContents(bundle);
    }

    private static List<ItemStack> readContents(ItemStack bundle) {
        BundleContentsComponent contents = bundle.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (contents == null || contents.isEmpty()) return List.of();

        List<ItemStack> result = new ArrayList<>(contents.size());
        contents.stream().map(ItemStack::copy).forEach(result::add);
        return List.copyOf(result);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int rows = Math.max(1, (items.size() + COLUMNS - 1) / COLUMNS);
        int panelHeight = PADDING * 2 + TITLE_HEIGHT + rows * SLOT_SIZE + FOOTER_HEIGHT;
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - panelHeight) / 2;

        context.fill(left, top, left + PANEL_WIDTH, top + panelHeight, 0xE0101010);
        context.fill(left, top, left + PANEL_WIDTH, top + 2, 0xFF55FFAA);
        context.drawCenteredTextWithShadow(
            textRenderer,
            title,
            width / 2,
            top + PADDING,
            0xFFFFFFFF
        );

        hoveredStack = ItemStack.EMPTY;
        int gridTop = top + PADDING + TITLE_HEIGHT;

        if (items.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal("Bundle is empty").formatted(Formatting.GRAY),
                width / 2,
                gridTop + 5,
                0xFFAAAAAA
            );
        } else {
            for (int index = 0; index < items.size(); index++) {
                int x = left + PADDING + (index % COLUMNS) * SLOT_SIZE;
                int y = gridTop + (index / COLUMNS) * SLOT_SIZE;
                ItemStack stack = items.get(index);

                context.fill(x, y, x + 18, y + 18, 0xFF8B8B8B);
                context.fill(x + 1, y + 1, x + 17, y + 17, 0xFF373737);
                context.drawItem(stack, x + 1, y + 1);
                context.drawStackOverlay(textRenderer, stack, x + 1, y + 1);

                if (
                    mouseX >= x
                        && mouseX < x + SLOT_SIZE
                        && mouseY >= y
                        && mouseY < y + SLOT_SIZE
                ) {
                    context.fill(x, y, x + 18, y + 18, 0x66FFFFFF);
                    hoveredStack = stack;
                }
            }
        }

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.literal("Press " + owner.activeKeyLabel() + " on a nested Bundle")
                .formatted(Formatting.DARK_GRAY),
            width / 2,
            top + panelHeight - FOOTER_HEIGHT + 3,
            0xFF777777
        );

        super.render(context, mouseX, mouseY, delta);

        if (!hoveredStack.isEmpty()) {
            List<Text> tooltip = new ArrayList<>(Screen.getTooltipFromItem(client, hoveredStack));
            if (hoveredStack.getItem() instanceof BundleItem) {
                tooltip.add(
                    Text.literal("Press " + owner.activeKeyLabel() + " to open")
                        .formatted(Formatting.GRAY)
                );
            }
            context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
        }
    }

    boolean openHoveredBundle() {
        if (!(hoveredStack.getItem() instanceof BundleItem) || client == null) return false;

        client.setScreen(new BundlePreviewScreen(owner, hoveredStack, this));
        return true;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
