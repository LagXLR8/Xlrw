package com.nnpg.alice.premium;

import com.nnpg.alice.AliceAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import meteordevelopment.meteorclient.utils.player.InvUtils;

import java.util.LinkedList;
import java.util.Queue;

public class AnchorMacro extends AliceModule
    implements GrimCombatCoordinator.Participant {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Keybind> activateKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("activate-key")
        .description("Key that activates the anchor macro.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Boolean> safeTotem = sgGeneral.add(new BoolSetting.Builder()
        .name("safe-totem")
        .description("doi sang totem khi bi no")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> totemSlot = sgGeneral.add(new IntSetting.Builder()
        .name("totem-slot")
        .description("slot hotbar (1-9) de doi item gay no")
        .defaultValue(1)
        .min(1)
        .max(9)
        .sliderMax(9)
        .visible(safeTotem::get)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("thoi gian tre (tick) sau khi dat anchor. (Tang len neu bi ghost block)")
        .defaultValue(0)
        .min(0).max(10).sliderMax(10)
        .build()
    );

    private final Setting<Integer> chargeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("charge-delay")
        .description("thoi gian tre (tick) sau khi sac anchor")
        .defaultValue(0)
        .min(0).max(10).sliderMax(10)
        .build()
    );

    private final Setting<Boolean> safeMacro = sgGeneral.add(new BoolSetting.Builder()
        .name("safe-macro")
        .description("dat glowstone ke ben anchor de giam sat thuong no")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> safeMacroDelay = sgGeneral.add(new IntSetting.Builder()
        .name("safe-macro-delay")
        .description("thoi gian tre (tick) sau khi dat safe block truoc khi no")
        .defaultValue(1)
        .min(0).max(10).sliderMax(10)
        .visible(safeMacro::get)
        .build()
    );

    private final Setting<Boolean> useMacroDelay = sgGeneral.add(new BoolSetting.Builder()
        .name("use-macro-delay")
        .description("bat thoi gian tre macro giua cac vong lap")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> macroDelay = sgGeneral.add(new IntSetting.Builder()
        .name("macro-delay")
        .description("thoi gian tre (tick) giua cac vong lap macro")
        .defaultValue(2)
        .min(0)
        .max(20)
        .sliderMax(20)
        .visible(useMacroDelay::get)
        .build()
    );

    private BlockPos lastExplodedPos = null;
    private int ticksSinceExplosion = 100;
    private int loopDelayCounter = 0;

    private static class ActionItem {
        Runnable action;
        int delay;

        ActionItem(Runnable action, int delay) {
            this.action = action;
            this.delay = delay;
        }
    }

    private final Queue<ActionItem> actionQueue = new LinkedList<>();
    private int actionDelayCounter = 0;

    public AnchorMacro() {
        super(AliceAddon.PVP_CATEGORY, "anchor-macro", "macro anchor 1 tick siêu toc");
    }

    @Override
    public void onActivate() {
        super.onActivate();
        GrimCombatCoordinator.activate(this);
        this.lastExplodedPos = null;
        this.ticksSinceExplosion = 100;
        this.loopDelayCounter = 0;
        this.actionQueue.clear();
        this.actionDelayCounter = 0;
    }

    @Override
    public void onDeactivate() {
        GrimCombatCoordinator.deactivate(this);
        super.onDeactivate();
        this.lastExplodedPos = null;
        this.ticksSinceExplosion = 100;
        this.loopDelayCounter = 0;
        this.actionQueue.clear();
        this.actionDelayCounter = 0;
    }

    @AliceHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) {
            return;
        }

        ticksSinceExplosion++;

        if (!actionQueue.isEmpty() || actionDelayCounter > 0) {
            mc.options.useKey.setPressed(false);
        }

        if (actionDelayCounter > 0) {
            actionDelayCounter--;
            return; // Wait until the coordinator can own the next packet epoch.
        }

        if (loopDelayCounter > 0) {
            loopDelayCounter--;
            return;
        }

        boolean keyPressed = activateKey.get().isPressed();

        // Only start a new sequence if the queue is fully empty
        if (keyPressed && actionQueue.isEmpty()) {
            mc.options.useKey.setPressed(false);

            HitResult hitResult = mc.crosshairTarget;
            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
                BlockHitResult bhr = (BlockHitResult) hitResult;
                
                if (mc.player.getEyePos().distanceTo(bhr.getPos()) <= 4.5) {
                    executeSequence(bhr);
                }
            }
        }
    }

    /**
     * Anchor actions are submitted to the same epoch arbiter as Crystal Aura++.
     * This keeps a place/charge/explode packet atomic while still allowing the
     * two modules to alternate instead of one module cancelling the other.
     */
    @Override
    public GrimCombatCoordinator.Intent prepareCombatIntent() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null
            || mc.interactionManager == null
            || actionQueue.isEmpty() || actionDelayCounter > 0
            || !GrimCombatCoordinator.serverLookKnown()
            || !GrimCombatCoordinator.sameLook(
                GrimCombatCoordinator.serverYaw(),
                GrimCombatCoordinator.serverPitch(),
                mc.player.getYaw(),
                mc.player.getPitch()
            )) {
            return null;
        }

        return new GrimCombatCoordinator.Intent() {
            @Override
            public GrimCombatCoordinator.Participant owner() {
                return AnchorMacro.this;
            }

            @Override
            public void execute() {
                ActionItem next = actionQueue.poll();
                if (next == null) return;
                next.action.run();
                actionDelayCounter = next.delay;
            }
        };
    }

    @Override
    public void suspendForItemUse() {
        // A visible user use must win the epoch. Drop the pending macro step;
        // the next key press can start a fresh, correctly aimed sequence.
        actionQueue.clear();
        actionDelayCounter = 0;
    }

    @Override
    public int combatPriority() {
        return 100;
    }

    private void executeSequence(BlockHitResult bhr) {
        BlockPos targetPos = bhr.getBlockPos();
        BlockState state = mc.world.getBlockState(targetPos);
        boolean isReplaceable = state.isReplaceable();

        boolean isAnchor = state.isOf(Blocks.RESPAWN_ANCHOR);
        boolean isTargetingOldAnchor = targetPos.equals(lastExplodedPos);
        boolean shouldPlace = !isAnchor || isTargetingOldAnchor;

        BlockPos newAnchorPos;
        if (isAnchor) {
            newAnchorPos = targetPos;
        } else if (isReplaceable) {
            newAnchorPos = targetPos;
        } else {
            newAnchorPos = targetPos.offset(bhr.getSide());
        }

        if (shouldPlace) {
            if (newAnchorPos.equals(lastExplodedPos)) {
                if (ticksSinceExplosion < 2) { 
                    return; 
                }
            } else {
                if (!mc.world.getBlockState(newAnchorPos).isReplaceable()) {
                    return;
                }
            }
        }

        BlockHitResult interactTarget;
        if (shouldPlace) {
            interactTarget = new BlockHitResult(bhr.getPos(), bhr.getSide(), newAnchorPos, false);
        } else {
            interactTarget = bhr;
        }

        actionQueue.clear();
        actionDelayCounter = 0;

        if (shouldPlace) {
            BlockHitResult finalBhr = bhr;
            actionQueue.add(new ActionItem(() -> {
                if (swapToItem(Items.RESPAWN_ANCHOR)) {
                    interactBlock(finalBhr);
                }
            }, placeDelay.get()));
        }

        actionQueue.add(new ActionItem(() -> {
            if (swapToItem(Items.GLOWSTONE)) {
                interactBlock(interactTarget);
            }
        }, chargeDelay.get()));

        if (safeMacro.get()) {
            actionQueue.add(new ActionItem(() -> {
                placeSafeBlock(newAnchorPos);
            }, safeMacroDelay.get()));
        }

        BlockPos finalTargetPos = targetPos;
        actionQueue.add(new ActionItem(() -> {
            explodeAnchor(newAnchorPos);
            lastExplodedPos = shouldPlace ? newAnchorPos : finalTargetPos;
            ticksSinceExplosion = 0;
            if (useMacroDelay.get()) {
                loopDelayCounter = macroDelay.get();
            }
        }, 0));
    }

    private void placeSafeBlock(BlockPos anchorPos) {
        if (!swapToItem(Items.GLOWSTONE)) return;

        Direction playerDir = mc.player.getHorizontalFacing().getOpposite();
        BlockPos safeBlockPos = anchorPos.offset(playerDir);

        if (!mc.world.getBlockState(safeBlockPos).isReplaceable()) {
            return; 
        }

        BlockHitResult safeHit = null;

        // Try the block exactly below the safe block first (ground)
        BlockPos downPos = safeBlockPos.down();
        if (!mc.world.getBlockState(downPos).isReplaceable() && !downPos.equals(anchorPos)) {
            safeHit = new BlockHitResult(downPos.toCenterPos().add(0, 0.5, 0), Direction.UP, downPos, false);
        }

        // Try other neighbors
        if (safeHit == null) {
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = safeBlockPos.offset(dir);
                if (neighbor.equals(anchorPos)) continue; // Try to avoid clicking the anchor if possible
                
                if (!mc.world.getBlockState(neighbor).isReplaceable()) {
                    safeHit = new BlockHitResult(
                        neighbor.toCenterPos().add(dir.getOpposite().getOffsetX() * 0.5, dir.getOpposite().getOffsetY() * 0.5, dir.getOpposite().getOffsetZ() * 0.5),
                        dir.getOpposite(),
                        neighbor,
                        false
                    );
                    break;
                }
            }
        }

        boolean needSneak = false;
        if (safeHit == null) {
            safeHit = new BlockHitResult(
                anchorPos.toCenterPos().add(playerDir.getOffsetX() * 0.5, playerDir.getOffsetY() * 0.5, playerDir.getOffsetZ() * 0.5),
                playerDir,
                anchorPos,
                false
            );
            needSneak = true;
        }

        boolean wasSneaking = mc.player.isSneaking();
        if (needSneak && !wasSneaking) {
            mc.player.setSneaking(true);
        }
        
        interactBlock(safeHit);
        
        if (needSneak && !wasSneaking) {
            mc.player.setSneaking(false);
        }
    }

    private void explodeAnchor(BlockPos anchorPos) {
        if (safeTotem.get()) {
            int selectedSlot = totemSlot.get() - 1;
            InvUtils.swap(selectedSlot, false);
        } else {
            if (!swapToItem(Items.RESPAWN_ANCHOR)) return;
        }

        // Explode by interacting with the UP face of the anchor to avoid LoS obstruction from safe block
        BlockHitResult explodeHit = new BlockHitResult(
            anchorPos.toCenterPos().add(0, 0.5, 0),
            Direction.UP, 
            anchorPos, 
            false
        );
        interactBlock(explodeHit);
    }

    private boolean swapToItem(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; ++i) {
            if (!mc.player.getInventory().getStack(i).isOf(item)) continue;
            InvUtils.swap(i, false);
            return true;
        }
        return false;
    }

    private void interactBlock(BlockHitResult hitResult) {
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        mc.player.swingHand(Hand.MAIN_HAND);
    }
}
