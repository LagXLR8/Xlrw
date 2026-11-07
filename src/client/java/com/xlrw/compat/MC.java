package com.xlrw.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Small game-API differences between 1.21.1 and 1.21.11 (logic side). */
public final class MC {
    private MC() {}

    public static Component itemName(Item item) {
        //? if >=1.21.11 {
        /*return item.getName();
        *///?} else {
        return item.getDescription();
        //?}
    }

    /** Rotation-only movement packet. */
    public static ServerboundMovePlayerPacket.Rot rot(float yaw, float pitch, boolean onGround) {
        //? if >=1.21.11 {
        /*LocalPlayer p = Minecraft.getInstance().player;
        return new ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround, p != null && p.horizontalCollision);
        *///?} else {
        return new ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround);
        //?}
    }

    /** Tells the server we started / stopped sneaking. */
    public static Packet<?> sneakPacket(LocalPlayer player, boolean sneak) {
        //? if >=1.21.11 {
        /*net.minecraft.world.entity.player.Input i = player.input != null ? player.input.keyPresses : net.minecraft.world.entity.player.Input.EMPTY;
        return new net.minecraft.network.protocol.game.ServerboundPlayerInputPacket(
                new net.minecraft.world.entity.player.Input(i.forward(), i.backward(), i.left(), i.right(), i.jump(), sneak, i.sprint()));
        *///?} else {
        return new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(player, sneak
                ? net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                : net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY);
        //?}
    }

    public static Direction nearestDir(double x, double y, double z) {
        //? if >=1.21.11 {
        /*return Direction.getApproximateNearest(x, y, z);
        *///?} else {
        return Direction.getNearest(x, y, z);
        //?}
    }

    /** Direction for an axis-aligned unit offset, null otherwise. */
    public static Direction dirFromDelta(int dx, int dy, int dz) {
        //? if >=1.21.11 {
        /*return Direction.getNearest(dx, dy, dz, null);
        *///?} else {
        return Direction.fromDelta(dx, dy, dz);
        //?}
    }

    public static int minY(Level level) {
        //? if >=1.21.11 {
        /*return level.getMinY();
        *///?} else {
        return level.getMinBuildHeight();
        //?}
    }

    /** First Y above the build limit (exclusive). */
    public static int maxYExclusive(Level level) {
        //? if >=1.21.11 {
        /*return level.getMaxY() + 1;
        *///?} else {
        return level.getMaxBuildHeight();
        //?}
    }

    public static int selected(Inventory inv) {
        //? if >=1.21.11 {
        /*return inv.getSelectedSlot();
        *///?} else {
        return inv.selected;
        //?}
    }

    public static void setSelected(Inventory inv, int slot) {
        //? if >=1.21.11 {
        /*inv.setSelectedSlot(slot);
        *///?} else {
        inv.selected = slot;
        //?}
    }

    /** The 36 main inventory slots (hotbar + storage). */
    public static NonNullList<ItemStack> mainItems(Inventory inv) {
        //? if >=1.21.11 {
        /*return inv.getNonEquipmentItems();
        *///?} else {
        return inv.items;
        //?}
    }

    public static boolean onCooldown(LocalPlayer player, Item item) {
        //? if >=1.21.11 {
        /*return player.getCooldowns().isOnCooldown(new ItemStack(item));
        *///?} else {
        return player.getCooldowns().isOnCooldown(item);
        //?}
    }
}
