package com.xlrw.builder;

import com.xlrw.config.BuilderConfig;
import com.xlrw.config.WorldStateManager;
import com.xlrw.debug.XlrwDebug;
import com.xlrw.printer.ChestManager;
import com.xlrw.printer.LocalPathfinder;
import com.xlrw.printer.PrinterMovement;
import com.xlrw.printer.ScaffoldManager;
import com.xlrw.printer.ScaffoldNavigator;
import com.xlrw.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Random;

public class BuilderController {
    private static final BuilderController INSTANCE = new BuilderController();
    private static final Random RANDOM = new Random();

    public Schematic getSchematic() {
        return com.xlrw.schematic.SchematicManager.getInstance().getActiveSchematic();
    }

    public enum PrinterState {
        IDLE,
        BUILDING,
        MOVING_TO_BLOCK,
        TRAVELING_TO_CHEST,
        REFILLING_CHEST,
        CLIMBING_SCAFFOLD,
        DEMOLISHING
    }

    private PrinterState printerState = PrinterState.IDLE;
    private PlacementCandidate currentCandidate = null;
    private long nextActionTime = 0;
    private int consecutiveBlocks = 0;
    private int statUpdateTicks = 0;
    private int candidateAlignTicks = 0;
    private int refillTicks = 0;
    private Vec3 lastWatchdogPos = Vec3.ZERO;
    private int standstillTicks = 0;
    private int unstuckTransitTicks = 0;
    private int noPlanTicks = 0;
    private Vec3 candidateEye = null;

    private BlockPos pendingPlacementPos = null;
    private BlockState pendingExpectedState = null;
    private PlacementCandidate.Type pendingPlacementType = null;
    private long pendingTimeout = 0;
    private float pendingYaw = 0, pendingPitch = 0;
    private Direction pendingFace = null;
    private BlockPos pendingClickPos = null;
    private long pendingSentAt = 0;
    private BlockPos lastConfirmedPos = null;

    private String statusMessage = "Idle";



    public static BuilderController getInstance() {
        return INSTANCE;
    }

    public BlockPos getLastConfirmedPos() {
        return lastConfirmedPos;
    }

    public PlacementCandidate getCurrentCandidate() {
        return currentCandidate;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String message) {
        this.statusMessage = message;
    }

    public int getNoPlanTicks() {
        return noPlanTicks;
    }

    public boolean isClimbOutActive() {
        return climbOutActive;
    }

    public PrinterState getPrinterState() {
        return printerState;
    }

    public void onClientTick(Minecraft mc) {
        com.xlrw.gui.HubWidgets.tick(mc);
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            currentCandidate = null;
            printerState = PrinterState.IDLE;
            return;
        }

        Schematic schematic = com.xlrw.schematic.SchematicManager.getInstance().getActiveSchematic();
        if (schematic == null) {
            currentCandidate = null;
            printerState = PrinterState.IDLE;
            statusMessage = "No Schematic Loaded";
            return;
        }

        // If the player opened a user screen (inventory, chat, pause, mod screen, etc.)
        // Pause all builder actions so the player has 100% full control without any interference!
        // Inventory open: keep working (unless disabled or the player is holding an item on the cursor -
        // our hotbar swaps would mix it up)
        boolean invScreen = mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
                || mc.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
        // The XLRW deck is a live dashboard now: keep building behind it
        if (mc.screen instanceof com.xlrw.gui.XlrwScreen && BuilderConfig.getInstance().enabled) invScreen = true;
        // Free-cam editor: pause and let go of the movement keys so the bot does not walk off while you place
        if (mc.screen instanceof com.xlrw.gui.SchematicEditorScreen) {
            PrinterMovement.stop(mc);
            return;
        }
        if (invScreen && BuilderConfig.getInstance().workWithInventoryOpen && BuilderConfig.getInstance().enabled) {
            if (!player.containerMenu.getCarried().isEmpty()) {
                PrinterMovement.stop(mc);
                statusMessage = "3D Printer: Chờ bạn thả item đang cầm trên chuột...";
                return;
            }
        } else if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
                || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen
                || mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen
                || mc.screen instanceof com.xlrw.gui.XlrwScreen
                || (mc.screen != null && printerState != PrinterState.REFILLING_CHEST)) {
            return;
        }

        // Periodic stats update
        if (++statUpdateTicks >= 20) {
            statUpdateTicks = 0;
            schematic.updateStats(mc.level);
        }

        BuilderConfig config = BuilderConfig.getInstance();
        if (!config.enabled) {
            currentCandidate = null;
            WorkZoneManager.getInstance().clearZone();
            StationPlanner.getInstance().clear();
            SupportBeamManager.getInstance().clear();
            if (printerState != PrinterState.IDLE) {
                PrinterMovement.stop(mc);
                printerState = PrinterState.IDLE;
            }
            statusMessage = "3D Printer Paused";
            return;
        }

        // Black-box monitor: status line every second, snapshot + escalation when nothing progresses
        com.xlrw.debug.BotMonitor.tick(mc, player, schematic, this);
        tickFastPending(mc.level);

        // 0. Scaffold Jump Guard: If executing a multi-tick scaffold jump, let it run exclusively
        if (ScaffoldManager.getInstance().isPerformingScaffoldJump()) {
            boolean active = ScaffoldManager.getInstance().tickScaffoldJump(mc, player);
            if (active) return;
        }

        // 0. Demolish State Machine: If actively breaking a misplaced block with tool/pickaxe, let it run
        if (DemolishManager.getInstance().isBreaking()) {
            boolean breaking = DemolishManager.getInstance().tickBreaking(mc, player);
            if (breaking) {
                BlockPos breakPos = DemolishManager.getInstance().getActiveBreakPos();
                if (breakPos != null && mc.level != null) {
                    statusMessage = "Demolishing misplaced block: " + mc.level.getBlockState(breakPos).getBlock().getName().getString();
                } else if (DemolishManager.getInstance().isCollecting()) {
                    statusMessage = "Collecting dropped items from demolished block...";
                }
                return;
            }
        }

        // 1. Check if currently in refill flow OR out of materials -> Auto Refill from Chest
        boolean inRefillMode = printerState == PrinterState.TRAVELING_TO_CHEST || printerState == PrinterState.REFILLING_CHEST;
        if (config.autoRefill && ChestManager.getInstance().hasAnyChest() && (inRefillMode || ChestManager.getInstance().needsRefill(mc, player, schematic))) {
            handleChestRefill(mc, player, schematic);
            return;
        } else if (!inRefillMode) {
            refillTicks = 0;
        }

        // 2. Check if current layer is complete -> Auto Layer Advance
        if (config.layerMode && config.autoLayer) {
            checkLayerLeftovers(mc, schematic, config.currentLayer);
            if (isLayerComplete(schematic, config.currentLayer, mc.level)) {
                // Remove throw-away bridge/pillar blocks before moving on
                if (DemolishManager.getInstance().isBreaking()) {
                    DemolishManager.getInstance().tickBreaking(mc, player);
                    return;
                }
                if (TempBlockTracker.tickCleanup(mc, player, schematic)) {
                    printerState = PrinterState.MOVING_TO_BLOCK;
                    StationPlanner.getInstance().dropPlan();
                    return;
                }
                if (TowerJanitor.tickLayerCleanup(mc, player, schematic, config.currentLayer + 1)) {
                    printerState = PrinterState.DEMOLISHING;
                    StationPlanner.getInstance().dropPlan();
                    return;
                }
                int maxLayer = schematic.getMaxY() - schematic.getMinY();
                if (config.currentLayer < maxLayer) {
                    config.currentLayer++;
                    com.xlrw.debug.BotMonitor.progress("lên layer " + config.currentLayer);
                    BuilderConfig.save();
                    WorldStateManager.saveCurrentSession(mc);
                    WorkZoneManager.getInstance().clearZone();
                    StationPlanner.getInstance().clear();
                    TaskTracker.getInstance().clear();
                    player.displayClientMessage(Component.literal("§b[XLRW 3D-PRINTER] §aLayer #" + (config.currentLayer - 1) + " complete! Advancing to Layer #" + config.currentLayer), true);
                    currentCandidate = null;
                    return;
                } else {
                    statusMessage = "Print Finished! All layers complete.";
                    config.enabled = false;
                    BuilderConfig.save();
                    WorldStateManager.saveCurrentSession(mc);
                    PrinterMovement.stop(mc);
                    printerState = PrinterState.IDLE;
                    return;
                }
            }
        }

        long now = System.currentTimeMillis();

        // 0. Placement Confirmation: Verify block has appeared in the world before turning away or running to next block!
        if (pendingPlacementPos != null) {
            BlockState inWorld = mc.level.getBlockState(pendingPlacementPos);
            boolean confirmed = false;

            if (pendingPlacementType == PlacementCandidate.Type.BUCKET_FILL) {
                // Water refill: confirmed when bucket is filled with water!
                confirmed = player.getMainHandItem().is(Items.WATER_BUCKET) || InventoryHelper.hasItem(player, Items.WATER_BUCKET);
            } else if (pendingExpectedState != null) {
                if (pendingExpectedState.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                    confirmed = inWorld.getBlock() == pendingExpectedState.getBlock()
                            && inWorld.getFluidState().isSource();
                } else if (pendingPlacementType == PlacementCandidate.Type.WATERLOG) {
                    // Only the bucket action must produce water; placing e.g. mangrove roots is done once the block exists
                    // (the water is added later, in the fluid phase of the layer)
                    confirmed = inWorld.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                            && inWorld.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED);
                } else {
                    confirmed = BuildRules.buildStatus(inWorld, pendingExpectedState) == BuildRules.BuildStatus.DONE
                            || (!inWorld.isAir() && !inWorld.canBeReplaced() && !BuildRules.isLiquid(inWorld));
                }
            } else {
                confirmed = (!inWorld.isAir() && !inWorld.canBeReplaced()) || inWorld.getFluidState().isSource();
            }

            if (confirmed) {
                // Check if block was placed with wrong orientation (directional blocks: observer, piston, repeater, etc.)
                boolean sameBlock = pendingExpectedState != null && inWorld.getBlock() == pendingExpectedState.getBlock();
                boolean wrongOrientation = sameBlock
                        && BuildRules.hasOrientation(pendingExpectedState)
                        && !BuildRules.orientationMatches(inWorld, pendingExpectedState)
                        && !BlockStateAdjuster.needsAdjustment(inWorld, pendingExpectedState);
                if (pendingExpectedState != null && !sameBlock && pendingPlacementType == PlacementCandidate.Type.PLACE) {
                    // Something else ended up in the cell (piston head, a block pushed in by a piston, flowing water...):
                    // that is not the fault of the standing spot, so don't blacklist it
                    XlrwDebug.log("BUILD/OCCUPIED", "Ô " + pendingPlacementPos.toShortString() + " đang bị chiếm bởi " + inWorld
                            + " (cần " + pendingExpectedState.getBlock().getName().getString() + ")");
                } else if (wrongOrientation) {
                    XlrwDebug.log("BUILD/WRONG_ORIENTATION", "Đặt " + pendingExpectedState.getBlock().getName().getString()
                            + " tại " + pendingPlacementPos.toShortString() + " sai hướng từ vị trí đứng " + player.blockPosition().toShortString()
                            + " | thực tế=" + inWorld + " | cần=" + pendingExpectedState
                            + String.format(" | nhắm yaw=%.1f pitch=%.1f, mặt=%s, click=%s | hiện tại yaw=%.1f pitch=%.1f, sneak=%b, ms=%d",
                                    pendingYaw, pendingPitch, pendingFace, pendingClickPos != null ? pendingClickPos.toShortString() : "-",
                                    player.getYRot(), player.getXRot(), player.isShiftKeyDown(), System.currentTimeMillis() - pendingSentAt));
                    PlacementFinder.recordBadPlacementSpot(pendingPlacementPos, player.blockPosition());
                    StationPlanner.getInstance().recordBadPlacementStation(pendingPlacementPos, player.blockPosition());
                } else {
                    PlacementFinder.clearBadPlacementSpot(pendingPlacementPos);
                }

                // Block placement acknowledged and verified by server!
                XlrwDebug.logBuildSuccess(pendingPlacementPos, pendingExpectedState != null ? pendingExpectedState.getBlock().getName().getString() : (pendingPlacementType == PlacementCandidate.Type.BUCKET_FILL ? "water_refill" : "block"), "Server verified in-world");
                TaskTracker.getInstance().onSuccess(pendingPlacementPos);
                StationPlanner.getInstance().onPlaced(pendingPlacementPos);
                fluidMisses.remove(pendingPlacementPos);
                lastConfirmedPos = pendingPlacementPos;
                pendingPlacementPos = null;
                pendingExpectedState = null;
                pendingPlacementType = null;
                currentCandidate = null; // Ready to calmly find the next block!
            } else if (now > pendingTimeout) {
                // Server did not acknowledge within timeout window
                int attempts = TaskTracker.getInstance().getAttempts(pendingPlacementPos);
                XlrwDebug.logBuildReject(pendingPlacementPos, pendingExpectedState != null ? pendingExpectedState.getBlock().getName().getString() : "block", "Server placement timeout (attempts=" + attempts + ")");
                if (attempts >= 2) {
                    TaskTracker.getInstance().blacklist(pendingPlacementPos, 5000);
                }
                if (pendingPlacementType == PlacementCandidate.Type.FLUID_SOURCE) {
                    // The pour went somewhere else (server ray hit another block): never pour from this spot again, and
                    // after a few misses leave the cell for later instead of looping on it forever
                    PlacementFinder.recordBadPlacementSpot(pendingPlacementPos, player.blockPosition());
                    StationPlanner.getInstance().recordBadPlacementStation(pendingPlacementPos, player.blockPosition());
                    if (fluidMisses.merge(pendingPlacementPos.immutable(), 1, Integer::sum) >= 3) {
                        StationPlanner.getInstance().giveUp(pendingPlacementPos, "đổ chất lỏng trượt 3 lần");
                    }
                }
                pendingPlacementPos = null;
                pendingExpectedState = null;
                pendingPlacementType = null;
                currentCandidate = null;
            } else {
                // Still waiting for server confirmation (within 350ms window):
                // Calmly stay focused on the block and remain stationary (Baritone style)
                if (currentCandidate != null && config.smoothAim) {
                    HumanAim.stepRotation(player, currentCandidate.targetYaw, currentCandidate.targetPitch);
                }
                PrinterMovement.stop(mc);
                return;
            }
        }

        // 0. Standstill Watchdog: only counts while WALKING toward a committed station.
        // Standing still to place blocks (or having arrived) is never "stuck".
        StationPlanner planner = StationPlanner.getInstance();
        StationPlanner.Plan activePlan = planner.getPlan();
        boolean walking = printerState == PrinterState.MOVING_TO_BLOCK && activePlan != null && !activePlan.arrived(player);
        if (walking) {
            double movedDistSq = player.position().distanceToSqr(lastWatchdogPos);
            if (movedDistSq < 0.0009) {
                standstillTicks++;
            } else {
                standstillTicks = Math.max(0, standstillTicks - 3);
            }
        } else {
            standstillTicks = 0;
        }
        lastWatchdogPos = player.position();

        // Truly blocked on the way (~2s without moving): give up on THIS station/anchor and re-plan a different one.
        if (standstillTicks > 50 && printerState != PrinterState.REFILLING_CHEST) {
            XlrwDebug.log("MOVE/STANDSTILL", "Đứng yên 2.5s khi đang đi tới trạm " + (activePlan != null ? activePlan.stationBlock.toShortString() : "?") + ", lập kế hoạch lại");
            standstillTicks = 0;
            planner.failPlan(10_000);
            currentCandidate = null;
            TaskTracker.getInstance().clearPending();
            PrinterMovement.resetPath();
        }

        if (unstuckTransitTicks > 0) {
            unstuckTransitTicks--;
            return;
        }

        // While waiting for delay timer, maintain view and stand completely still!
        if (now < nextActionTime) {
            if (currentCandidate != null && config.smoothAim) {
                HumanAim.stepRotation(player, currentCandidate.targetYaw, currentCandidate.targetPitch);
            }
            if (printerState == PrinterState.BUILDING) {
                PrinterMovement.stop(mc);
            }
            return;
        }

        // A chorus escape in progress owns the tick (also when it was started from inside lava)
        if (chorusActive && tickChorus(mc, player)) {
            return;
        }

        // In lava, or stuck in (flowing) water: get out onto dry ground first
        if (tickFluidEscape(mc, player)) {
            return;
        }

        // Liquid poured into the wrong cell: plug it first, before it floods the build
        if (tickSpillFix(mc, player, schematic)) {
            return;
        }

        // Chorus fruit escape in progress (eating / waiting for the teleport)
        if (chorusActive && tickChorus(mc, player)) {
            return;
        }

        // Climbing out of an enclosed area owns the controls until we are out (checked BEFORE placing, otherwise
        // a single reachable block every few seconds kept resetting the "no plan" counter and the bot never left)
        if (climbOutActive && tickClimbOut(mc, player, schematic)) {
            return;
        }

        // Watchdog escalation: no block placed / broken and no real movement for a long time
        com.xlrw.debug.BotMonitor.Escalation esc = com.xlrw.debug.BotMonitor.pollEscalation();
        if (esc != com.xlrw.debug.BotMonitor.Escalation.NONE && handleEscalation(mc, player, schematic, esc)) {
            return;
        }

        // 1. Demolish Check: Search for misplaced/wrong blocks within reach to break with tool/pickaxe first!
        int activeLayerY = schematic.getMinY() + config.currentLayer;
        if (config.autoDemolish) {
            BlockPos misplacedPos = DemolishManager.getInstance().findMisplacedBlock(mc, player, schematic, activeLayerY);
            if (misplacedPos != null) {
                PrinterMovement.stop(mc); // Stop moving while breaking blocks!
                printerState = PrinterState.BUILDING;
                statusMessage = "Breaking misplaced block at " + misplacedPos.toShortString();
                DemolishManager.getInstance().startBreaking(mc, player, misplacedPos);
                return;
            }
        }

        // 1.5. Scaffolding Elevation & Descend Control:
        // Ground layers (Layer #0 or layer 1 directly reachable from ground) NEVER use scaffolding towers.
        boolean alreadyClimbing = printerState == PrinterState.CLIMBING_SCAFFOLD;
        boolean feetInScaffold = mc.level.getBlockState(player.blockPosition()).is(Blocks.SCAFFOLDING);
        boolean onScaffold = feetInScaffold || mc.level.getBlockState(player.blockPosition().below()).is(Blocks.SCAFFOLDING);
        boolean isGroundLayer = config.currentLayer == 0 || activeLayerY <= schematic.getMinY() + 1;

        // A) Accidentally elevated on old scaffolding while working on a ground layer (and no tower plan): sink down
        boolean towerPlanActive = activePlan != null && activePlan.isTower();
        if (isGroundLayer && onScaffold && !towerPlanActive && player.getY() > activeLayerY + 1.2) {
            statusMessage = "3D Printer: Descending scaffolding to ground for Layer #" + config.currentLayer;
            ScaffoldNavigator.releaseKeys(mc, player);
            PrinterMovement.holdSneakToDescend(mc, player);
            return;
        }

        // B) While climbing a tower, the climb owns the controls (no placing from mid-air inside the shaft)
        if (towerPlanActive && activePlan.phase == StationPlanner.PlanPhase.CLIMB_TOWER) {
            executePlanMovement(mc, player, schematic, activePlan, planner);
            currentCandidate = null;
            return;
        }

        // 2. Build everything reachable from the CURRENT spot first (stationary turret phase)
        if (currentCandidate != null && candidateEye != null
                && player.getEyePosition().distanceToSqr(candidateEye) > 0.09) {
            currentCandidate = null; // we moved: the stored aim data is stale
        }
        if (currentCandidate == null) {
            currentCandidate = PlacementFinder.findBestCandidate();
            candidateEye = player.getEyePosition();
            candidateAlignTicks = 0;
        }

        if (currentCandidate != null) {
            planner.resetArrivedIdle();
            noPlanTicks = 0;

            // Retry limit
            if (TaskTracker.getInstance().getAttempts(currentCandidate.targetPos) >= 3) {
                XlrwDebug.logBuildReject(currentCandidate.targetPos, currentCandidate.requiredState.getBlock().getName().getString(), "Retry limit reached (>=3 attempts), blacklisting 6s");
                TaskTracker.getInstance().blacklist(currentCandidate.targetPos, 6000);
                currentCandidate = null;
                return;
            }

            // A valid reachable candidate exists! STOP ALL LOCOMOTION IMMEDIATELY (Baritone & Meteor style)
            printerState = PrinterState.BUILDING;
            PrinterMovement.stop(mc);

            // Re-validate candidate target with TaskTracker
            if (TaskTracker.getInstance().isBlocked(currentCandidate.targetPos)) {
                currentCandidate = null;
                return;
            }

        // Equip required item / safe tool
        if (currentCandidate.type == PlacementCandidate.Type.BUCKET_FILL) {
            InventoryHelper.EquipResult eq = InventoryHelper.equipItem(player, Items.BUCKET);
            if (eq == InventoryHelper.EquipResult.SWITCHED) {
                nextActionTime = System.currentTimeMillis() + 60;
                return;
            } else if (eq == InventoryHelper.EquipResult.MISSING && !player.isCreative()) {
                currentCandidate = null;
                return;
            }
            statusMessage = "Refilling water bucket from source at " + currentCandidate.targetPos.toShortString();
        } else if (currentCandidate.type == PlacementCandidate.Type.ADJUST) {
            boolean changed = BlockStateAdjuster.equipSafeItem(player);
            if (changed) {
                nextActionTime = System.currentTimeMillis() + 60;
                return;
            }
            statusMessage = "Adjusting state: " + currentCandidate.requiredState.getBlock().getName().getString();
        } else if (currentCandidate.type == PlacementCandidate.Type.WATERLOG) {
            InventoryHelper.EquipResult eq = InventoryHelper.equipItem(player, Items.WATER_BUCKET);
            if (eq == InventoryHelper.EquipResult.SWITCHED) {
                nextActionTime = System.currentTimeMillis() + 60;
                return;
            } else if (eq == InventoryHelper.EquipResult.MISSING && !player.isCreative()) {
                XlrwDebug.logBuildReject(currentCandidate.targetPos, currentCandidate.requiredState.getBlock().getName().getString(), "Missing Water Bucket in inventory");
                currentCandidate = null;
                return;
            }
            statusMessage = "Waterlogging: " + currentCandidate.requiredState.getBlock().getName().getString();
        } else if (currentCandidate.type == PlacementCandidate.Type.FLUID_SOURCE) {
            InventoryHelper.EquipResult eq = InventoryHelper.equipItem(player, currentCandidate.item);
            if (eq == InventoryHelper.EquipResult.SWITCHED) {
                nextActionTime = System.currentTimeMillis() + 60;
                return;
            } else if (eq == InventoryHelper.EquipResult.MISSING && !player.isCreative()) {
                XlrwDebug.logBuildReject(currentCandidate.targetPos, currentCandidate.requiredState.getBlock().getName().getString(), "Missing fluid item: " + (currentCandidate.item != null ? currentCandidate.item.getDescription().getString() : "null"));
                currentCandidate = null;
                return;
            }
            statusMessage = "Placing fluid: " + currentCandidate.requiredState.getBlock().getName().getString();
        } else {
            // Placement / Stacking
            if (config.autoSwitchHotbar && currentCandidate.item != null) {
                InventoryHelper.EquipResult eq = InventoryHelper.equipItem(player, currentCandidate.item);
                if (eq == InventoryHelper.EquipResult.SWITCHED) {
                    nextActionTime = System.currentTimeMillis() + 60;
                    return;
                } else if (eq == InventoryHelper.EquipResult.MISSING && !player.isCreative()) {
                    statusMessage = "Missing item: " + currentCandidate.item.getDescription().getString();
                    XlrwDebug.logBuildReject(currentCandidate.targetPos, currentCandidate.requiredState.getBlock().getName().getString(), "Missing item in inventory: " + currentCandidate.item.getDescription().getString());
                    currentCandidate = null;
                    return;
                }
            }
            statusMessage = "Printing Layer #" + config.currentLayer + ": " + currentCandidate.requiredState.getBlock().getName().getString();
        }

        // Step aim
        boolean aimed = HumanAim.stepRotation(player, currentCandidate.targetYaw, currentCandidate.targetPitch);
        BlockPos clickPos = currentCandidate.getClickTargetPos();
        Direction requiredFace = (currentCandidate.type == PlacementCandidate.Type.WATERLOG || currentCandidate.type == PlacementCandidate.Type.BUCKET_FILL) ? null : currentCandidate.clickFace;
        boolean lookingAtFace = currentCandidate.type == PlacementCandidate.Type.BUCKET_FILL ? aimed : HumanAim.isLookingAtFace(mc, player, clickPos, requiredFace, config.reachDistance);

        // FAST MODE: plain blocks (obsidian, stone, planks...) - the look direction does not change the result, so
        // snap the aim and place several per tick without waiting for each server confirmation.
        if (isFastEligible(player, currentCandidate)) {
            int budget = Math.max(1, config.fastBlocksPerTick);
            PlacementCandidate c = currentCandidate;
            Item held = player.getMainHandItem().getItem();
            for (int i = 0; i < budget && c != null; i++) {
                if (c.item != held) break;
                if (!placeFast(mc, player, c)) break;
                if (i + 1 < budget) {
                    c = PlacementFinder.findBestCandidate();
                    if (c != null && !isFastEligible(player, c)) c = null;
                }
            }
            currentCandidate = null;
            nextActionTime = System.currentTimeMillis() + 45; // next burst next tick
            return;
        }

        boolean hasOrientation = BuildRules.hasOrientation(currentCandidate.requiredState);
        // For directional blocks (repeater, comparator, piston, observer, stairs, trapdoor...),
        // player yaw directly determines the placed block facing! We MUST require aimed == true!
        if (hasOrientation ? !aimed : (!aimed && !lookingAtFace)) {
            // Never spin forever on one block: count the failed alignment as an attempt and move on
            if (++candidateAlignTicks > 30) {
                XlrwDebug.logBuildReject(currentCandidate.targetPos, currentCandidate.requiredState.getBlock().getName().getString(), "Aim alignment timeout (>30 ticks)");
                TaskTracker.getInstance().onAction(currentCandidate.targetPos, 0);
                currentCandidate = null;
                candidateAlignTicks = 0;
            }
            return;
        }
        candidateAlignTicks = 0;

        // Execute authentic placement / interaction
        placeCandidate(mc, player, currentCandidate);
        return;
        }

        // ==========================================
        // 3. NOTHING BUILDABLE FROM HERE -> walk to the COMMITTED station (Baritone style)
        // ==========================================
        if (!config.autoMove) {
            statusMessage = "Nothing in reach. Enable Auto-Move or step closer.";
            PrinterMovement.stop(mc);
            return;
        }

        // 2.5 Emergency Pit-Escape Check:
        // If the player has fallen into a hole or pit, escape immediately before trying to walk across the map!
        if (isTrappedInPit(mc.level, player)) {
            if (handlePitEscape(mc, player, schematic)) {
                return;
            }
        }

        // Climbing out of an enclosed area owns the controls until we are out
        if (climbOutActive && tickClimbOut(mc, player, schematic)) {
            return;
        }

        // Throw-away blocks (escape pillars, bridges) within reach and no longer needed: break them right away
        if (!climbOutActive && TempBlockTracker.tickNearby(mc, player, schematic)) {
            printerState = PrinterState.DEMOLISHING;
            return;
        }

        // Scaffolding towers that no remaining work needs any more: knock them down when passing by
        if (!climbOutActive && TowerJanitor.tickNearby(mc, player, schematic)) {
            printerState = PrinterState.DEMOLISHING;
            return;
        }

        printerState = PrinterState.MOVING_TO_BLOCK;
        StationPlanner.Plan plan = planner.ensurePlan(mc.level, player, schematic, config.currentLayer, lastConfirmedPos);

        if (plan == null) {
            // Work exists but no standing spot is reachable right now. Stand calmly; do NOT wander.
            PrinterMovement.stop(mc);
            noPlanTicks++;
            statusMessage = "3D Printer: Looking for a reachable standing spot...";

            // Active Floating / Support handling:
            // If any blocks on this layer lack solid support (floating), construct scaffolding columns or support beams!
            if (!config.airPlace && SupportBeamManager.getInstance().handleFloatingSupport(mc, player, schematic, activeLayerY)) {
                noPlanTicks = 0;
                return;
            }

            // If SupportBeamManager is currently actively managing a floating target, stay calm and do NOT wander
            if (!config.airPlace && SupportBeamManager.getInstance().hasActiveFloatingTarget()) {
                noPlanTicks = 0;
                return;
            }

            // Shut in (a room / pit inside the build) with no way to the remaining work: climb out on scaffolding
            if (noPlanTicks > 20 && !climbOutActive && isEnclosed(mc.level, player)) {
                if (startClimbOut(mc, player)) {
                    noPlanTicks = 0;
                    return;
                }
            }

            // Only explore after being idle for a while (~3s)
            if (noPlanTicks > 60) {
                noPlanTicks = 0;
                TaskTracker.getInstance().clearPermanentSkips();
                if (triggerAutonomousUnstuck(mc, player, schematic, config.currentLayer)) {
                    unstuckTransitTicks = 25;
                }
            }
            return;
        }
        noPlanTicks = 0;

        // Execute unified movement sequence according to plan.phase:
        executePlanMovement(mc, player, schematic, plan, planner);
    }

    // Tower progress watchdog
    private StationPlanner.Plan towerWatchPlan = null;
    private int towerWatchTop = Integer.MIN_VALUE;
    private double towerWatchY = 0;
    private long towerWatchAt = 0;

    private void executePlanMovement(Minecraft mc, LocalPlayer player, Schematic schematic, StationPlanner.Plan plan, StationPlanner planner) {
        // ---------------- Plain walk to a ground station ----------------
        if (!plan.isTower()) {
            if (plan.arrived(player)) {
                PrinterMovement.stop(mc);
                ScaffoldNavigator.releaseKeys(mc, player);
                plan.transitionTo(StationPlanner.PlanPhase.AT_STATION);
                statusMessage = "3D Printer: At station, waiting for " + plan.anchor.toShortString();
                planner.tickArrivedIdle();
                return;
            }
            if (isTrappedInPit(mc.level, player) && handlePitEscape(mc, player, schematic)) {
                return;
            }
            printerState = PrinterState.MOVING_TO_BLOCK;
            statusMessage = "3D Printer: Walking to station " + plan.stationBlock.toShortString() + " for " + plan.anchor.toShortString();
            PrinterMovement.moveTo(mc, plan.station, plan.precise ? 0.18 : 0.3, false, false);
            return;
        }

        // ---------------- Scaffolding tower: approach -> raise -> climb -> stand on top ----------------
        ScaffoldManager sm = ScaffoldManager.getInstance();
        BlockPos base = plan.scaffoldTowerPos;
        int neededTopY = plan.targetElevation - 1;
        int topY = sm.getColumnTopY(mc, base); // base.y - 1 when the column does not exist yet
        long now = System.currentTimeMillis();

        // Support column for a floating section: done as soon as it reaches just under the seed block
        if (plan.isSupport() && topY >= neededTopY) {
            TempBlockTracker.record(base, Items.SCAFFOLDING);
            PrinterMovement.stop(mc);
            ScaffoldNavigator.releaseKeys(mc, player);
            statusMessage = "3D Printer: Support column ready under " + plan.anchor.toShortString();
            planner.completeSupport();
            return;
        }

        if (!plan.isSupport() && plan.arrived(player) && player.onGround()) {
            PrinterMovement.stop(mc);
            ScaffoldNavigator.releaseKeys(mc, player);
            plan.transitionTo(StationPlanner.PlanPhase.AT_STATION);
            statusMessage = "3D Printer: On tower top, waiting for " + plan.anchor.toShortString();
            planner.tickArrivedIdle();
            return;
        }

        // Progress watchdog: the tower must grow or the player must rise, otherwise give up on this tower
        if (towerWatchPlan != plan) {
            towerWatchPlan = plan;
            towerWatchTop = topY;
            towerWatchY = player.getY();
            towerWatchAt = now;
        } else if (plan.phase == StationPlanner.PlanPhase.APPROACH_TOWER_BASE || plan.phase == StationPlanner.PlanPhase.BUILD_TOWER
                && topY > towerWatchTop) {
            // Walking to the base (often DOWN from the top of the build) or raising the column: progress, and the
            // height reference must follow the player down - otherwise the climb from the ground never "beats" the
            // height we started at and a perfectly fine 20-block climb was cancelled half way.
            towerWatchTop = Math.max(towerWatchTop, topY);
            towerWatchY = player.getY();
            towerWatchAt = now;
        } else if (topY > towerWatchTop || player.getY() > towerWatchY + 0.5) {
            towerWatchTop = Math.max(towerWatchTop, topY);
            towerWatchY = Math.max(towerWatchY, player.getY());
            towerWatchAt = now;
        } else if (now - towerWatchAt > 5_000) {
            XlrwDebug.logTowerAction("NO_PROGRESS", base, topY, neededTopY, "Trụ không tiến triển 5s ở phase " + plan.phase + ", bỏ trụ này");
            towerWatchPlan = null;
            PrinterMovement.stop(mc);
            ScaffoldNavigator.releaseKeys(mc, player);
            planner.failPlan(15_000);
            return;
        }

        printerState = PrinterState.CLIMBING_SCAFFOLD;

        if (topY < neededTopY) {
            BlockPos stand = plan.towerStand;
            if (stand == null) {
                planner.failPlan(10_000);
                return;
            }
            boolean insideColumn = player.blockPosition().getX() == base.getX() && player.blockPosition().getZ() == base.getZ();
            Vec3 baseCenter = Vec3.atCenterOf(base);
            double eyeDist = player.getEyePosition().distanceTo(baseCenter);
            boolean canClick = !insideColumn && player.onGround() && eyeDist <= 4.0
                    && Math.hypot(player.getX() - baseCenter.x, player.getZ() - baseCenter.z) >= 0.9;
            if (canClick) {
                BlockHitResult los = mc.level.clip(new ClipContext(player.getEyePosition(), baseCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                if (los.getType() == HitResult.Type.BLOCK && !los.getBlockPos().equals(base)
                        && !mc.level.getBlockState(los.getBlockPos()).is(Blocks.SCAFFOLDING)
                        && !los.getBlockPos().equals(base.below())) {
                    canClick = false;
                }
            }
            if (!canClick) {
                plan.transitionTo(StationPlanner.PlanPhase.APPROACH_TOWER_BASE);
                statusMessage = "3D Printer: Going to tower spot " + stand.toShortString();
                if (PrinterMovement.moveTo(mc, new Vec3(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5), 0.3, false, false)) {
                    // Arrived at the stand but still cannot click: the planner's assumption was wrong
                    if (now - plan.phaseStartedAt > 2_500) planner.failPlan(15_000);
                }
                return;
            }
            plan.transitionTo(StationPlanner.PlanPhase.BUILD_TOWER);
            PrinterMovement.stop(mc);
            ScaffoldNavigator.releaseKeys(mc, player);
            statusMessage = "3D Printer: " + (plan.isSupport() ? "Raising support column " : "Raising scaffolding tower ") + base.toShortString() + " to Y=" + neededTopY + " (" + (topY + 1 - base.getY()) + "/" + (neededTopY + 1 - base.getY()) + ")";
            sm.extendScaffoldColumn(mc, player, base, neededTopY);
            if (sm.consumeColumnRejected()) {
                // This column can never be built: drop it right away instead of waiting for the watchdog
                towerWatchPlan = null;
                PrinterMovement.stop(mc);
                ScaffoldNavigator.releaseKeys(mc, player);
                planner.failPlan(20_000);
            }
            return;
        }

        plan.transitionTo(StationPlanner.PlanPhase.CLIMB_TOWER);
        statusMessage = "3D Printer: Climbing tower to Y=" + plan.targetElevation;
        if (ScaffoldNavigator.climb(mc, player, base, topY, plan.targetElevation)) {
            plan.transitionTo(StationPlanner.PlanPhase.AT_STATION);
            PrinterMovement.stop(mc);
        }
    }

    private void handleChestRefill(Minecraft mc, LocalPlayer player, Schematic schematic) {
        BlockPos chestPos = ChestManager.getInstance().getTargetChestPos();
        if (chestPos == null) {
            statusMessage = "Missing Material Chest! Set chest in GUI [V].";
            PrinterMovement.stop(mc);
            return;
        }

        Vec3 chestTarget = new Vec3(chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5);
        double dist = player.position().distanceTo(chestTarget);

        if (dist > 3.2) {
            printerState = PrinterState.TRAVELING_TO_CHEST;
            int chestNum = ChestManager.getInstance().getCurrentChestIndex() + 1;
            statusMessage = "3D Printer: Traveling to supply chest #" + chestNum + " at " + chestPos.toShortString();
            PrinterMovement.moveTo(mc, chestTarget, 2.5);
            refillTicks = 0;
        } else {
            printerState = PrinterState.REFILLING_CHEST;
            PrinterMovement.stop(mc);
            refillTicks++;

            // Safety timeout: if trapped in chest GUI for > 60 ticks (3s), force close and resume
            if (refillTicks > 60) {
                player.closeContainer();
                if (mc.screen != null) mc.setScreen(null);
                printerState = PrinterState.BUILDING;
                refillTicks = 0;
                return;
            }

            if (player.containerMenu == player.inventoryMenu) {
                statusMessage = "3D Printer: Opening supply chest...";
                ChestManager.getInstance().openChest(mc, player);
            } else {
                statusMessage = "3D Printer: Restocking layer materials...";
                ChestManager.RefillResult result = ChestManager.getInstance().processRefill(mc, player, schematic);

                if (result == ChestManager.RefillResult.FINISHED) {
                    player.displayClientMessage(Component.literal("§b[XLRW 3D-PRINTER] §aMaterials restocked! Resuming build."), true);
                    player.closeContainer();
                    if (mc.screen != null) mc.setScreen(null);
                    printerState = PrinterState.BUILDING;
                    refillTicks = 0;
                } else if (result == ChestManager.RefillResult.NEXT_CHEST) {
                    int nextNum = ChestManager.getInstance().getCurrentChestIndex() + 1;
                    player.displayClientMessage(Component.literal("§b[XLRW 3D-PRINTER] §eVisiting next supply chest #" + nextNum + "..."), true);
                    player.closeContainer();
                    if (mc.screen != null) mc.setScreen(null);
                    printerState = PrinterState.TRAVELING_TO_CHEST;
                    refillTicks = 0;
                } else if (result == ChestManager.RefillResult.ALL_CHESTS_EXHAUSTED) {
                    player.displayClientMessage(Component.literal("§b[XLRW 3D-PRINTER] §6All supply chests checked. Continuing with available items."), true);
                    player.closeContainer();
                    if (mc.screen != null) mc.setScreen(null);
                    printerState = PrinterState.BUILDING;
                    refillTicks = 0;
                }
            }
        }
    }

    // Blocks the bot gave up on (no reachable spot even after an access tower): the layer may advance without them
    private final java.util.Set<BlockPos> layerLeftovers = new java.util.LinkedHashSet<>();

    /**
     * Blocks given up on in earlier layers that are still unfinished: offered again as extra work, so the bot places
     * them as soon as it can reach them (e.g. from the next layer, or once it is back up on the build).
     */
    public List<Map.Entry<BlockPos, BlockState>> leftoverEntries(Schematic schematic, ClientLevel level) {
        List<Map.Entry<BlockPos, BlockState>> out = new java.util.ArrayList<>();
        if (layerLeftovers.isEmpty() || schematic == null || level == null) return out;
        java.util.Iterator<BlockPos> it = layerLeftovers.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            BlockState exp = schematic.getBlock(p);
            if (exp == null) { it.remove(); continue; }
            BlockState w = level.getBlockState(p);
            BuildRules.BuildStatus st = BuildRules.buildStatus(w, exp);
            BuildRules.FluidStatus fs = BuildRules.fluidStatus(w, exp);
            boolean open = st == BuildRules.BuildStatus.MISSING || st == BuildRules.BuildStatus.STACKABLE || st == BuildRules.BuildStatus.ADJUST
                    || fs == BuildRules.FluidStatus.PLACE_SOURCE || fs == BuildRules.FluidStatus.WATERLOG;
            if (!open) { it.remove(); continue; }
            out.add(new java.util.AbstractMap.SimpleEntry<>(p, exp));
        }
        return out;
    }
    private long nextLeftoverCheck = 0;

    /**
     * Nothing placed for 45s and every unfinished block of the layer is one that could not be reached even from an
     * access tower: accept them as leftovers so the build goes on, and tell the player which ones to place by hand.
     */
    private void checkLayerLeftovers(Minecraft mc, Schematic schematic, int layerIndex) {
        long now = System.currentTimeMillis();
        if (now < nextLeftoverCheck) return;
        nextLeftoverCheck = now + 5_000;
        if (com.xlrw.debug.BotMonitor.sinceLastPlaced() < 45_000) return;
        int targetY = schematic.getMinY() + layerIndex;
        List<BlockPos> unfinished = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : schematic.getBlocksAtY(targetY)) {
            if (layerLeftovers.contains(e.getKey())) continue;
            BlockState w = mc.level.getBlockState(e.getKey());
            BuildRules.BuildStatus st = BuildRules.buildStatus(w, e.getValue());
            BuildRules.FluidStatus fs = BuildRules.fluidStatus(w, e.getValue());
            boolean open = st == BuildRules.BuildStatus.MISSING || st == BuildRules.BuildStatus.STACKABLE || st == BuildRules.BuildStatus.ADJUST
                    || fs == BuildRules.FluidStatus.PLACE_SOURCE || fs == BuildRules.FluidStatus.WATERLOG;
            if (open) {
                if (!StationPlanner.getInstance().isHardSkipped(e.getKey())) return; // still real work somewhere
                unfinished.add(e.getKey());
            }
        }
        if (unfinished.isEmpty()) return;
        layerLeftovers.addAll(unfinished);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(unfinished.size(), 20); i++) sb.append(unfinished.get(i).toShortString()).append("; ");
        XlrwDebug.log("LAYER/LEFTOVER", "Layer #" + layerIndex + ": " + unfinished.size()
                + " block không có chỗ đứng nào tới được, bỏ lại để làm tiếp layer sau: " + sb);
        if (mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§e[Xlrw] §fLayer #" + layerIndex + ": bỏ qua "
                    + unfinished.size() + " block bot không tới được (đặt tay sau). Vị trí ghi trong logs/xlrw_debug.log"), false);
        }
    }

    private boolean isLayerComplete(Schematic schematic, int layerIndex, ClientLevel level) {
        int minY = schematic.getMinY();
        int targetY = minY + layerIndex;

        List<Map.Entry<BlockPos, BlockState>> entries = schematic.getBlocksAtY(targetY);
        for (Map.Entry<BlockPos, BlockState> entry : entries) {
            BlockPos pos = entry.getKey();
            if (layerLeftovers.contains(pos)) continue;
            BlockState expected = entry.getValue();
            BlockState inWorld = level.getBlockState(pos);

            // Solid blocks build phase
            BuildRules.BuildStatus bStatus = BuildRules.buildStatus(inWorld, expected);
            if (bStatus == BuildRules.BuildStatus.MISSING || bStatus == BuildRules.BuildStatus.STACKABLE || bStatus == BuildRules.BuildStatus.ADJUST) {
                return false;
            }

            // Misplaced blocks must be demolished before layer is complete
            if (DemolishManager.isMisplaced(level, schematic, pos)) {
                return false;
            }

            // Fluid phase
            BuildRules.FluidStatus fStatus = BuildRules.fluidStatus(inWorld, expected);
            if (fStatus == BuildRules.FluidStatus.PLACE_SOURCE || fStatus == BuildRules.FluidStatus.WATERLOG || fStatus == BuildRules.FluidStatus.DRAIN) {
                return false;
            }

            // Successfully matched: clear any past retry counts
            TaskTracker.getInstance().onSuccess(pos);
        }
        return true;
    }

    public static boolean hasSolidNeighbor(ClientLevel level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos n = pos.relative(dir);
            BlockState s = level.getBlockState(n);
            if (!s.isAir() && !s.canBeReplaced() && !BuildRules.isLiquid(s)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAccessible(ClientLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        // At least one adjacent face must be passable (air, replaceable, etc.) so a player or raycast can reach it
        for (Direction dir : Direction.values()) {
            BlockPos n = pos.relative(dir);
            BlockState s = level.getBlockState(n);
            if (s.isAir() || s.canBeReplaced() || LocalPathfinder.isPassable(level, n)) {
                return true;
            }
        }
        return false;
    }

    public static Vec3 getVantagePosition(ClientLevel level, BlockPos targetPos, LocalPlayer player) {
        if (level == null || targetPos == null || player == null) return Vec3.atCenterOf(targetPos);

        Vec3 bestSpot = null;
        double minTravelDistSq = Double.MAX_VALUE;

        // Search horizontal radius 2 to 3 around targetPos
        // Search vertical Y from targetPos.getY() + 1 down to 4 blocks below
        for (int r = 2; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // perimeter

                    for (int dy = 1; dy >= -4; dy--) {
                        BlockPos standPos = new BlockPos(targetPos.getX() + dx, targetPos.getY() + dy, targetPos.getZ() + dz);

                        boolean feetPassable = LocalPathfinder.isPassable(level, standPos)
                                || level.getBlockState(standPos).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING);
                        boolean headPassable = LocalPathfinder.isPassable(level, standPos.above());
                        boolean floorStandable = LocalPathfinder.isStandable(level, standPos.below())
                                || level.getBlockState(standPos).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING);

                        if (feetPassable && headPassable && floorStandable) {
                            Vec3 eyeAtStand = new Vec3(standPos.getX() + 0.5, standPos.getY() + 1.62, standPos.getZ() + 0.5);
                            double distToTargetSq = eyeAtStand.distanceToSqr(Vec3.atCenterOf(targetPos));

                            // Must be within reach distance
                            if (distToTargetSq >= 1.4 * 1.4 && distToTargetSq <= 4.2 * 4.2) {
                                // Line of sight check: ensure targetPos is actually visible from this vantage spot
                                net.minecraft.world.phys.BlockHitResult los = level.clip(new net.minecraft.world.level.ClipContext(
                                        eyeAtStand, Vec3.atCenterOf(targetPos), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
                                if (los.getType() == HitResult.Type.BLOCK && !los.getBlockPos().equals(targetPos)) {
                                    continue; // Occluded by an obstructing wall
                                }

                                Vec3 spot = new Vec3(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5);
                                double distToPlayerSq = spot.distanceToSqr(player.position());
                                if (distToPlayerSq < minTravelDistSq) {
                                    minTravelDistSq = distToPlayerSq;
                                    bestSpot = spot;
                                }
                            }
                        }
                    }
                }
            }
            if (bestSpot != null) return bestSpot;
        }

        if (bestSpot != null) return bestSpot;

        // Fallback: horizontal offset at player's current Y height so destination is never floating in air
        Vec3 diff = player.position().subtract(Vec3.atCenterOf(targetPos));
        double xzLen = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        if (xzLen > 0.1) {
            return new Vec3(targetPos.getX() + 0.5 + (diff.x / xzLen) * 2.0, player.getY(), targetPos.getZ() + 0.5 + (diff.z / xzLen) * 2.0);
        }
        return new Vec3(targetPos.getX() + 2.0, player.getY(), targetPos.getZ() + 0.5);
    }

    public static Vec3 getAlternateVantagePosition(ClientLevel level, BlockPos targetPos, LocalPlayer player) {
        if (level == null || targetPos == null || player == null) return null;

        Vec3 playerPos = player.position();
        Vec3 targetCenter = Vec3.atCenterOf(targetPos);
        double currentAngle = Math.atan2(playerPos.z - targetCenter.z, playerPos.x - targetCenter.x);

        Vec3 bestAlternate = null;
        double maxAngleDiff = 0.0;

        for (int r = 2; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;

                    double spotAngle = Math.atan2(dz, dx);
                    double angleDiff = Math.abs(Mth.wrapDegrees((float) Math.toDegrees(spotAngle - currentAngle)));

                    if (angleDiff < 55.0) continue; // must be on another side of block

                    for (int dy = 1; dy >= -3; dy--) {
                        BlockPos standPos = new BlockPos(targetPos.getX() + dx, targetPos.getY() + dy, targetPos.getZ() + dz);

                        boolean feetPassable = LocalPathfinder.isPassable(level, standPos)
                                || level.getBlockState(standPos).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING);
                        boolean headPassable = LocalPathfinder.isPassable(level, standPos.above());
                        boolean floorStandable = LocalPathfinder.isStandable(level, standPos.below())
                                || level.getBlockState(standPos).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING);

                        if (feetPassable && headPassable && floorStandable) {
                            Vec3 eyeAtStand = new Vec3(standPos.getX() + 0.5, standPos.getY() + 1.62, standPos.getZ() + 0.5);
                            double distToTargetSq = eyeAtStand.distanceToSqr(targetCenter);

                            if (distToTargetSq >= 1.4 * 1.4 && distToTargetSq <= 4.2 * 4.2) {
                                net.minecraft.world.phys.BlockHitResult los = level.clip(new net.minecraft.world.level.ClipContext(
                                        eyeAtStand, targetCenter, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
                                if (los.getType() == HitResult.Type.BLOCK
                                        && !los.getBlockPos().equals(targetPos)
                                        && los.getBlockPos().distSqr(targetPos) > 2) {
                                    continue;
                                }

                                if (angleDiff > maxAngleDiff) {
                                    maxAngleDiff = angleDiff;
                                    bestAlternate = new Vec3(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5);
                                }
                            }
                        }
                    }
                }
            }
        }
        return bestAlternate;
    }

    /**
     * Called after a few seconds without any reachable work. Moves to a more open, reachable spot closer to the
     * remaining work. It NEVER breaks correctly built blocks and NEVER places random blocks - that behaviour used to
     * litter the site with glass/cobble pillars and holes.
     */
    public boolean triggerAutonomousUnstuck(Minecraft mc, LocalPlayer player, Schematic schematic, int currentLayer) {
        if (mc.level == null || player == null || schematic == null) return false;

        // 1. Centroid of the unfinished blocks on this layer
        int targetY = schematic.getMinY() + currentLayer;
        double sumX = 0, sumZ = 0;
        int count = 0;
        for (Map.Entry<BlockPos, BlockState> e : schematic.getBlocksAtY(targetY)) {
            BuildRules.BuildStatus status = BuildRules.buildStatus(mc.level.getBlockState(e.getKey()), e.getValue());
            if (status == BuildRules.BuildStatus.MISSING || status == BuildRules.BuildStatus.STACKABLE) {
                sumX += e.getKey().getX();
                sumZ += e.getKey().getZ();
                count++;
            }
        }
        if (count == 0) return false;
        Vec3 center = new Vec3(sumX / count + 0.5, player.getY(), sumZ / count + 0.5);

        // 2. A reachable, open standing cell that is closer to that work (real walking reachability)
        BlockPos playerFoot = LocalPathfinder.normalizeStart(mc.level, player.blockPosition());
        LocalPathfinder.Reachability reach = LocalPathfinder.flood(mc.level, playerFoot, 2500, 16);
        Direction[] cardinals = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        BlockPos best = null;
        double bestScore = -Double.MAX_VALUE;
        double currentDist = Vec3.atBottomCenterOf(playerFoot).distanceTo(center);
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos p = playerFoot.offset(dx, dy, dz);
                    double cost = reach.costTo(p);
                    if (cost == Double.MAX_VALUE || cost < 3) continue;
                    int walls = 0;
                    for (Direction d : cardinals) {
                        if (!LocalPathfinder.isPassable(mc.level, p.relative(d))) walls++;
                    }
                    if (walls >= 3) continue;
                    double distToCenter = Vec3.atBottomCenterOf(p).distanceTo(center);
                    if (distToCenter >= currentDist - 1.0) continue; // must actually get closer to the work
                    double score = -distToCenter * 2.0 - cost * 0.5 - walls * 2.0;
                    if (score > bestScore) {
                        bestScore = score;
                        best = p;
                    }
                }
            }
        }
        if (best != null) {
            XlrwDebug.logMoveObstacle("UNSTUCK_CORRIDOR", best, "Đi tới chỗ thoáng gần phần việc còn lại");
            printerState = PrinterState.MOVING_TO_BLOCK;
            statusMessage = "3D Printer: Repositioning toward remaining work";
            PrinterMovement.resetPath();
            PrinterMovement.moveTo(mc, new Vec3(best.getX() + 0.5, best.getY(), best.getZ() + 0.5), 0.5);
            return true;
        }

        // 3. Only genuinely wrong blocks right around the player may be removed (never correct build blocks,
        //    never natural ground, never our own tower)
        BlockPos feetPos = player.blockPosition();
        StationPlanner.Plan plan = StationPlanner.getInstance().getPlan();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos check = feetPos.offset(dx, dy, dz);
                    if (plan != null && plan.isTower() && check.getX() == plan.scaffoldTowerPos.getX() && check.getZ() == plan.scaffoldTowerPos.getZ()) continue;
                    if (DemolishManager.isMisplaced(mc.level, schematic, check)) {
                        XlrwDebug.logMoveObstacle("UNSTUCK_DEMOLISH_MISPLACED", check, "Phá block sai vị trí đang chặn người chơi");
                        statusMessage = "3D Printer: Unstuck - Breaking misplaced block at " + check.toShortString();
                        printerState = PrinterState.DEMOLISHING;
                        DemolishManager.getInstance().startBreaking(mc, player, check);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------
    // Climb out of an enclosed area: scaffolding (or a throw-away block) under the feet, jump, repeat
    // ------------------------------------------------------------------------------------------------

    private boolean climbOutActive = false;
    private double climbOutStartY = 0;
    private long climbOutStartedAt = 0;

    /** Enclosed = only a small area can be walked to from here (a room or pit inside the build). */
    public static boolean isEnclosed(ClientLevel level, LocalPlayer player) {
        if (level == null || player == null) return false;
        BlockPos foot = LocalPathfinder.normalizeStart(level, player.blockPosition());
        return LocalPathfinder.flood(level, foot, 120, 12).size() <= 80;
    }

    private Item climbOutItem(LocalPlayer player) {
        if (ScaffoldManager.getInstance().hasScaffolding(player) || player.isCreative()) return Items.SCAFFOLDING;
        return InventoryHelper.getAvailableSolidSupportItem(player);
    }

    private boolean startClimbOut(Minecraft mc, LocalPlayer player) {
        if (tryStartChorus(mc, player, "vùng kín")) {
            return true;
        }
        if (climbOutItem(player) == null) {
            XlrwDebug.logRateLimited("CLIMB_OUT_NO_ITEM", 5000, "MOVE/CLIMB_OUT", "Bị nhốt nhưng không có giàn giáo/block để kê chân!");
            return false;
        }
        climbOutActive = true;
        climbOutStartY = player.getY();
        climbOutStartedAt = System.currentTimeMillis();
        XlrwDebug.log("MOVE/CLIMB_OUT", "Bị nhốt trong vùng kín tại " + player.blockPosition().toShortString()
                + " -> kê " + climbOutItem(player).getDescription().getString() + " dưới chân để leo ra");
        return true;
    }

    // ------------------------------------------------------------------------------------------------
    // Chorus fruit escape (option): eat a chorus fruit instead of pillaring out of a deep hole
    // ------------------------------------------------------------------------------------------------

    private boolean chorusActive = false;
    private int chorusAttempts = 0;
    private int chorusTicks = 0;
    private Vec3 chorusStartPos = null;
    private int chorusSettleTicks = -1;
    private long chorusBlockedUntil = 0;

    private boolean hasChorus(LocalPlayer player) {
        return player.isCreative() && BuilderConfig.getInstance().creativeAutoPick || InventoryHelper.hasItem(player, Items.CHORUS_FRUIT);
    }

    private final Map<BlockPos, Integer> fluidMisses = new java.util.HashMap<>();

    private boolean tryStartChorus(Minecraft mc, LocalPlayer player, String where) {
        BuilderConfig config = BuilderConfig.getInstance();
        if (!config.chorusEscape) return false;
        if (chorusActive) return true;
        if (System.currentTimeMillis() < chorusBlockedUntil) return false;
        if (!hasChorus(player)) {
            XlrwDebug.logRateLimited("CHORUS_NONE", 10_000, "MOVE/CHORUS", "Kẹt trong " + where + " nhưng không có Chorus Fruit -> dùng giàn giáo");
            return false;
        }
        chorusActive = true;
        chorusAttempts = 0;
        chorusTicks = 0;
        chorusSettleTicks = -1;
        chorusStartPos = player.position();
        PrinterMovement.stop(mc);
        StationPlanner.getInstance().dropPlan();
        currentCandidate = null;
        XlrwDebug.log("MOVE/CHORUS", "Kẹt trong " + where + " tại " + player.blockPosition().toShortString() + " -> ăn Chorus Fruit để dịch chuyển ra");
        return true;
    }

    private void endChorus(Minecraft mc, String why) {
        chorusActive = false;
        mc.options.keyUse.setDown(false);
        if (mc.player != null && mc.player.isUsingItem() && mc.gameMode != null) mc.gameMode.releaseUsingItem(mc.player);
        XlrwDebug.log("MOVE/CHORUS", why);
        StationPlanner.getInstance().dropPlan();
        PrinterMovement.resetPath();
    }

    /** @return true while the chorus routine owns this tick */
    private boolean tickChorus(Minecraft mc, LocalPlayer player) {
        PrinterMovement.stop(mc);
        statusMessage = "3D Printer: Ăn Chorus Fruit để thoát (lần " + (chorusAttempts + 1) + ")";

        // After a teleport: let the player land, then check whether we are out
        if (chorusSettleTicks >= 0) {
            if (++chorusSettleTicks < 12 && !player.onGround()) return true;
            if (chorusSettleTicks < 6) return true;
            chorusSettleTicks = -1;
            if (!isEnclosed(mc.level, player) && !isTrappedInPit(mc.level, player) && !player.isInLava()) {
                endChorus(mc, "Chorus Fruit: đã ra ngoài tại " + player.blockPosition().toShortString());
                return false;
            }
            if (chorusAttempts >= 5) {
                chorusBlockedUntil = System.currentTimeMillis() + 30_000;
                endChorus(mc, "Chorus Fruit: 5 lần vẫn chưa ra được, chuyển sang giàn giáo");
                return false;
            }
            chorusTicks = 0;
            chorusStartPos = player.position();
            return true;
        }

        // Teleported? (position jumped)
        if (chorusStartPos != null && player.position().distanceToSqr(chorusStartPos) > 2.25) {
            mc.options.keyUse.setDown(false);
            chorusAttempts++;
            chorusSettleTicks = 0;
            XlrwDebug.log("MOVE/CHORUS", "Dịch chuyển tới " + player.blockPosition().toShortString() + " (lần " + chorusAttempts + ")");
            return true;
        }

        // Cooldown between fruits
        if (player.getCooldowns().isOnCooldown(Items.CHORUS_FRUIT)) {
            mc.options.keyUse.setDown(false);
            return true;
        }

        if (++chorusTicks > 80) { // ate without teleporting (no safe spot) or could not eat
            chorusAttempts++;
            chorusTicks = 0;
            mc.options.keyUse.setDown(false);
            if (player.isUsingItem()) mc.gameMode.releaseUsingItem(player);
            if (chorusAttempts >= 5) {
                chorusBlockedUntil = System.currentTimeMillis() + 30_000;
                endChorus(mc, "Chorus Fruit: không dịch chuyển được, chuyển sang giàn giáo");
                return false;
            }
            return true;
        }

        InventoryHelper.EquipResult eq = InventoryHelper.equipItem(player, Items.CHORUS_FRUIT);
        if (eq == InventoryHelper.EquipResult.SWITCHED) return true;
        if (eq == InventoryHelper.EquipResult.MISSING && !player.isCreative()) {
            chorusBlockedUntil = System.currentTimeMillis() + 30_000;
            endChorus(mc, "Chorus Fruit: hết trái, chuyển sang giàn giáo");
            return false;
        }
        if (!player.isUsingItem()) {
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        mc.options.keyUse.setDown(true); // keep eating until the fruit is consumed
        return true;
    }

    /** @return true while the climb-out routine is handling this tick */
    private boolean tickClimbOut(Minecraft mc, LocalPlayer player, Schematic schematic) {
        ScaffoldManager sm = ScaffoldManager.getInstance();
        if (sm.isPerformingScaffoldJump()) {
            sm.tickScaffoldJump(mc, player);
            statusMessage = "3D Printer: Đang kê chân leo ra khỏi chỗ kẹt";
            return true;
        }
        if (!player.onGround()) {
            PrinterMovement.stop(mc);
            return true;
        }

        double climbed = player.getY() - climbOutStartY;
        boolean out = climbed >= 0.9 && !isEnclosed(mc.level, player);
        boolean timeout = System.currentTimeMillis() - climbOutStartedAt > 30_000 || climbed > 16;
        BlockPos head = player.blockPosition().above();
        boolean ceiling = !LocalPathfinder.isPassable(mc.level, head.above());
        if (out || timeout || ceiling) {
            climbOutActive = false;
            XlrwDebug.log("MOVE/CLIMB_OUT", out ? String.format("Đã ra khỏi vùng kín (leo %.0f block) tại %s", climbed, player.blockPosition().toShortString())
                    : (ceiling ? "Trần bị chặn, chuyển sang đục tường để thoát" : "Dừng leo (quá lâu/quá cao)"));
            StationPlanner.getInstance().dropPlan();
            if (ceiling && !out) {
                handlePitEscape(mc, player, schematic);
            }
            return false;
        }

        Item item = climbOutItem(player);
        if (item == null) {
            climbOutActive = false;
            return false;
        }
        PrinterMovement.stop(mc);
        statusMessage = "3D Printer: Kê " + item.getDescription().getString() + " dưới chân để leo ra";
        sm.triggerPillarStepUp(mc, player, item);
        return true;
    }

    /**
     * Forced recovery requested by the watchdog ({@link com.xlrw.debug.BotMonitor}).
     * CLIMB_OUT: pillar up on scaffolding (only when we are actually shut in, otherwise go straight to BREAK_OUT).
     * BREAK_OUT: open the wall toward the nearest unfinished block.
     * RESET: forget every skip / bad spot / plan and re-plan from scratch.
     */
    private boolean handleEscalation(Minecraft mc, LocalPlayer player, Schematic schematic, com.xlrw.debug.BotMonitor.Escalation esc) {
        ClientLevel level = mc.level;
        switch (esc) {
            case CLIMB_OUT -> {
                if (isEnclosed(level, player) && !climbOutActive) {
                    XlrwDebug.log("MON/RECOVER", "Watchdog: bị nhốt -> kê giàn giáo leo ra");
                    PrinterMovement.stop(mc);
                    StationPlanner.getInstance().dropPlan();
                    currentCandidate = null;
                    return startClimbOut(mc, player);
                }
                XlrwDebug.log("MON/RECOVER", "Watchdog: không bị nhốt (flood lớn) -> làm mới kế hoạch");
                resetAllMemory();
                return false;
            }
            case BREAK_OUT -> {
                climbOutActive = false;
                // Only when really shut in: never knock correct blocks out of the build when we can walk around
                if (!isEnclosed(level, player)) {
                    XlrwDebug.log("MON/RECOVER", "Watchdog: không bị nhốt -> không phá tường, làm mới kế hoạch");
                    resetAllMemory();
                    return false;
                }
                PrinterMovement.stop(mc);
                if (forceBreakOut(mc, player, schematic)) return true;
                XlrwDebug.log("MON/RECOVER", "Watchdog: không có tường nào để phá -> làm mới kế hoạch");
                resetAllMemory();
                return false;
            }
            case RESET -> {
                XlrwDebug.log("MON/RECOVER", "Watchdog: xoá toàn bộ bộ nhớ skip/bad-spot, lập kế hoạch lại từ đầu");
                resetAllMemory();
                if (triggerAutonomousUnstuck(mc, player, schematic, BuilderConfig.getInstance().currentLayer)) {
                    unstuckTransitTicks = 25;
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void resetAllMemory() {
        StationPlanner.getInstance().clear();
        TaskTracker.getInstance().clearPermanentSkips();
        PlacementFinder.clearAllBadPlacementSpots();
        PrinterMovement.resetPath();
        currentCandidate = null;
        noPlanTicks = 0;
    }

    /** Breaks the wall (feet level first, then head level) on the side facing the nearest unfinished block. */
    private boolean forceBreakOut(Minecraft mc, LocalPlayer player, Schematic schematic) {
        ClientLevel level = mc.level;
        BlockPos foot = player.blockPosition();
        BlockPos target = null;
        double best = Double.MAX_VALUE;
        int layerY = schematic.getMinY() + BuilderConfig.getInstance().currentLayer;
        for (Map.Entry<BlockPos, BlockState> e : schematic.getBlocksAtY(layerY)) {
            BuildRules.BuildStatus st = BuildRules.buildStatus(level.getBlockState(e.getKey()), e.getValue());
            if (st != BuildRules.BuildStatus.MISSING && st != BuildRules.BuildStatus.ADJUST) continue;
            double d = e.getKey().distSqr(foot);
            if (d > 4 && d < best) { // not the ones right next to us: those are what we could not reach
                best = d;
                target = e.getKey();
            }
        }
        Direction[] order = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        if (target != null) {
            final BlockPos t = target;
            order = java.util.Arrays.stream(order)
                    .sorted(java.util.Comparator.comparingDouble((Direction d) -> foot.relative(d).distSqr(t)))
                    .toArray(Direction[]::new);
        }
        for (Direction d : order) {
            for (BlockPos wall : new BlockPos[]{foot.relative(d), foot.relative(d).above()}) {
                BlockState st = level.getBlockState(wall);
                if (LocalPathfinder.isPassable(level, wall) || st.getDestroySpeed(level, wall) < 0 || !st.getFluidState().isEmpty()) continue;
                // never open a wall that holds back water/lava
                boolean fluidBehind = false;
                for (Direction n : Direction.values()) {
                    if (!level.getFluidState(wall.relative(n)).isEmpty()) fluidBehind = true;
                }
                if (fluidBehind) continue;
                XlrwDebug.log("MON/RECOVER", "Watchdog: phá " + st.getBlock().getName().getString() + " tại " + wall.toShortString()
                        + " (hướng " + d + (target != null ? " về phía " + target.toShortString() : "") + ") để thoát ra");
                statusMessage = "3D Printer: Phá tường để thoát " + wall.toShortString();
                printerState = PrinterState.DEMOLISHING;
                return DemolishManager.getInstance().startBreaking(mc, player, wall);
            }
        }
        return false;
    }

    /**
     * True only if the player really cannot walk anywhere (a hole / boxed in). Standing on top of a 1-wide wall of
     * the build is NOT a pit: the walker can step down from there.
     */
    public static boolean isTrappedInPit(ClientLevel level, LocalPlayer player) {
        if (level == null || player == null) return false;
        if (!player.onGround() && !player.isInWater()) return false;
        BlockPos foot = player.blockPosition();
        if (level.getBlockState(foot).is(Blocks.SCAFFOLDING) || level.getBlockState(foot.below()).is(Blocks.SCAFFOLDING)) {
            return false;
        }
        // Boxed into a tiny pocket (e.g. a hole inside the build): at most 3 cells can be walked to
        return LocalPathfinder.flood(level, foot, 16, 4).size() <= 3;
    }

    /**
     * Escape from a real pit: pillar up (scaffolding preferred) if the head room is clear, otherwise break the wall
     * block at eye level - but never a block that is correctly part of the build.
     */
    public boolean handlePitEscape(Minecraft mc, LocalPlayer player, Schematic schematic) {
        if (mc.level == null || player == null) return false;

        if (DemolishManager.getInstance().isBreaking()) {
            printerState = PrinterState.DEMOLISHING;
            DemolishManager.getInstance().tickBreaking(mc, player);
            return true;
        }
        if (ScaffoldManager.getInstance().isPerformingScaffoldJump()) {
            if (ScaffoldManager.getInstance().tickScaffoldJump(mc, player)) return true;
        }

        BlockPos foot = player.blockPosition();
        BlockPos head = foot.above();
        boolean canJumpUp = LocalPathfinder.isPassable(mc.level, head.above());
        Item pillarItem = ScaffoldManager.getInstance().hasScaffolding(player)
                ? Items.SCAFFOLDING
                : InventoryHelper.getAvailableSolidSupportItem(player);

        // Only climb out when we are really enclosed (walls at head height on 3+ sides). On top of a tall, thin
        // part of the build there is nothing to climb out of - pillaring there would just keep going up.
        int headWalls = 0;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (!LocalPathfinder.isPassable(mc.level, head.relative(d))) headWalls++;
        }
        boolean enclosed = headWalls >= 3;
        if (!enclosed && !LocalPathfinder.isPassable(mc.level, foot.relative(Direction.NORTH))
                && !LocalPathfinder.isPassable(mc.level, foot.relative(Direction.SOUTH))
                && !LocalPathfinder.isPassable(mc.level, foot.relative(Direction.EAST))
                && !LocalPathfinder.isPassable(mc.level, foot.relative(Direction.WEST))) {
            enclosed = true;
        }
        if (!enclosed) {
            return false;
        }

        if (tryStartChorus(mc, player, "hố")) {
            return true;
        }

        if (pillarItem != null && canJumpUp) {
            XlrwDebug.logMoveObstacle("PIT_ESCAPE_PILLAR", foot, "Kẹt trong hố thật sự: nhảy đặt " + pillarItem.getDescription().getString() + " dưới chân");
            statusMessage = "3D Printer: Thoát hố - nhảy đặt block dưới chân";
            PrinterMovement.stop(mc);
            return ScaffoldManager.getInstance().triggerPillarStepUp(mc, player, pillarItem);
        }

        // Break out sideways. Prefer anything that is NOT a correct build block; only if the pocket is walled in
        // entirely by the build, remove one build block (it simply gets placed again later from outside).
        Direction[] cardinals = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        BlockPos bestWall = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction d : cardinals) {
            for (BlockPos wall : new BlockPos[]{head.relative(d), foot.relative(d)}) {
                BlockState state = mc.level.getBlockState(wall);
                if (state.isAir() || LocalPathfinder.isPassable(mc.level, wall) || state.getDestroySpeed(mc.level, wall) < 0) continue;
                if (!state.getFluidState().isEmpty()) continue;
                boolean correctBuildBlock = false;
                if (schematic != null) {
                    BlockState expected = schematic.getBlock(wall);
                    correctBuildBlock = expected != null && expected.getBlock() == state.getBlock();
                }
                // Opening this side must actually lead somewhere: the cell beyond has to be free or breakable
                BlockPos beyond = wall.relative(d);
                double score = (correctBuildBlock ? 100 : 0)
                        + state.getDestroySpeed(mc.level, wall)
                        + (LocalPathfinder.isPassable(mc.level, beyond) ? 0 : 20);
                if (score < bestScore) {
                    bestScore = score;
                    bestWall = wall;
                }
            }
        }
        if (bestWall != null) {
            XlrwDebug.logMoveObstacle("PIT_ESCAPE_BREAK_WALL", bestWall, "Phá " + mc.level.getBlockState(bestWall).getBlock().getName().getString()
                    + " để thoát hốc kẹt" + (bestScore >= 100 ? " (block của công trình, sẽ đặt lại sau)" : ""));
            statusMessage = "3D Printer: Thoát hố - phá block " + bestWall.toShortString();
            printerState = PrinterState.DEMOLISHING;
            DemolishManager.getInstance().startBreaking(mc, player, bestWall);
            return true;
        }
        return false;
    }

    public BlockPos findNearestMissingBlockOnLayer(LocalPlayer player, Schematic schematic, int layerIndex, ClientLevel level, BlockPos playerPos) {
        BuilderConfig config = BuilderConfig.getInstance();
        int minY = schematic.getMinY();
        int targetY = minY + layerIndex;
        List<Map.Entry<BlockPos, BlockState>> entries = schematic.getBlocksAtY(targetY);

        return findMissingBlockInEntries(player, schematic, entries, level, playerPos, config);
    }

    private BlockPos findMissingBlockInEntries(LocalPlayer player, Schematic schematic, List<Map.Entry<BlockPos, BlockState>> entries,
                                               ClientLevel level, BlockPos playerPos, BuilderConfig config) {
        BlockPos nearestSolidWithNeighbor = null;
        double minSolidWithNeighborDistSq = Double.MAX_VALUE;

        BlockPos nearestSolidAny = null;
        double minSolidAnyDistSq = Double.MAX_VALUE;

        BlockPos nearestMisplaced = null;
        double minMisplacedDistSq = Double.MAX_VALUE;

        BlockPos nearestFluid = null;
        double minFluidDistSq = Double.MAX_VALUE;

        for (Map.Entry<BlockPos, BlockState> entry : entries) {
            BlockPos pos = entry.getKey();

            // Skip blocked or permanently skipped positions so bot doesn't get stuck navigating to them!
            if (TaskTracker.getInstance().isBlocked(pos) || TaskTracker.getInstance().isPermanentlySkipped(pos)) {
                continue;
            }

            BlockState expected = entry.getValue();
            BlockState inWorld = level.getBlockState(pos);

            // Check if position is occupied by a misplaced block that must be demolished
            if (config.autoDemolish && DemolishManager.isMisplaced(level, schematic, pos)) {
                double distSq = pos.distSqr(playerPos);
                if (distSq < minMisplacedDistSq) {
                    minMisplacedDistSq = distSq;
                    nearestMisplaced = pos;
                }
                continue;
            }

            BuildRules.BuildStatus bStatus = BuildRules.buildStatus(inWorld, expected);
            if (bStatus == BuildRules.BuildStatus.MISSING || bStatus == BuildRules.BuildStatus.STACKABLE || bStatus == BuildRules.BuildStatus.ADJUST) {
                // 1. Accessibility: Must have at least one open face so it's not buried/covered
                if (bStatus == BuildRules.BuildStatus.MISSING && !isAccessible(level, pos)) {
                    continue;
                }

                // 2. Gravity check: Falling blocks cannot float over air
                if (expected.getBlock() instanceof net.minecraft.world.level.block.FallingBlock && level.getBlockState(pos.below()).isAir()) {
                    continue;
                }

                net.minecraft.world.item.Item reqItem = InventoryHelper.getItemForBlockState(expected);
                if (player.isCreative() || InventoryHelper.hasItem(player, reqItem) || bStatus == BuildRules.BuildStatus.ADJUST) {
                    double distSq = pos.distSqr(playerPos);
                    if (hasSolidNeighbor(level, pos)) {
                        if (distSq < minSolidWithNeighborDistSq) {
                            minSolidWithNeighborDistSq = distSq;
                            nearestSolidWithNeighbor = pos;
                        }
                    } else {
                        if (distSq < minSolidAnyDistSq) {
                            minSolidAnyDistSq = distSq;
                            nearestSolidAny = pos;
                        }
                    }
                }
            } else {
                BuildRules.FluidStatus fStatus = BuildRules.fluidStatus(inWorld, expected);
                if (fStatus == BuildRules.FluidStatus.PLACE_SOURCE || fStatus == BuildRules.FluidStatus.WATERLOG) {
                    double distSq = pos.distSqr(playerPos);
                    if (distSq < minFluidDistSq) {
                        minFluidDistSq = distSq;
                        nearestFluid = pos;
                    }
                }
            }
        }

        // Prioritize nearby misplaced blocks to demolish with pickaxe first, then solid blocks with existing neighbors!
        if (nearestMisplaced != null && (nearestSolidWithNeighbor == null || minMisplacedDistSq <= minSolidWithNeighborDistSq + 16.0)) {
            return nearestMisplaced;
        }
        if (nearestSolidWithNeighbor != null) return nearestSolidWithNeighbor;
        if (nearestMisplaced != null) return nearestMisplaced;
        if (nearestSolidAny != null) return nearestSolidAny;

        // Strictly only navigate to fluid after all solid blocks and misplaced blocks on this layer are completed!
        return nearestFluid;
    }

    // ------------------------------------------------------------------------------------------------
    // Fast mode for plain blocks
    // ------------------------------------------------------------------------------------------------

    private record FastPending(BlockPos pos, BlockState expected, long sentAt) {}
    private final java.util.ArrayDeque<FastPending> fastPending = new java.util.ArrayDeque<>();

    /** Plain block: no block-state properties at all (no facing, no redstone state, no waterlogging...). */
    private boolean isFastEligible(LocalPlayer player, PlacementCandidate c) {
        BuilderConfig config = BuilderConfig.getInstance();
        if (!config.fastSimpleBlocks || c == null) return false;
        if (c.type != PlacementCandidate.Type.PLACE || c.item == null || c.requiresSneak) return false;
        if (c.throughWall && !PlacementFinder.throughWallAnywhere()) return false;
        BlockState st = c.requiredState;
        if (!st.getProperties().isEmpty()) return false;
        if (st.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return false;
        if (BuildRules.isInteractable(st) || BuildRules.isPiston(st) || BuildRules.isLiquid(st)) return false;
        if (st.isSignalSource() || st.is(Blocks.SCAFFOLDING)) return false;
        return player.getMainHandItem().is(c.item);
    }

    /** One fast placement: snap rotation, send it, click. Confirmation is checked later in {@link #tickFastPending}. */
    private boolean placeFast(Minecraft mc, LocalPlayer player, PlacementCandidate c) {
        BuilderConfig config = BuilderConfig.getInstance();
        Vec3 eye = player.getEyePosition();
        if (c.hitVec.distanceToSqr(eye) > config.reachDistance * config.reachDistance) return false;
        if (config.strictRaycast && !c.throughWall) {
            BlockHitResult t = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, c.hitVec,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
            if (t.getType() == HitResult.Type.BLOCK && !t.getBlockPos().equals(c.neighborPos) && !t.getBlockPos().equals(c.targetPos)
                    && !mc.level.getBlockState(t.getBlockPos()).is(Blocks.SCAFFOLDING)) {
                TaskTracker.getInstance().onAction(c.targetPos, 0); // don't pick the same blocked one every tick
                return false;
            }
        }
        PrinterMovement.stop(mc);
        player.setYRot(c.targetYaw);
        player.setXRot(c.targetPitch);
        player.connection.send(new ServerboundMovePlayerPacket.Rot(c.targetYaw, c.targetPitch, player.onGround()));
        BlockHitResult hit = new BlockHitResult(c.hitVec, c.clickFace, c.neighborPos, false);
        InteractionResult r = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        TaskTracker.getInstance().onAction(c.targetPos, 600);
        fastPending.addLast(new FastPending(c.targetPos.immutable(), c.requiredState, System.currentTimeMillis()));
        printerState = PrinterState.BUILDING;
        statusMessage = "Printing Layer #" + config.currentLayer + " (nhanh): " + c.requiredState.getBlock().getName().getString();
        return r.consumesAction();
    }

    /** Checks fast placements once the server had time to answer. */
    private void tickFastPending(ClientLevel level) {
        long now = System.currentTimeMillis();
        while (!fastPending.isEmpty()) {
            FastPending f = fastPending.peekFirst();
            BlockState w = level.getBlockState(f.pos());
            boolean ok = w.getBlock() == f.expected().getBlock();
            if (ok && now - f.sentAt() >= 100) {
                fastPending.pollFirst();
                XlrwDebug.logBuildSuccess(f.pos(), f.expected().getBlock().getName().getString(), "fast");
                TaskTracker.getInstance().onSuccess(f.pos());
                lastConfirmedPos = f.pos();
            } else if (!ok && now - f.sentAt() > 1000) {
                fastPending.pollFirst();
                XlrwDebug.logBuildReject(f.pos(), f.expected().getBlock().getName().getString(), "Fast mode: server không xác nhận");
            } else {
                break;
            }
        }
    }

    private long lastThroughWallAt = 0;
    private BlockPos rotSyncPos = null;
    private float rotSyncYaw, rotSyncPitch;
    private long rotSyncAt = 0;

    private void placeCandidate(Minecraft mc, LocalPlayer player, PlacementCandidate candidate) {
        BuilderConfig config = BuilderConfig.getInstance();

        // 0. Through-wall placements: at most one every 1.5 s, so it never looks like a burst of impossible clicks
        Vec3 eyePos = player.getEyePosition();
        if (candidate.throughWall) {
            // Pockets-only mode keeps the old 1.5 s spacing; anywhere mode places at the normal speed
            if (!PlacementFinder.throughWallAnywhere()) {
                long nowTw = System.currentTimeMillis();
                if (nowTw - lastThroughWallAt < 1_500) {
                    nextActionTime = lastThroughWallAt + 1_500;
                    return;
                }
                lastThroughWallAt = nowTw;
            }
            XlrwDebug.logRateLimited("THROUGH_WALL", 2000, "BUILD/THROUGH_WALL", "Đặt xuyên tường "
                    + candidate.requiredState.getBlock().getName().getString() + " tại " + candidate.targetPos.toShortString());
        }
        // 1. Line of Sight Check: ensure there is no obstructing solid wall between eye and hitVec
        if (config.strictRaycast && !candidate.throughWall) {
            net.minecraft.world.level.ClipContext clipContext = new net.minecraft.world.level.ClipContext(
                    eyePos, candidate.hitVec, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player);
            BlockHitResult testHit = mc.level.clip(clipContext);
            if (testHit.getType() == HitResult.Type.BLOCK
                    && !testHit.getBlockPos().equals(candidate.neighborPos)
                    && !testHit.getBlockPos().equals(candidate.targetPos)
                    && !mc.level.getBlockState(testHit.getBlockPos()).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) {
                // There is an actual wall obstructing line of sight: count it as an attempt (throttles + eventually
                // blacklists) instead of instantly re-selecting the very same block in an endless loop
                XlrwDebug.logBuildReject(candidate.targetPos, candidate.requiredState.getBlock().getName().getString(),
                        "LOS blocked by " + mc.level.getBlockState(testHit.getBlockPos()).getBlock().getName().getString() + " at " + testHit.getBlockPos().toShortString());
                TaskTracker.getInstance().onAction(candidate.targetPos, 0);
                currentCandidate = null;
                return;
            }
        }

        // 2. Synchronize view rotation packet before action
        // For directional blocks and all bucket actions (which rely strictly on view raycast), guarantee exact rotation sync!
        if (BuildRules.hasOrientation(candidate.requiredState) || candidate.isBucketAction()) {
            player.setYRot(candidate.targetYaw);
            player.setXRot(candidate.targetPitch);
            player.yRotO = candidate.targetYaw;
            player.xRotO = candidate.targetPitch;
            player.yHeadRot = candidate.targetYaw;
            player.yHeadRotO = candidate.targetYaw;
            player.yBodyRot = candidate.targetYaw;
        }
        player.connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), player.onGround()));

        // 2b. The server only copies the body yaw into the HEAD yaw during its next player tick, and pistons /
        //     observers / dispensers / droppers / hoppers take their facing from the head yaw. Clicking in the same
        //     tick as the rotation packet used the OLD head direction -> facing off by 90 degrees whenever the bot
        //     had just turned. So: send the rotation, wait ~2 ticks with the exact same aim, then click.
        if ((candidate.type == PlacementCandidate.Type.PLACE && BuildRules.hasOrientation(candidate.requiredState))
                || candidate.isBucketAction()) {
            long nowMs = System.currentTimeMillis();
            // Buckets: the server traces the pour from where it thinks we stand and look - stand completely still
            if (candidate.isBucketAction()) {
                Vec3 v = player.getDeltaMovement();
                if (v.x * v.x + v.z * v.z > 1.0e-4 || !player.onGround()) {
                    PrinterMovement.stop(mc);
                    rotSyncPos = null; // restart the settle wait once we stand still
                    nextActionTime = nowMs + 50;
                    return;
                }
            }
            boolean sameAim = rotSyncPos != null && rotSyncPos.equals(candidate.targetPos)
                    && Math.abs(Mth.wrapDegrees(rotSyncYaw - candidate.targetYaw)) < 0.01f
                    && Math.abs(rotSyncPitch - candidate.targetPitch) < 0.01f;
            if (!sameAim) {
                rotSyncPos = candidate.targetPos;
                rotSyncYaw = candidate.targetYaw;
                rotSyncPitch = candidate.targetPitch;
                rotSyncAt = nowMs;
                nextActionTime = nowMs + 110;
                return;
            }
            if (nowMs - rotSyncAt < 100) {
                nextActionTime = rotSyncAt + 110;
                return;
            }
        }

        // 2c. Buckets: check the exact view ray the server will use. It must land where we mean to pour, otherwise
        //     the liquid goes on top of / beside the wrong block.
        if (candidate.isBucketAction()) {
            boolean fill = candidate.type == PlacementCandidate.Type.BUCKET_FILL || candidate.type == PlacementCandidate.Type.DRAIN;
            BlockHitResult ray = PlacementFinder.viewRay(mc.level, player, player.getEyePosition(), player.getYRot(), player.getXRot(),
                    config.reachDistance + 0.5, fill ? net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY : net.minecraft.world.level.ClipContext.Fluid.NONE);
            boolean ok;
            if (candidate.type == PlacementCandidate.Type.FLUID_SOURCE) {
                ok = ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(candidate.neighborPos) && ray.getDirection() == candidate.clickFace;
            } else {
                ok = ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(candidate.targetPos);
            }
            if (!ok) {
                XlrwDebug.logBuildReject(candidate.targetPos, candidate.requiredState.getBlock().getName().getString(),
                        "Tia nhìn của xô không trúng đúng chỗ (trúng " + (ray.getType() == HitResult.Type.BLOCK ? ray.getBlockPos().toShortString() + " mặt " + ray.getDirection() : "không khí") + "), không đổ");
                TaskTracker.getInstance().onAction(candidate.targetPos, 0);
                currentCandidate = null;
                rotSyncPos = null;
                return;
            }
        }

        // 3. Sneak if neighbour is interactable (e.g. chest, door, trapdoor, furnace) to prevent opening GUI / toggling
        if (candidate.requiresSneak) {
            mc.options.keyShift.setDown(true);
            if (player.input != null) player.input.shiftKeyDown = true;
            player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
        }

        // 4. Construct authentic BlockHitResult (Meteor & Baritone style) targeting candidate.hitVec on candidate.clickFace
        BlockHitResult hitResult;
        if (candidate.type == PlacementCandidate.Type.ADJUST
                || candidate.type == PlacementCandidate.Type.STACK
                || candidate.type == PlacementCandidate.Type.WATERLOG
                || candidate.type == PlacementCandidate.Type.BUCKET_FILL) {
            hitResult = new BlockHitResult(candidate.hitVec, candidate.clickFace, candidate.targetPos, false);
        } else {
            hitResult = new BlockHitResult(candidate.hitVec, candidate.clickFace, candidate.neighborPos, false);
        }

        // 4b. Last check for facing-sensitive blocks: simulate EXACTLY what is about to be sent (rotation + hit).
        // If vanilla would not give the schematic orientation, do not place a block that must be broken again.
        if (candidate.type == PlacementCandidate.Type.PLACE && BuildRules.hasOrientation(candidate.requiredState) && candidate.item != null) {
            PlacementSimulator.Result sim = PlacementSimulator.simulate(mc.level, player, new net.minecraft.world.item.ItemStack(candidate.item),
                    hitResult, player.getYRot(), player.getXRot(), candidate.requiredState.getBlock());
            if (sim == null || !sim.pos().equals(candidate.targetPos) || !BuildRules.orientationMatches(sim.state(), candidate.requiredState)) {
                XlrwDebug.logBuildReject(candidate.targetPos, candidate.requiredState.getBlock().getName().getString(),
                        "Kiểm tra trước khi click: sẽ ra sai hướng (" + (sim != null ? sim.state() : "null") + "), bỏ qua lần này");
                if (candidate.requiresSneak) {
                    mc.options.keyShift.setDown(false);
                    if (player.input != null) player.input.shiftKeyDown = false;
                    player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
                }
                TaskTracker.getInstance().onAction(candidate.targetPos, 0);
                currentCandidate = null;
                return;
            }
        }

        // Liquid sources around the pour BEFORE clicking: only sources that appear afterwards count as a spill
        java.util.Set<BlockPos> sourcesBefore = null;
        if (candidate.type == PlacementCandidate.Type.FLUID_SOURCE || candidate.type == PlacementCandidate.Type.WATERLOG) {
            sourcesBefore = new java.util.HashSet<>();
            for (BlockPos q : BlockPos.betweenClosed(candidate.targetPos.offset(-2, -1, -2), candidate.targetPos.offset(2, 2, 2))) {
                if (mc.level.getFluidState(q).isSource()) sourcesBefore.add(q.immutable());
            }
        }

        // 5. Perform click action
        InteractionHand hand = InteractionHand.MAIN_HAND;
        // Anti-fall keeps SNEAK held at edges; a sneaking right-click would not toggle/adjust a block
        boolean unsneakForClick = candidate.type == PlacementCandidate.Type.ADJUST && player.isShiftKeyDown();
        if (unsneakForClick) {
            player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
        }
        InteractionResult useResult = mc.gameMode.useItemOn(player, hand, hitResult);
        if (unsneakForClick) {
            player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
        }
        if (!useResult.consumesAction()) {
            mc.gameMode.useItem(player, hand);
        }
        player.swing(hand);
        XlrwDebug.logBuildAttempt(candidate.targetPos, candidate.requiredState.getBlock().getName().getString(),
                "Type=" + candidate.type + " face=" + candidate.clickFace + " targetOn=" + (candidate.type == PlacementCandidate.Type.ADJUST || candidate.type == PlacementCandidate.Type.STACK || candidate.type == PlacementCandidate.Type.WATERLOG || candidate.type == PlacementCandidate.Type.BUCKET_FILL ? candidate.targetPos.toShortString() : candidate.neighborPos.toShortString()) + " result=" + useResult);

        // 6. Release sneak (unless we stand at an edge: then anti-fall keeps it held)
        if (candidate.requiresSneak && !PrinterMovement.isNearDangerousEdge(mc.level, player)) {
            mc.options.keyShift.setDown(false);
            if (player.input != null) player.input.shiftKeyDown = false;
            player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
        }

        // 7. Track with TaskTracker to prevent repeated click spam
        int actionDelay = Math.max(120, config.placeDelayMs);
        if (candidate.type == PlacementCandidate.Type.FLUID_SOURCE || candidate.type == PlacementCandidate.Type.BUCKET_FILL) actionDelay = Math.max(300, actionDelay);
        TaskTracker.getInstance().onAction(candidate.targetPos, actionDelay);

        // 8. Humanized delay
        int delay = actionDelay;
        if (config.jitterMs > 0) {
            delay += RANDOM.nextInt(config.jitterMs * 2 + 1) - config.jitterMs;
        }

        consecutiveBlocks++;
        if (consecutiveBlocks >= 25 + RANDOM.nextInt(15)) {
            delay += 60 + RANDOM.nextInt(80);
            consecutiveBlocks = 0;
        }

        nextActionTime = System.currentTimeMillis() + Math.max(100, delay);

        // Await server placement confirmation: DO NOT pre-fetch or whip camera to next block prematurely!
        pendingPlacementPos = candidate.targetPos;
        pendingExpectedState = candidate.requiredState;
        pendingPlacementType = candidate.type;
        pendingYaw = player.getYRot();
        pendingPitch = player.getXRot();
        pendingFace = candidate.clickFace;
        pendingClickPos = hitResult.getBlockPos();
        pendingSentAt = System.currentTimeMillis();
        pendingTimeout = System.currentTimeMillis() + (candidate.isBucketAction() ? 450 : 350);
        if (candidate.type == PlacementCandidate.Type.FLUID_SOURCE || candidate.type == PlacementCandidate.Type.WATERLOG) {
            spillChecks.add(new SpillCheck(candidate.targetPos, candidate.item == Items.LAVA_BUCKET, System.currentTimeMillis() + 500,
                    sourcesBefore != null ? sourcesBefore : java.util.Set.of()));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Escaping lava / water currents
    // ------------------------------------------------------------------------------------------------

    private int fluidStuckTicks = 0;
    private Vec3 fluidRef = null;
    private boolean fluidEscaping = false;

    /** @return true while steering out of lava / a water current toward the nearest dry standing cell */
    // Fluid "trouble": spans the bot bobbing in and out of the same pool, so climbing out and sliding back in does not
    // reset the clock. Too long in a lava pit (or a water hole) -> eat a chorus fruit.
    private long fluidTroubleSince = 0, lastInFluidAt = 0;
    private int fluidExitTicks = 0;
    private Vec3 fluidTroubleRef = null;
    private boolean fluidTroubleLava = false;

    private boolean tickFluidEscape(Minecraft mc, LocalPlayer player) {
        boolean inLava = player.isInLava();
        boolean inWater = player.isInWater();
        long nowMs = System.currentTimeMillis();
        if (inLava || inWater) {
            if (fluidTroubleSince == 0 || fluidTroubleRef == null || player.position().distanceToSqr(fluidTroubleRef) > 16) {
                fluidTroubleSince = nowMs;
                fluidTroubleRef = player.position();
                fluidTroubleLava = false;
            }
            fluidTroubleLava |= inLava;
            lastInFluidAt = nowMs;
            long limit = fluidTroubleLava ? 6_000 : 25_000; // water: swimming out (holding space) usually works
            if (nowMs - fluidTroubleSince > limit && !chorusActive
                    && tryStartChorus(mc, player, fluidTroubleLava ? "hố lava (không leo ra được)" : "hố nước (không leo ra được)")) {
                fluidEscaping = false;
                fluidTroubleSince = 0;
                mc.options.keyJump.setDown(false);
                mc.options.keyUp.setDown(false);
                return true;
            }
        } else if (nowMs - lastInFluidAt > 4_000) {
            fluidTroubleSince = 0;
        }
        if (!inLava && !inWater && fluidEscaping && !player.onGround() && fluidExitTicks < 30) {
            // Head is out but the feet are not on the ledge yet: keep HOLDING jump + forward (a single tap only bobs
            // at the surface and the bot sinks straight back in)
            fluidExitTicks++;
            mc.options.keyJump.setDown(true);
            mc.options.keyUp.setDown(true);
            statusMessage = "3D Printer: Leo lên khỏi nước (giữ Space)";
            return true;
        }
        if (!inLava && !inWater) {
            fluidExitTicks = 0;
            if (fluidEscaping) {
                mc.options.keyJump.setDown(false);
                fluidEscaping = false;
                PrinterMovement.stop(mc);
                PrinterMovement.resetPath();
                StationPlanner.getInstance().dropPlan();
                XlrwDebug.log("MOVE/FLUID_ESCAPE", "Đã ra khỏi chất lỏng tại " + player.blockPosition().toShortString());
            }
            fluidStuckTicks = 0;
            fluidRef = null;
            return false;
        }
        if (fluidRef == null || player.position().distanceToSqr(fluidRef) > 1.0) {
            fluidRef = player.position();
            fluidStuckTicks = 0;
        }
        fluidStuckTicks++;
        // Water: only take over when the normal walker is not getting anywhere (a current pushing us back)
        if (!inLava && !fluidEscaping && (fluidStuckTicks < 40 || com.xlrw.debug.BotMonitor.sinceLastPlaced() < 3_000)) return false;

        ClientLevel level = mc.level;
        BlockPos feet = player.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    BlockPos p = feet.offset(dx, dy, dz);
                    if (!LocalPathfinder.isValidStand(level, p)) continue;
                    if (!level.getFluidState(p).isEmpty() || !level.getFluidState(p.above()).isEmpty()
                            || !level.getFluidState(p.below()).isEmpty()) continue;
                    // Prefer getting OUT upward; going down (into a hole / shaft next to the pool) only as a last resort
                    double d = Vec3.atBottomCenterOf(p).distanceToSqr(player.position()) + Math.max(0, dy) * 0.5 + Math.max(0, -dy) * 12.0;
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        if (!fluidEscaping) {
            fluidEscaping = true;
            XlrwDebug.log("MOVE/FLUID_ESCAPE", (inLava ? "Đang ở trong LAVA" : "Bị kẹt trong dòng nước") + " tại " + feet.toShortString()
                    + " -> bơi ra " + (best != null ? best.toShortString() : "(chưa thấy chỗ khô, bơi lên)"));
        }
        statusMessage = "3D Printer: Thoát khỏi " + (inLava ? "lava" : "dòng nước");
        mc.options.keySprint.setDown(false);
        mc.options.keyShift.setDown(false);
        if (player.input != null) player.input.shiftKeyDown = false;
        mc.options.keyJump.setDown(true); // swim up / hop onto the ledge
        if (best != null) {
            double tx = best.getX() + 0.5 - player.getX(), tz = best.getZ() + 0.5 - player.getZ();
            float yaw = (float) (Math.toDegrees(Math.atan2(tz, tx)) - 90.0);
            player.setYRot(yaw);
            mc.options.keyUp.setDown(Math.hypot(tx, tz) > 0.2);
        } else {
            mc.options.keyUp.setDown(false);
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------
    // Spilled liquid: a pour that landed in the wrong cell is plugged with a throw-away block (broken later)
    // ------------------------------------------------------------------------------------------------

    private record SpillCheck(BlockPos center, boolean lava, long at, java.util.Set<BlockPos> before) {}
    private final java.util.List<SpillCheck> spillChecks = new java.util.ArrayList<>();
    private final java.util.LinkedHashSet<BlockPos> spills = new java.util.LinkedHashSet<>();
    private long spillGiveUpAt = 0;

    private void scanSpills(ClientLevel level, Schematic schematic) {
        long now = System.currentTimeMillis();
        java.util.Iterator<SpillCheck> it = spillChecks.iterator();
        while (it.hasNext()) {
            SpillCheck c = it.next();
            if (now < c.at()) continue;
            it.remove();
            for (BlockPos p : BlockPos.betweenClosed(c.center().offset(-2, -1, -2), c.center().offset(2, 2, 2))) {
                net.minecraft.world.level.material.FluidState fs = level.getFluidState(p);
                if (fs.isEmpty() || !fs.isSource()) continue;
                if (c.before().contains(p)) continue; // was already there before we poured
                if (fs.is(net.minecraft.tags.FluidTags.LAVA) != c.lava()) continue;
                BlockState ws = level.getBlockState(p);
                BlockState exp = schematic.getBlock(p);
                if (!(ws.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) {
                    // the bucket waterlogged a block that should stay dry
                    if (exp != null && exp.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                            && !exp.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
                        XlrwDebug.log("FLUID/SPILL", "Xô nước làm ngập nhầm block " + ws.getBlock().getName().getString() + " tại " + p.toShortString()
                                + " (không thể tự rút nếu không có xô rỗng)");
                    }
                    continue;
                }
                boolean wanted = exp != null && ((c.lava() && exp.is(Blocks.LAVA)) || (!c.lava() && exp.is(Blocks.WATER)));
                if (wanted) continue;
                if (spills.add(p.immutable())) {
                    XlrwDebug.log("FLUID/SPILL", (c.lava() ? "Lava" : "Nước") + " bị đổ nhầm vào " + p.toShortString()
                            + " (bản vẽ cần " + (exp == null ? "ngoài bản vẽ" : exp.getBlock().getName().getString()) + ") -> lấp bằng block tạm rồi phá");
                }
            }
        }
    }

    /** @return true while busy plugging a spilled liquid source */
    private boolean tickSpillFix(Minecraft mc, LocalPlayer player, Schematic schematic) {
        ClientLevel level = mc.level;
        scanSpills(level, schematic);
        if (spills.isEmpty()) return false;
        spills.removeIf(p -> {
            net.minecraft.world.level.material.FluidState fs = level.getFluidState(p);
            return fs.isEmpty() || !fs.isSource() || !(level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.LiquidBlock);
        });
        if (spills.isEmpty()) return false;
        long now = System.currentTimeMillis();
        if (spillGiveUpAt == 0) spillGiveUpAt = now + 20_000;
        if (now > spillGiveUpAt) {
            XlrwDebug.log("FLUID/SPILL", "Không lấp được chỗ chất lỏng đổ nhầm sau 20s, bỏ qua");
            spills.clear();
            spillGiveUpAt = 0;
            return false;
        }
        Item plug = InventoryHelper.getAvailableSolidSupportItem(player);
        if (plug == null && player.isCreative()) plug = Items.COBBLESTONE;
        if (plug == null) {
            spills.clear();
            return false;
        }
        double reach = BuilderConfig.getInstance().reachDistance;
        Vec3 eye = player.getEyePosition();
        for (BlockPos p : spills) {
            if (eye.distanceToSqr(Vec3.atCenterOf(p)) > reach * reach) continue;
            BlockState plugState = ((net.minecraft.world.item.BlockItem) plug).getBlock().defaultBlockState();
            PlacementCandidate c = PlacementFinder.findValidNeighborPlacement(level, player, p, plugState, plug, eye, reach * reach,
                    BuilderConfig.getInstance().strictRaycast);
            if (c == null) continue;
            InventoryHelper.EquipResult eq = InventoryHelper.equipItem(player, plug);
            if (eq == InventoryHelper.EquipResult.SWITCHED) {
                nextActionTime = now + 60;
                return true;
            }
            if (eq == InventoryHelper.EquipResult.MISSING && !player.isCreative()) {
                spills.clear();
                return false;
            }
            PrinterMovement.stop(mc);
            player.setYRot(c.targetYaw);
            player.setXRot(c.targetPitch);
            player.connection.send(new ServerboundMovePlayerPacket.Rot(c.targetYaw, c.targetPitch, player.onGround()));
            BlockHitResult hit = new BlockHitResult(c.hitVec, c.clickFace, c.neighborPos, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            TempBlockTracker.record(p, plug);
            XlrwDebug.log("FLUID/PLUG", "Lấp " + p.toShortString() + " bằng " + plug.getDescription().getString());
            statusMessage = "3D Printer: Lấp chất lỏng đổ nhầm tại " + p.toShortString();
            spills.remove(p);
            nextActionTime = now + 150;
            if (spills.isEmpty()) spillGiveUpAt = 0;
            return true;
        }
        // nothing in reach: walk next to the first one
        BlockPos first = spills.iterator().next();
        PrinterMovement.moveTo(mc, Vec3.atBottomCenterOf(first.above()), 2.5);
        statusMessage = "3D Printer: Đi tới chỗ chất lỏng đổ nhầm " + first.toShortString();
        return true;
    }

    public void placeSingleStep(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        PlacementCandidate candidate = PlacementFinder.findBestCandidate();
        if (candidate != null) {
            if (candidate.item != null) {
                InventoryHelper.equipItem(player, candidate.item);
            }
            placeCandidate(mc, player, candidate);
        }
    }

    public void reset() {
        printerState = PrinterState.IDLE;
        currentCandidate = null;
        pendingPlacementPos = null;
        pendingExpectedState = null;
        lastConfirmedPos = null;
        standstillTicks = 0;
        noPlanTicks = 0;
        consecutiveBlocks = 0;
        candidateAlignTicks = 0;
        StationPlanner.getInstance().clear();
        ScaffoldNavigator.reset();
        TempBlockTracker.clear();
        towerWatchPlan = null;
        climbOutActive = false;
        WorkZoneManager.getInstance().clearZone();
        DemolishManager.getInstance().stopBreaking(Minecraft.getInstance());
        PrinterMovement.stop(Minecraft.getInstance());
        PrinterMovement.resetPath();
        TaskTracker.getInstance().clear();
        PlacementFinder.clearAllBadPlacementSpots();
    }
}
