package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

public final class CrystalLegit extends AliceModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> sync = sgGeneral.add(new BoolSetting.Builder()
        .name("sync")
        .description("cho server xac nhan truoc khi dat/pha")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> minCps = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-cps")
        .description("toc do pha cham nhat")
        .defaultValue(8.0)
        .min(1.0)
        .max(20.0)
        .sliderRange(1.0, 20.0)
        .build()
    );

    private final Setting<Double> maxCps = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-cps")
        .description("toc do pha nhanh nhat")
        .defaultValue(12.0)
        .min(1.0)
        .max(20.0)
        .sliderRange(1.0, 20.0)
        .build()
    );

    private final Setting<Boolean> placeObsidian = sgGeneral.add(new BoolSetting.Builder()
        .name("place-obsidian")
        .description("dat obsidian truoc khi ban khong nhin vao obsidian hoac bedrock")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> stopOnKill = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-on-kill")
        .description("tam dung khi co nguoi chet gan do de tranh lam no do")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoRefillCrystal = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-refill-crystal")
        .description("tu dong bom crystal vao hotbar")
        .defaultValue(false)
        .build()
    );

    private static final double LOOT_RADIUS = 10.0;
    private static final double CRYSTAL_RANGE_SQUARED = 25.0;
    private final Random random = new Random();

    private int breakDelayCounter;
    private int cooldownCounter;
    private int nextBreakDelay;

    private boolean useWasHeld;
    private long pausedUntil;

    private final GrimHotbarRefill refill = new GrimHotbarRefill(this);
    private int lastCrystalHotbarSlot = -1;

    public CrystalLegit() {
        super(AliceAddon.PVP_CATEGORY, "Crystal Macro", "dat va dap crystal khi ban giu chuot phai");
    }

    @Override
    public void onActivate() {
        super.onActivate();
        resetLocal();
        rollBreakDelay();
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();
        resetLocal();
    }

    private void resetLocal() {
        breakDelayCounter = 0;
        cooldownCounter = 0;
        useWasHeld = false;
        pausedUntil = 0L;
        refill.cancel();
        lastCrystalHotbarSlot = -1;
    }

    private void rollBreakDelay() {
        double min = minCps.get();
        double max = maxCps.get();
        if (min > max) {
            double swap = min;
            min = max;
            max = swap;
        }

        double cps = min + (max - min) * random.nextDouble();
        nextBreakDelay = Math.max(1, (int) (20.0 / cps));
    }

    @AliceHandler(priority = 100)
    private void onPlayerTickMovement(PlayerTickMovementEvent event) {
        refill.onPlayerTickMovement();
    }

    @AliceHandler(priority = -200)
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        refill.onSendMovementPacketsPost();
    }

    @AliceHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!stopOnKill.get()) return;
        if (!(event.packet instanceof EntityStatusS2CPacket packet)) return;
        if (packet.getStatus() != 3) return; // 3 is entity death status
        if (mc.world == null || mc.player == null) return;

        Entity dead = packet.getEntity(mc.world);
        if (dead == null || dead == mc.player) return;
        if (dead.distanceTo(mc.player) > 10.0) return;

        pausedUntil = System.currentTimeMillis() + 1500L;
    }

    @AliceHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.currentScreen != null) {
            refill.cancel();
            return;
        }

        tickCounters();

        if (stopOnKill.get() && System.currentTimeMillis() < pausedUntil) {
            refill.cancel();
            return;
        }
        if (mc.player.isUsingItem()) return;

        boolean useHeld = mc.options.useKey.isPressed();
        if (!useHeld) {
            useWasHeld = false;
            cooldownCounter = 0;
            refill.cancel();
            return;
        }

        if (!useWasHeld) {
            useWasHeld = true;
            cooldownCounter = 0;
        }

        int existingCrystalSlot = hotbarSlot(Items.END_CRYSTAL);
        if (existingCrystalSlot >= 0) {
            lastCrystalHotbarSlot = existingCrystalSlot;
        }

        if (autoRefillCrystal.get()) {
            int existing = existingCrystalSlot;
            if (existing >= 0) {
                refill.cancel();
            } else if (lastCrystalHotbarSlot >= 0) {
                GrimHotbarRefill.Result result = refill.request(
                    Items.END_CRYSTAL,
                    lastCrystalHotbarSlot
                );
                if (result == GrimHotbarRefill.Result.Waiting) return;
                if (result instanceof GrimHotbarRefill.Result.Ready ready) {
                    lastCrystalHotbarSlot = ready.hotbarSlot();
                    InvUtils.swap(ready.hotbarSlot(), false);
                }
            } else {
                refill.cancel();
            }
        } else {
            refill.cancel();
        }

        Hand hand = Hand.MAIN_HAND;
        if (mc.player.getOffHandStack().isOf(Items.END_CRYSTAL)) {
            hand = Hand.OFF_HAND;
        } else if (mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) {
            hand = Hand.MAIN_HAND;
        } else {
            return;
        }

        HitResult target = mc.crosshairTarget;
        
        // Attack logic
        if (target instanceof EntityHitResult entityHit) {
            boolean attacked = onEntity(entityHit.getEntity());
            if (attacked && sync.get()) return;
        } else if (!sync.get()) {
            // If sync is false, attack nearby crystals even if crosshair is on block (Ghost crystals)
            EndCrystalEntity nearest = findNearestCrystal();
            if (nearest != null) {
                onEntity(nearest);
                // We don't return here because sync is false and we want to spam place too!
            }
        }

        // Place logic
        if (target instanceof BlockHitResult blockHit) {
            onBlock(blockHit, hand);
        }
    }

    private EndCrystalEntity findNearestCrystal() {
        if (mc.world == null || mc.player == null) return null;
        EndCrystalEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal) || crystal.isRemoved()) continue;
            double distance = mc.player.squaredDistanceTo(crystal);
            if (distance <= CRYSTAL_RANGE_SQUARED && distance < nearestDistance) {
                nearest = crystal;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void tickCounters() {
        if (breakDelayCounter > 0) breakDelayCounter--;
        if (cooldownCounter > 0) cooldownCounter--;
    }

    private void onBlock(BlockHitResult blockHit, Hand crystalHand) {
        if (blockHit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = blockHit.getBlockPos();
        BlockState state = mc.world.getBlockState(pos);
        boolean solidEnough = state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK);

        if (!solidEnough) {
            if (placeObsidian.get() && cooldownCounter <= 0) {
                startAndFinishObsidian(blockHit, crystalHand);
            }
            return;
        }

        if (!canPlaceCrystal(pos)) return;
        if (lootNearby(pos.up())) return;

        mc.interactionManager.interactBlock(mc.player, crystalHand, blockHit);
        mc.player.swingHand(crystalHand);
    }

    private void startAndFinishObsidian(BlockHitResult blockHit, Hand crystalHand) {
        if (mc.player == null || mc.world == null) return;

        int slot = hotbarSlot(Items.OBSIDIAN);
        if (slot == -1) return;

        InvUtils.swap(slot, false);
        
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, blockHit);
        mc.player.swingHand(Hand.MAIN_HAND);

        if (crystalHand == Hand.MAIN_HAND) {
            int crystal = hotbarSlot(Items.END_CRYSTAL);
            if (crystal != -1) InvUtils.swap(crystal, false);
        }

        BlockPos obbyPos = blockHit.getBlockPos().offset(blockHit.getSide());
        if (canPlaceCrystal(obbyPos) && !lootNearby(obbyPos.up())) {
            BlockHitResult crystalHit = new BlockHitResult(
                new Vec3d(obbyPos.getX() + 0.5, obbyPos.getY() + 1.0, obbyPos.getZ() + 0.5),
                Direction.UP,
                obbyPos,
                false
            );
            mc.interactionManager.interactBlock(mc.player, crystalHand, crystalHit);
            mc.player.swingHand(crystalHand);
        }

        cooldownCounter = 6;
    }

    private boolean onEntity(Entity entity) {
        if (breakDelayCounter > 0) return false;
        if (mc.player == null || mc.interactionManager == null) return false;

        if (entity == null || entity.isRemoved() || !entity.isAlive()) return false;
        if (!(entity instanceof EndCrystalEntity) && !(entity instanceof SlimeEntity)) return false;
        if (lootNearby(entity.getBlockPos())) return false;

        mc.interactionManager.attackEntity(mc.player, entity);
        mc.player.swingHand(Hand.MAIN_HAND);

        breakDelayCounter = nextBreakDelay;
        rollBreakDelay();
        return true;
    }

    private boolean canPlaceCrystal(BlockPos pos) {
        if (mc.world == null) return false;

        BlockPos above = pos.up();
        if (!mc.world.isAir(above)) return false;
        
        if (!sync.get()) return true;

        int x = above.getX(), y = above.getY(), z = above.getZ();
        return mc.world.getOtherEntities(null, new Box(x, y, z, x + 1.0, y + 2.0, z + 1.0)).isEmpty();
    }

    private boolean lootNearby(BlockPos pos) {
        if (mc.world == null || pos == null) return false;

        Box area = new Box(
            pos.getX() - LOOT_RADIUS, pos.getY() - LOOT_RADIUS, pos.getZ() - LOOT_RADIUS,
            pos.getX() + LOOT_RADIUS, pos.getY() + LOOT_RADIUS, pos.getZ() + LOOT_RADIUS
        );

        for (Entity entity : mc.world.getOtherEntities(null, area)) {
            if (!(entity instanceof ItemEntity item)) continue;

            ItemStack stack = item.getStack();
            if (stack.isEmpty()) continue;

            if (stack.isOf(Items.ELYTRA)) return true;
            if (stack.isIn(ItemTags.SWORDS)) return true;
            if (stack.isIn(ItemTags.HEAD_ARMOR) || stack.isIn(ItemTags.CHEST_ARMOR)
                || stack.isIn(ItemTags.LEG_ARMOR) || stack.isIn(ItemTags.FOOT_ARMOR)) return true;
        }

        return false;
    }

    private int hotbarSlot(net.minecraft.item.Item item) {
        if (mc.player == null) return -1;

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }
}
