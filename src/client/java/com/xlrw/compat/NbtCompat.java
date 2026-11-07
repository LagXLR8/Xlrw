package com.xlrw.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.Set;

/** NBT / registry access that works on 1.21.1 (plain getters) and 1.21.11 (Optional getters). */
public final class NbtCompat {
    private NbtCompat() {}

    public static int getInt(CompoundTag t, String k) {
        //? if >=1.21.11 {
        /*return t.getIntOr(k, 0);
        *///?} else {
        return t.getInt(k);
        //?}
    }

    public static short getShort(CompoundTag t, String k) {
        //? if >=1.21.11 {
        /*return t.getShortOr(k, (short) 0);
        *///?} else {
        return t.getShort(k);
        //?}
    }

    public static String getString(CompoundTag t, String k) {
        //? if >=1.21.11 {
        /*return t.getStringOr(k, "");
        *///?} else {
        return t.getString(k);
        //?}
    }

    public static CompoundTag getCompound(CompoundTag t, String k) {
        //? if >=1.21.11 {
        /*return t.getCompoundOrEmpty(k);
        *///?} else {
        return t.getCompound(k);
        //?}
    }

    public static ListTag getList(CompoundTag t, String k, int elementType) {
        //? if >=1.21.11 {
        /*return t.getListOrEmpty(k);
        *///?} else {
        return t.getList(k, elementType);
        //?}
    }

    public static CompoundTag listCompound(ListTag l, int i) {
        //? if >=1.21.11 {
        /*return l.getCompoundOrEmpty(i);
        *///?} else {
        return l.getCompound(i);
        //?}
    }

    public static byte[] getByteArray(CompoundTag t, String k) {
        //? if >=1.21.11 {
        /*return t.getByteArray(k).orElse(new byte[0]);
        *///?} else {
        return t.getByteArray(k);
        //?}
    }

    public static long[] getLongArray(CompoundTag t, String k) {
        //? if >=1.21.11 {
        /*return t.getLongArray(k).orElse(new long[0]);
        *///?} else {
        return t.getLongArray(k);
        //?}
    }

    public static Set<String> keys(CompoundTag t) {
        //? if >=1.21.11 {
        /*return t.keySet();
        *///?} else {
        return t.getAllKeys();
        //?}
    }

    /** True if the key exists and holds a tag of the given type (Tag.TAG_*). */
    public static boolean has(CompoundTag t, String k, int type) {
        //? if >=1.21.11 {
        /*Tag v = t.get(k);
        return v != null && v.getId() == type;
        *///?} else {
        return t.contains(k, type);
        //?}
    }

    public static Block block(ResourceLocation id) {
        //? if >=1.21.11 {
        /*return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id);
        *///?} else {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
        //?}
    }
}
