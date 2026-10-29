package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;

import java.util.HashSet;
import java.util.Set;

public final class CrystalOptimazer extends AliceModule {
    private final Set<Integer> pendingCrystalIds = new HashSet<>();

    public CrystalOptimazer() {
        super(
            AliceAddon.PVP_CATEGORY,
            "Crystal Optimazer",
            "Removes attacked End Crystals client-side without waiting for the server destroy packet."
        );
    }

    @Override
    public void onDeactivate() {
        pendingCrystalIds.clear();
        super.onDeactivate();
    }

    @AliceHandler(priority = 100)
    private void onAttack(AttackEntityEvent event) {
        if (event.entity instanceof EndCrystalEntity crystal) {
            pendingCrystalIds.add(crystal.getId());
        }
    }

    @AliceHandler(priority = -100)
    private void onTick(TickEvent.Post event) {
        if (pendingCrystalIds.isEmpty()) return;

        if (mc.world == null) {
            pendingCrystalIds.clear();
            return;
        }

        for (int entityId : pendingCrystalIds) {
            Entity entity = mc.world.getEntityById(entityId);
            if (entity instanceof EndCrystalEntity && !entity.isRemoved()) {
                mc.world.removeEntity(entityId, Entity.RemovalReason.KILLED);
            }
        }

        pendingCrystalIds.clear();
    }
}
