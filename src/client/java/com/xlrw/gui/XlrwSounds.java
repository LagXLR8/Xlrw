package com.xlrw.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Custom UI / mascot sounds (assets/xlrw/sounds/*.ogg, listed in assets/xlrw/sounds.json). The events are only played
 * client-side, so they do not need to be registered.
 */
public final class XlrwSounds {
    private static final Map<String, SoundEvent> CACHE = new HashMap<>();
    private static final java.util.Random RND = new java.util.Random();

    private XlrwSounds() {}

    public static void play(String id, float pitch, float volume) {
        try {
            SoundEvent ev = CACHE.computeIfAbsent(id, k -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("xlrw", k)));
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev, pitch, volume));
        } catch (Throwable ignored) {
        }
    }

    /** Pitch varied a little so repeated sounds do not feel mechanical. */
    public static void playVar(String id, float pitch, float volume, float spread) {
        play(id, pitch + (RND.nextFloat() * 2 - 1) * spread, volume);
    }
}
