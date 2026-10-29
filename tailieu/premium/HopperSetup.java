package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import net.minecraft.client.gui.screen.ingame.HopperScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.HopperScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

public class HopperSetup extends AliceModule {
    private static final int HOPPER_SLOT_COUNT = 5;
    private static final int PLAYER_INVENTORY_SIZE = 36;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<TriggerMode> mode = sgGeneral.add(new EnumSetting.Builder<TriggerMode>()
        .name("trigger-mode")
        .description("chon tu dong setup khi mo hoac dung phim tat de setup thu cong")
        .defaultValue(TriggerMode.Auto)
        .build());

    private final Setting<Keybind> fillKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("setup-key")
        .description("ap dung layout hopper da cau hinh khi mo GUI")
        .visible(() -> mode.get() == TriggerMode.Keybind)
        .build());

    private final Setting<Item> item1 = itemSetting(1);
    private final Setting<Integer> count1 = countSetting(1);
    private final Setting<Item> item2 = itemSetting(2);
    private final Setting<Integer> count2 = countSetting(2);
    private final Setting<Item> item3 = itemSetting(3);
    private final Setting<Integer> count3 = countSetting(3);
    private final Setting<Item> item4 = itemSetting(4);
    private final Setting<Integer> count4 = countSetting(4);
    private final Setting<Item> item5 = itemSetting(5);
    private final Setting<Integer> count5 = countSetting(5);

    private boolean pendingAutomaticSetup;
    private boolean keyWasPressed;

    public HopperSetup() {
        super(
            AliceAddon.CATEGORY,
            "HopperSetup",
            "Builds a saved five-slot hopper layout from items in your inventory."
        );
    }

    @Override
    public void onActivate() {
        super.onActivate();
        pendingAutomaticSetup = false;
        keyWasPressed = false;
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();
        pendingAutomaticSetup = false;
        keyWasPressed = false;
    }

    @AliceHandler
    public void onOpenScreen(OpenScreenEvent event) {
        if (!isActive() || mode.get() != TriggerMode.Auto) return;
        if (event.screen instanceof HopperScreen) pendingAutomaticSetup = true;
    }

    @AliceHandler
    public void onTick(TickEvent.Pre event) {
        if (!isActive() || mc.player == null || mc.interactionManager == null) return;
        if (!(mc.player.currentScreenHandler instanceof HopperScreenHandler handler)) {
            pendingAutomaticSetup = false;
            keyWasPressed = false;
            return;
        }

        if (mode.get() == TriggerMode.Auto) {
            if (!pendingAutomaticSetup) return;
            pendingAutomaticSetup = false;
            applyLayout(handler);
            return;
        }

        boolean pressed = fillKey.get().isPressed();
        if (pressed && !keyWasPressed) applyLayout(handler);
        keyWasPressed = pressed;
    }

    private void applyLayout(HopperScreenHandler handler) {
        if (!handler.getCursorStack().isEmpty()) {
            info("Clear the cursor before applying HopperSetup.");
            return;
        }

        Item[] items = {item1.get(), item2.get(), item3.get(), item4.get(), item5.get()};
        int[] counts = {count1.get(), count2.get(), count3.get(), count4.get(), count5.get()};

        for (int hopperSlot = 0; hopperSlot < HOPPER_SLOT_COUNT; hopperSlot++) {
            Item item = items[hopperSlot];
            if (item == null || item == Items.AIR) continue;
            fillSlot(handler, hopperSlot, item, counts[hopperSlot]);
        }
    }

    private void fillSlot(HopperScreenHandler handler, int hopperSlot, Item item, int amount) {
        ItemStack destination = handler.getSlot(hopperSlot).getStack();
        int remaining = SetupModuleLogic.requiredTransfer(
            destination.isEmpty(),
            destination.isOf(item),
            destination.getCount(),
            amount,
            item.getMaxCount()
        );
        if (remaining <= 0) return;

        for (int inventoryIndex = 0;
             inventoryIndex < PLAYER_INVENTORY_SIZE && remaining > 0;
             inventoryIndex++) {
            ItemStack source = mc.player.getInventory().getStack(inventoryIndex);
            if (!source.isOf(item)) continue;

            int sourceSlotId = findPlayerSlotId(handler, inventoryIndex);
            if (sourceSlotId < 0) continue;

            int sourceCount = source.getCount();
            int transferred = Math.min(sourceCount, remaining);
            click(handler, sourceSlotId, 0);

            if (transferred == sourceCount) {
                click(handler, hopperSlot, 0);
                if (!handler.getCursorStack().isEmpty()) click(handler, sourceSlotId, 0);
            } else {
                for (int unit = 0; unit < transferred; unit++) {
                    click(handler, hopperSlot, 1);
                }
                click(handler, sourceSlotId, 0);
            }

            remaining -= transferred;
        }
    }

    private int findPlayerSlotId(HopperScreenHandler handler, int inventoryIndex) {
        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory() && slot.getIndex() == inventoryIndex) {
                return slot.id;
            }
        }
        return -1;
    }

    private void click(HopperScreenHandler handler, int slotId, int button) {
        mc.interactionManager.clickSlot(
            handler.syncId,
            slotId,
            button,
            SlotActionType.PICKUP,
            mc.player
        );
    }

    private Setting<Item> itemSetting(int slot) {
        return sgGeneral.add(new ItemSetting.Builder()
            .name("hopper-slot-" + slot)
            .description("item duoc chon cho slot " + slot + ".")
            .defaultValue(Items.AIR)
            .build());
    }

    private Setting<Integer> countSetting(int slot) {
        return sgGeneral.add(new IntSetting.Builder()
            .name("slot-" + slot + "-amount")
            .description("so luong muc tieu cho hopper slot " + slot + ".")
            .defaultValue(1)
            .min(1)
            .max(64)
            .sliderMax(64)
            .build());
    }

    public enum TriggerMode {
        Auto,
        Keybind
    }
}
