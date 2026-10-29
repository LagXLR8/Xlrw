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

    public PrinterState getPrinterState() {
        return printerState;
    }

    public void onClientTick(Minecraft mc) {
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
        if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
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
            if (isLayerComplete(schematic, config.currentLayer, mc.level)) {
                int maxLayer = schematic.getMaxY() - schematic.getMinY();
                if (config.currentLayer < maxLayer) {
                    config.currentLayer++;
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
                } else if (pendingExpectedState.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                        && pendingExpectedState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
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
                boolean wrongOrientation = pendingExpectedState != null
                        && BuildRules.hasOrientation(pendingExpectedState)
                        && !BuildRules.orientationMatches(inWorld, pendingExpectedState)
                        && !BlockStateAdjuster.needsAdjustment(inWorld, pendingExpectedState);
                if (wrongOrientation) {
                    XlrwDebug.log("BUILD/WRONG_ORIENTATION", "Đặt " + pendingExpectedState.getBlock().getName().getString()
                            + " tại " + pendingPlacementPos.toShortString() + " sai hướng từ vị trí đứng " + player.blockPosition().toShortString() + "!");
                    PlacementFinder.recordBadPlacementSpot(pendingPlacementPos, player.blockPosition());
                    StationPlanner.getInstance().recordBadPlacementStation(pendingPlacementPos, player.blockPosition());
                } else {
                    PlacementFinder.clearBadPlacementSpot(pendingPlacementPos);
                }

                // Block placement acknowledged and verified by server!
                XlrwDebug.logBuildSuccess(pendingPlacementPos, pendingExpectedState != null ? pendingExpectedState.getBlock().getName().getString() : (pendingPlacementType == PlacementCandidate.Type.BUCKET_FILL ? "water_refill" : "block"), "Server verified in-world");
                TaskTracker.getInstance().onSuccess(pendingPlacementPos);
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
            if (movedDistSq < 0.004) {
                standstillTicks++;
            } else {
                standstillTicks = Math.max(0, standstillTicks - 3);
            }
        } else {
            standstillTicks = 0;
        }
        lastWatchdogPos = player.position();

        // Truly blocked on the way (~2s without moving): give up on THIS station/anchor and re-plan a different one.
        if (standstillTicks > 40 && printerState != PrinterState.REFILLING_CHEST) {
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

        // A) If accidentally elevated on old scaffolding when working on a ground layer or lower floor:
        // Immediately descend down the scaffold shaft to ground level!
        if (isGroundLayer && onScaffold && player.getY() > activeLayerY + 1.2) {
            statusMessage = "3D Printer: Descending scaffolding to ground for Layer #" + config.currentLayer;
            ScaffoldNavigator.releaseKeys(mc, player);
            mc.options.keyShift.setDown(true);
            if (player.input != null) player.input.shiftKeyDown = true;
            return;
        }

        // B) Persist climbing if currently climbing tower in committed plan
        if (activePlan != null && activePlan.phase == StationPlanner.PlanPhase.CLIMB_TOWER) {
            printerState = PrinterState.CLIMBING_SCAFFOLD;
            statusMessage = "3D Printer: Climbing scaffolding tower to Y=" + activePlan.targetElevation;
            int topY = ScaffoldManager.getInstance().getColumnTopY(mc, activePlan.scaffoldTowerPos);
            boolean arrivedAtTop = ScaffoldNavigator.climb(mc, player, activePlan.scaffoldTowerPos, topY, activePlan.targetElevation);
            if (arrivedAtTop) {
                if (activePlan.arrived(player)) {
                    activePlan.transitionTo(StationPlanner.PlanPhase.AT_STATION);
                } else {
                    activePlan.transitionTo(StationPlanner.PlanPhase.BRIDGE_TO_STATION);
                }
            }
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

        printerState = PrinterState.MOVING_TO_BLOCK;
        StationPlanner.Plan plan = planner.ensurePlan(mc.level, player, schematic, config.currentLayer, lastConfirmedPos);

        if (plan == null) {
            // Work exists but no standing spot is reachable right now. Stand calmly; do NOT wander.
            PrinterMovement.stop(mc);
            noPlanTicks++;
            statusMessage = "3D Printer: Looking for a reachable standing spot...";

            // Active Floating / Support handling:
            // If any blocks on this layer lack solid support (floating), construct scaffolding columns or support beams!
            if (SupportBeamManager.getInstance().handleFloatingSupport(mc, player, schematic, activeLayerY)) {
                noPlanTicks = 0;
                return;
            }

            // If SupportBeamManager is currently actively managing a floating target, stay calm and do NOT wander
            if (SupportBeamManager.getInstance().hasActiveFloatingTarget()) {
                noPlanTicks = 0;
                return;
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

    private void executePlanMovement(Minecraft mc, LocalPlayer player, Schematic schematic, StationPlanner.Plan plan, StationPlanner planner) {
        BuilderConfig config = BuilderConfig.getInstance();
        ScaffoldManager sm = ScaffoldManager.getInstance();

        boolean checkArrived = plan.planKind != StationPlanner.PlanKind.SCAFFOLD_EXPEDITION 
                || (plan.phase != StationPlanner.PlanPhase.APPROACH_TOWER_BASE && plan.phase != StationPlanner.PlanPhase.BUILD_TOWER);
        if (checkArrived && plan.arrived(player)) {
            PrinterMovement.stop(mc);
            ScaffoldNavigator.releaseKeys(mc, player);
            plan.transitionTo(StationPlanner.PlanPhase.AT_STATION);
            statusMessage = "3D Printer: At station, waiting for " + plan.anchor.toShortString();
            planner.tickArrivedIdle();
            return;
        }

        if (plan.phase == StationPlanner.PlanPhase.APPROACH_STATION || plan.phase == StationPlanner.PlanPhase.APPROACH_TOWER_BASE) {
            if (isTrappedInPit(mc.level, player)) {
                if (handlePitEscape(mc, player, schematic)) {
                    return;
                }
            }
        }

        switch (plan.phase) {
            case APPROACH_STATION:
                printerState = PrinterState.MOVING_TO_BLOCK;
                statusMessage = "3D Printer: Walking directly to station for " + plan.anchor.toShortString();
                PrinterMovement.moveTo(mc, plan.station, 0.45, false, false);
                break;

            case APPROACH_TOWER_BASE:
                printerState = PrinterState.MOVING_TO_BLOCK;
                BlockPos towerStandPos = sm.getTowerStandPos(mc.level, plan.scaffoldTowerPos);
                Vec3 standCenter = new Vec3(towerStandPos.getX() + 0.5, towerStandPos.getY(), towerStandPos.getZ() + 0.5);
                double distToStand = Math.hypot(player.getX() - standCenter.x, player.getZ() - standCenter.z);
                double distToBase = Math.hypot(player.getX() - (plan.scaffoldTowerPos.getX() + 0.5), player.getZ() - (plan.scaffoldTowerPos.getZ() + 0.5));
                if (distToStand <= 0.85 || (distToBase >= 0.95 && distToBase <= 2.2)) {
                    PrinterMovement.stop(mc);
                    plan.transitionTo(StationPlanner.PlanPhase.BUILD_TOWER);
                } else {
                    statusMessage = "3D Printer: Moving to scaffolding tower staging spot at " + towerStandPos.toShortString();
                    PrinterMovement.moveTo(mc, standCenter, 0.35, false, false);
                }
                break;

            case BUILD_TOWER:
                printerState = PrinterState.CLIMBING_SCAFFOLD;
                double curDistToBase = Math.hypot(player.getX() - (plan.scaffoldTowerPos.getX() + 0.5), player.getZ() - (plan.scaffoldTowerPos.getZ() + 0.5));
                if (curDistToBase < 0.88) {
                    // Inside column: step back to tower staging spot to click exterior side face
                    BlockPos stand = sm.getTowerStandPos(mc.level, plan.scaffoldTowerPos);
                    PrinterMovement.moveTo(mc, new Vec3(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5), 0.3, false, false);
                    return;
                }
                int neededTopY = Math.max(plan.scaffoldTowerPos.getY(), Math.min(schematic.getMaxY(), plan.targetElevation - 1));
                int currentTopY = sm.getColumnTopY(mc, plan.scaffoldTowerPos);
                if (currentTopY >= neededTopY) {
                    plan.transitionTo(StationPlanner.PlanPhase.CLIMB_TOWER);
                } else {
                    statusMessage = "3D Printer: Raising scaffolding tower to Y=" + neededTopY;
                    ScaffoldNavigator.releaseKeys(mc, player);
                    sm.extendScaffoldColumn(mc, player, plan.scaffoldTowerPos, neededTopY);
                }
                break;

            case CLIMB_TOWER:
                printerState = PrinterState.CLIMBING_SCAFFOLD;
                statusMessage = "3D Printer: Climbing scaffolding tower to Y=" + plan.targetElevation;
                int topY = sm.getColumnTopY(mc, plan.scaffoldTowerPos);
                boolean arrivedAtTop = ScaffoldNavigator.climb(mc, player, plan.scaffoldTowerPos, topY, plan.targetElevation);
                if (arrivedAtTop) {
                    if (plan.arrived(player)) {
                        plan.transitionTo(StationPlanner.PlanPhase.AT_STATION);
                    } else {
                        plan.transitionTo(StationPlanner.PlanPhase.BRIDGE_TO_STATION);
                    }
                }
                break;

            case BRIDGE_TO_STATION:
                printerState = PrinterState.CLIMBING_SCAFFOLD;
                statusMessage = "3D Printer: Bridging horizontally toward station " + plan.stationBlock.toShortString();
                boolean canWalk = PrinterMovement.canWalkDirectly(mc.level, player.position(), plan.stationBlock);
                if (canWalk) {
                    PrinterMovement.moveTo(mc, plan.station, 0.35, false, false);
                } else {
                    ScaffoldNavigator.BridgeResult res = ScaffoldNavigator.bridge(mc, player, schematic, plan.stationBlock);
                    if (res == ScaffoldNavigator.BridgeResult.ARRIVED) {
                        plan.transitionTo(StationPlanner.PlanPhase.AT_STATION);
                    } else if (res == ScaffoldNavigator.BridgeResult.BLOCKED) {
                        PrinterMovement.stop(mc);
                        ScaffoldNavigator.releaseKeys(mc, player);
                        planner.failPlan(8000);
                        statusMessage = "3D Printer: Cannot bridge to station from this tower, re-routing...";
                    }
                }
                break;

            case AT_STATION:
                PrinterMovement.stop(mc);
                ScaffoldNavigator.releaseKeys(mc, player);
                statusMessage = "3D Printer: Ready at station for " + plan.anchor.toShortString();
                break;
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

    private long scaffoldRetryAt = 0;
    private Vec3 lastTowerWatchdogPos = Vec3.ZERO;
    private int towerStuckTicks = 0;

    private boolean trackTowerStuck(Minecraft mc, LocalPlayer player, BlockPos pillarPos) {
        if (player.position().distanceToSqr(lastTowerWatchdogPos) < 0.004) {
            towerStuckTicks++;
            if (towerStuckTicks > 25) {
                towerStuckTicks = 0;
                ScaffoldManager.getInstance().blacklistPillar(pillarPos, 25_000);
                BuilderConfig.getInstance().scaffoldPillarPos = null;
                BuilderConfig.save();
                PrinterMovement.resetPath();
                PrinterMovement.stop(mc);
                statusMessage = "3D Printer: Tower blocked by wall, re-routing to a reachable spot...";
                return true;
            }
        } else {
            towerStuckTicks = Math.max(0, towerStuckTicks - 2);
        }
        lastTowerWatchdogPos = player.position();
        return false;
    }



    /**
     * Raises the player to stand at feet level {@code targetElevation} using a scaffolding tower:
     * 1. build the column until its top block is at targetElevation - 1 (the player then stands on it),
     * 2. walk into the shaft, hold JUMP only while inside it, and step onto the top.
     *
     * @return true while this routine is handling the tick; false when finished or when no tower is possible
     *         (the caller then continues with normal building).
     */
    private boolean handleScaffoldingClimb(Minecraft mc, LocalPlayer player, Schematic schematic, int targetElevation, BlockPos targetWorkPos) {
        BuilderConfig config = BuilderConfig.getInstance();
        ScaffoldManager sm = ScaffoldManager.getInstance();
        long now = System.currentTimeMillis();

        // Avoid re-running the expensive shaft search every tick when nothing is available
        if (config.scaffoldPillarPos == null && now < scaffoldRetryAt) return false;

        BlockPos scaffoldPos = sm.getScaffoldPos(schematic, player, targetElevation, targetWorkPos);
        if (scaffoldPos == null || scaffoldPos.equals(BlockPos.ZERO)) {
            statusMessage = "3D Printer: No clear spot for a scaffolding tower";
            scaffoldRetryAt = now + 3000;
            return false;
        }

        // The top scaffolding block is the platform; the player stands on top of it.
        // Ensure the tower is built tall enough to comfortably reach and place blocks on the target layer from above.
        int targetStandY = targetElevation;
        if (targetWorkPos != null && targetWorkPos.getY() + 1 > targetElevation) {
            targetStandY = targetWorkPos.getY() + 1;
        }
        // Top scaffolding block is at targetStandY - 1, player stands on it at targetStandY.
        // Tower only rises as high as the currently needed layer (capped at schematic.getMaxY()),
        // NEVER building to the sky before reaching upper layers!
        int neededTopY = Math.max(scaffoldPos.getY(), Math.min(schematic.getMaxY(), targetStandY - 1));
        int topY = sm.getColumnTopY(mc, scaffoldPos);
        Vec3 towerBaseTarget = new Vec3(scaffoldPos.getX() + 0.5, scaffoldPos.getY(), scaffoldPos.getZ() + 0.5);
        double offset = Math.hypot(player.getX() - towerBaseTarget.x, player.getZ() - towerBaseTarget.z);

        // 1. Column not tall enough yet: build it up from the ground
        if (topY < neededTopY) {
            printerState = PrinterState.CLIMBING_SCAFFOLD;
            BlockPos standPos = sm.getTowerStandPos(mc.level, scaffoldPos);
            Vec3 standTarget = new Vec3(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5);
            double distToStand = Math.hypot(player.getX() - standTarget.x, player.getZ() - standTarget.z);
            double distToBase = Math.hypot(player.getX() - (scaffoldPos.getX() + 0.5), player.getZ() - (scaffoldPos.getZ() + 0.5));

            if (distToBase < 0.85 || distToStand > 1.8) {
                boolean onOldElevatedScaffold = player.getY() > scaffoldPos.getY() + 1.8
                        && (mc.level.getBlockState(player.blockPosition()).is(Blocks.SCAFFOLDING)
                        || mc.level.getBlockState(player.blockPosition().below()).is(Blocks.SCAFFOLDING));
                if (onOldElevatedScaffold) {
                    statusMessage = "3D Printer: Descending scaffolding to ground to relocate tower";
                    ScaffoldNavigator.releaseKeys(mc, player);
                    mc.options.keyShift.setDown(true);
                    if (player.input != null) player.input.shiftKeyDown = true;
                    return true;
                }

                statusMessage = "3D Printer: Moving to scaffolding tower stand spot";
                PrinterMovement.moveTo(mc, standTarget, 0.4);
                if (trackTowerStuck(mc, player, scaffoldPos)) {
                    return false;
                }
                return true;
            }

            // Verify line of sight before trying to build through walls
            Vec3 eyePos = player.getEyePosition();
            Vec3 baseCenter = Vec3.atCenterOf(scaffoldPos);
            BlockHitResult los = mc.level.clip(new ClipContext(eyePos, baseCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (los.getType() == HitResult.Type.BLOCK && !los.getBlockPos().equals(scaffoldPos) && !los.getBlockPos().equals(standPos)) {
                XlrwDebug.logTowerAction("CLIMB_WALL_BLOCKED", scaffoldPos, "LOS blocked by " + mc.level.getBlockState(los.getBlockPos()).getBlock().getName().getString() + " at " + los.getBlockPos().toShortString());
                sm.blacklistPillar(scaffoldPos, 25_000);
                config.scaffoldPillarPos = null;
                BuilderConfig.save();
                PrinterMovement.resetPath();
                PrinterMovement.stop(mc);
                statusMessage = "3D Printer: Tower blocked by wall, re-routing...";
                return false;
            }

            statusMessage = "3D Printer: Building scaffolding tower to Y=" + neededTopY;
            ScaffoldNavigator.releaseKeys(mc, player);
            boolean extended = sm.extendScaffoldColumn(mc, player, scaffoldPos, neededTopY);
            if (!extended && !sm.isColumnClear(mc.level, scaffoldPos, neededTopY)) {
                // Obstructed overhead: drop this shaft so another direction is searched
                config.scaffoldPillarPos = null;
            }
            return true;
        }

        // 2. Tall enough: walk into the shaft if on the ground outside
        boolean atBaseLevel = player.getY() < scaffoldPos.getY() + 1.2;
        boolean insideColumn = player.blockPosition().getX() == scaffoldPos.getX() && player.blockPosition().getZ() == scaffoldPos.getZ();
        if (atBaseLevel && offset > 0.35 && !insideColumn) {
            printerState = PrinterState.CLIMBING_SCAFFOLD;
            statusMessage = "3D Printer: Walking into scaffolding tower base";
            PrinterMovement.moveTo(mc, towerBaseTarget, 0.25);
            if (trackTowerStuck(mc, player, scaffoldPos)) {
                return false;
            }
            return true;
        }

        // 3. Climb it: centred in the shaft, JUMP only while inside, step onto the working level
        int desiredFeetY = targetStandY;
        printerState = PrinterState.CLIMBING_SCAFFOLD;
        statusMessage = "3D Printer: Climbing scaffolding to Y=" + desiredFeetY;
        boolean arrived = ScaffoldNavigator.climb(mc, player, scaffoldPos, topY, desiredFeetY);
        if (arrived) {
            printerState = PrinterState.BUILDING;
            return false;
        }
        return true;
    }

    private boolean isLayerComplete(Schematic schematic, int layerIndex, ClientLevel level) {
        int minY = schematic.getMinY();
        int targetY = minY + layerIndex;

        List<Map.Entry<BlockPos, BlockState>> entries = schematic.getBlocksAtY(targetY);
        for (Map.Entry<BlockPos, BlockState> entry : entries) {
            BlockPos pos = entry.getKey();
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

    public boolean triggerAutonomousUnstuck(Minecraft mc, LocalPlayer player, Schematic schematic, int currentLayer) {
        if (mc.level == null || player == null || schematic == null) return false;

        // 1. Calculate centroid of unplaced blocks on this layer, or schematic center
        int minY = schematic.getMinY();
        int targetY = minY + currentLayer;
        List<Map.Entry<BlockPos, BlockState>> entries = schematic.getBlocksAtY(targetY);

        double sumX = 0, sumZ = 0;
        int count = 0;
        for (Map.Entry<BlockPos, BlockState> e : entries) {
            BlockPos p = e.getKey();
            BlockState inWorld = mc.level.getBlockState(p);
            BuildRules.BuildStatus status = BuildRules.buildStatus(inWorld, e.getValue());
            if (status == BuildRules.BuildStatus.MISSING || status == BuildRules.BuildStatus.STACKABLE) {
                sumX += p.getX();
                sumZ += p.getZ();
                count++;
            }
        }

        Vec3 center;
        if (count > 0) {
            center = new Vec3(sumX / count + 0.5, player.getY(), sumZ / count + 0.5);
        } else {
            BlockPos anchor = schematic.getAnchor();
            net.minecraft.core.Vec3i sz = schematic.getSize();
            center = new Vec3(anchor.getX() + sz.getX() * 0.5, player.getY(), anchor.getZ() + sz.getZ() * 0.5);
        }

        BlockPos playerFoot = player.blockPosition();
        Vec3 bestCorridor = null;
        double bestScore = -Double.MAX_VALUE;
        Direction[] cardinals = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

        // 2. Search for open corridors around player (radius 3 to 7) not blocked by high walls
        for (int r = 3; r <= 7; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;

                    for (int dy = 1; dy >= -2; dy--) {
                        BlockPos checkPos = playerFoot.offset(dx, dy, dz);

                        if (!LocalPathfinder.isPassable(mc.level, checkPos)
                                || !LocalPathfinder.isPassable(mc.level, checkPos.above())
                                || !LocalPathfinder.isStandable(mc.level, checkPos.below())) {
                            continue;
                        }

                        // Wall-freedom check: avoid tight dead-ends or corridors with 3+ solid walls
                        int solidWallSides = 0;
                        for (Direction d : cardinals) {
                            BlockPos side = checkPos.relative(d);
                            if (!LocalPathfinder.isPassable(mc.level, side) && !LocalPathfinder.isPassable(mc.level, side.above())) {
                                solidWallSides++;
                            }
                        }
                        if (solidWallSides >= 3) {
                            continue; // Dead-end trapped by walls!
                        }

                        boolean highClear = LocalPathfinder.isPassable(mc.level, checkPos.above(2));

                        // Path check: player must be able to reach checkPos
                        List<BlockPos> testPath = LocalPathfinder.findPath(mc.level, playerFoot, checkPos, 120);
                        if (testPath.isEmpty() && checkPos.distSqr(playerFoot) > 4) {
                            continue;
                        }

                        Vec3 candVec = new Vec3(checkPos.getX() + 0.5, checkPos.getY(), checkPos.getZ() + 0.5);
                        double distToCenter = candVec.distanceTo(center);
                        double distFromPlayer = Math.sqrt(dx * dx + dz * dz);

                        // High score for being closer to center, open walls, high headroom, and moving away from stuck spot
                        double score = -distToCenter * 2.0 + (4 - solidWallSides) * 3.5 + distFromPlayer * 1.5;
                        if (highClear) score += 4.0;

                        if (score > bestScore) {
                            bestScore = score;
                            bestCorridor = candVec;
                        }
                    }
                }
            }
        }

        if (bestCorridor != null) {
            XlrwDebug.logMoveObstacle("UNSTUCK_CORRIDOR", BlockPos.containing(bestCorridor), "Navigating open corridor away from stuck spot");
            printerState = PrinterState.MOVING_TO_BLOCK;
            statusMessage = "3D Printer: Unstuck - Navigating open corridor towards schematic center";
            PrinterMovement.resetPath();
            PrinterMovement.moveTo(mc, bestCorridor, 0.5);
            return true;
        }

        // 3. Fallback: Self-Unstuck Breakout System ("nếu có kẹt thì sẽ tự gỡ")
        // Check blocks immediately surrounding the player (feet, head, adjacent blocks)
        BlockPos feetPos = player.blockPosition();
        BlockPos headPos = feetPos.above();

        // Priority A: Scaffolding blocks obstructing or trapping the player
        for (Direction d : Direction.values()) {
            BlockPos check = feetPos.relative(d);
            if (mc.level.getBlockState(check).is(Blocks.SCAFFOLDING)) {
                XlrwDebug.logMoveObstacle("UNSTUCK_DEMOLISH_SCAFFOLD", check, "Clearing scaffolding obstruction at feet");
                statusMessage = "3D Printer: Unstuck - Clearing scaffolding obstruction at " + check.toShortString();
                printerState = PrinterState.DEMOLISHING;
                DemolishManager.getInstance().startBreaking(mc, player, check);
                return true;
            }
            BlockPos checkHead = headPos.relative(d);
            if (mc.level.getBlockState(checkHead).is(Blocks.SCAFFOLDING)) {
                XlrwDebug.logMoveObstacle("UNSTUCK_DEMOLISH_OVERHEAD", checkHead, "Clearing scaffolding overhead");
                statusMessage = "3D Printer: Unstuck - Clearing scaffolding overhead at " + checkHead.toShortString();
                printerState = PrinterState.DEMOLISHING;
                DemolishManager.getInstance().startBreaking(mc, player, checkHead);
                return true;
            }
        }

        // Priority B: Misplaced or foreign blocks within arm's reach
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos check = feetPos.offset(dx, dy, dz);
                    if (DemolishManager.isMisplaced(mc.level, schematic, check)) {
                        XlrwDebug.logMoveObstacle("UNSTUCK_DEMOLISH_MISPLACED", check, "Breaking misplaced block trapping player");
                        statusMessage = "3D Printer: Unstuck - Breaking misplaced block at " + check.toShortString();
                        printerState = PrinterState.DEMOLISHING;
                        DemolishManager.getInstance().startBreaking(mc, player, check);
                        return true;
                    }
                }
            }
        }

        // Priority C: Elevation under feet (Scaffolding or solid support) if overhead space is clear
        BlockPos aboveHead = headPos.above();
        if (LocalPathfinder.isPassable(mc.level, aboveHead)) {
            Item pillarItem = ScaffoldManager.getInstance().hasScaffolding(player)
                    ? Items.SCAFFOLDING
                    : InventoryHelper.getAvailableSolidSupportItem(player);
            if (pillarItem != null) {
                XlrwDebug.logMoveObstacle("UNSTUCK_SCAFFOLD_ELEVATE", aboveHead, "Deploying elevation step up with " + pillarItem.getDescription().getString());
                statusMessage = "3D Printer: Unstuck - Deploying elevation step up";
                return ScaffoldManager.getInstance().triggerPillarStepUp(mc, player, pillarItem);
            }
        }

        // Priority D: Boxed in / trapped (break an adjacent wall block to open an exit passage)
        for (Direction d : cardinals) {
            BlockPos wallFeet = feetPos.relative(d);
            BlockState sf = mc.level.getBlockState(wallFeet);
            if (!sf.isAir() && sf.getDestroySpeed(mc.level, wallFeet) >= 0) {
                XlrwDebug.logMoveObstacle("UNSTUCK_BREAK_WALL", wallFeet, "Breaking wall block to escape trap: " + sf.getBlock().getName().getString());
                statusMessage = "3D Printer: Unstuck - Breaking exit passage at " + wallFeet.toShortString();
                printerState = PrinterState.DEMOLISHING;
                DemolishManager.getInstance().startBreaking(mc, player, wallFeet);
                return true;
            }
        }

        return false;
    }

    /**
     * Detects if the player is trapped inside a hole, pit, or trench with 3+ walls >= 2 blocks high.
     */
    public static boolean isTrappedInPit(ClientLevel level, LocalPlayer player) {
        if (level == null || player == null) return false;
        if (!player.onGround()) return false;
        BlockPos foot = player.blockPosition();

        // If player is on or inside scaffolding, climbing is native
        if (level.getBlockState(foot).is(Blocks.SCAFFOLDING) || level.getBlockState(foot.below()).is(Blocks.SCAFFOLDING)) {
            return false;
        }

        Direction[] cardinals = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        int blockedDirections = 0;

        for (Direction d : cardinals) {
            BlockPos step = foot.relative(d);
            BlockPos stepAbove = step.above();
            BlockPos stepBelow = step.below();

            // Direction is blocked if it is a 2+ block high solid wall:
            boolean wallBlocked = !LocalPathfinder.isPassable(level, step) && !LocalPathfinder.isPassable(level, stepAbove);

            // Or if it is a drop into void/pit (> 2 blocks down with no landing):
            boolean dropBlocked = !LocalPathfinder.isStandable(level, step)
                    && !LocalPathfinder.isStandable(level, stepBelow)
                    && !LocalPathfinder.isStandable(level, stepBelow.below());

            if (wallBlocked || dropBlocked) {
                blockedDirections++;
            }
        }

        return blockedDirections >= 3;
    }

    /**
     * Autonomously escapes from a pit/hole:
     * 1. If overhead is clear, nerd-poles / pillars up using Scaffolding, Cobble, Dirt, etc.
     * 2. If overhead is blocked or no blocks, breaks wall block at eye level to create an exit step.
     */
    public boolean handlePitEscape(Minecraft mc, LocalPlayer player, Schematic schematic) {
        if (mc.level == null || player == null) return false;

        // 1. If currently breaking a block to escape, let DemolishManager continue
        if (DemolishManager.getInstance().isBreaking()) {
            printerState = PrinterState.DEMOLISHING;
            DemolishManager.getInstance().tickBreaking(mc, player);
            return true;
        }

        // 2. If currently performing a jump-place (pillaring), let ScaffoldManager continue
        if (ScaffoldManager.getInstance().isPerformingScaffoldJump()) {
            boolean active = ScaffoldManager.getInstance().tickScaffoldJump(mc, player);
            if (active) return true;
        }

        BlockPos foot = player.blockPosition();
        BlockPos head = foot.above();

        // Check if overhead space allows jumping up (headroom clear)
        boolean canJumpUp = LocalPathfinder.isPassable(mc.level, head.above());

        // Check for pillaring blocks (Scaffolding, Cobblestone, Dirt, Planks...)
        Item pillarItem = ScaffoldManager.getInstance().hasScaffolding(player)
                ? Items.SCAFFOLDING
                : InventoryHelper.getAvailableSolidSupportItem(player);

        if (pillarItem != null && canJumpUp) {
            // Option A: Nerd-pole / Pillar up out of the pit!
            XlrwDebug.logMoveObstacle("PIT_ESCAPE_PILLAR", foot, "Nhảy đặt " + pillarItem.getDescription().getString() + " dưới chân để leo lên khỏi hố");
            statusMessage = "3D Printer: Thoát hố - Nhảy đặt block dưới chân để leo lên";
            PrinterMovement.stop(mc);
            return ScaffoldManager.getInstance().triggerPillarStepUp(mc, player, pillarItem);
        }

        // Option B: Cannot jump up or out of blocks -> Dig / Break wall block to create exit step ("phá block rồi đặt vào để leo lên")
        Direction[] cardinals = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        BlockPos bestWallToBreak = null;
        double bestDist = Double.MAX_VALUE;

        Vec3 center = schematic != null ? Vec3.atCenterOf(schematic.getAnchor()) : player.position();
        for (Direction d : cardinals) {
            BlockPos wallEye = head.relative(d);
            BlockState state = mc.level.getBlockState(wallEye);
            if (!state.isAir() && state.getDestroySpeed(mc.level, wallEye) >= 0) {
                double dist = wallEye.distToCenterSqr(center);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestWallToBreak = wallEye;
                }
            }
        }

        if (bestWallToBreak != null) {
            XlrwDebug.logMoveObstacle("PIT_ESCAPE_BREAK_WALL", bestWallToBreak, "Phá block tường để tạo bậc leo ra khỏi hố");
            statusMessage = "3D Printer: Thoát hố - Phá block tường " + bestWallToBreak.toShortString() + " để tạo bậc leo lên";
            printerState = PrinterState.DEMOLISHING;
            DemolishManager.getInstance().startBreaking(mc, player, bestWallToBreak);
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

    private void placeCandidate(Minecraft mc, LocalPlayer player, PlacementCandidate candidate) {
        BuilderConfig config = BuilderConfig.getInstance();

        // 1. Line of Sight Check: ensure there is no obstructing solid wall between eye and hitVec
        Vec3 eyePos = player.getEyePosition();
        if (config.strictRaycast) {
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

        // 5. Perform click action
        InteractionHand hand = InteractionHand.MAIN_HAND;
        InteractionResult useResult = mc.gameMode.useItemOn(player, hand, hitResult);
        if (!useResult.consumesAction()) {
            mc.gameMode.useItem(player, hand);
        }
        player.swing(hand);
        XlrwDebug.logBuildAttempt(candidate.targetPos, candidate.requiredState.getBlock().getName().getString(),
                "Type=" + candidate.type + " face=" + candidate.clickFace + " targetOn=" + (candidate.type == PlacementCandidate.Type.ADJUST || candidate.type == PlacementCandidate.Type.STACK || candidate.type == PlacementCandidate.Type.WATERLOG || candidate.type == PlacementCandidate.Type.BUCKET_FILL ? candidate.targetPos.toShortString() : candidate.neighborPos.toShortString()) + " result=" + useResult);

        // 6. Release sneak
        if (candidate.requiresSneak) {
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
        pendingTimeout = System.currentTimeMillis() + (candidate.isBucketAction() ? 450 : 350);
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
        scaffoldRetryAt = 0;
        WorkZoneManager.getInstance().clearZone();
        DemolishManager.getInstance().stopBreaking(Minecraft.getInstance());
        PrinterMovement.stop(Minecraft.getInstance());
        PrinterMovement.resetPath();
        TaskTracker.getInstance().clear();
        PlacementFinder.clearAllBadPlacementSpots();
    }
}
