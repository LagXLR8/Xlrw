package com.xlrw.printer;

import com.xlrw.builder.BuildRules;
import com.xlrw.builder.HumanAim;
import com.xlrw.builder.InventoryHelper;
import com.xlrw.config.BuilderConfig;
import com.xlrw.schematic.Schematic;
import com.xlrw.schematic.SchematicManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Manages active scaffolding deployment:
 * 1. Jump-placement: multi-tick state machine to elevate player over 1.5 - 2 block walls or obstacles.
 * 2. Scaffolding pillar elevators: building and climbing vertical scaffold shafts to reach higher layers.
 */
public class ScaffoldManager {
    private static final ScaffoldManager INSTANCE = new ScaffoldManager();

    private enum JumpState {
        IDLE,
        PREPARING,
        ASCENDING,
        PLACING,
        LANDED
    }

    private JumpState jumpState = JumpState.IDLE;
    private int jumpTicks = 0;
    private float preJumpPitch = 0.0f;
    private double startY = 0.0;
    private BlockPos startFootPos = null;
    private long lastScaffoldAction = 0;
    private Item activePillarItem = Items.SCAFFOLDING;
    private final Map<BlockPos, Long> blacklistedPillars = new HashMap<>();

    public static ScaffoldManager getInstance() {
        return INSTANCE;
    }

    public void blacklistPillar(BlockPos pos, long durationMs) {
        if (pos != null) {
            blacklistedPillars.put(pos.immutable(), System.currentTimeMillis() + durationMs);
        }
    }

    public boolean isPillarBlacklisted(BlockPos pos) {
        if (pos == null) return true;
        Long expire = blacklistedPillars.get(pos);
        if (expire == null) return false;
        if (System.currentTimeMillis() > expire) {
            blacklistedPillars.remove(pos);
            return false;
        }
        return true;
    }

    public boolean isPerformingScaffoldJump() {
        return jumpState != JumpState.IDLE;
    }

    public void cancelJump() {
        jumpState = JumpState.IDLE;
        jumpTicks = 0;
        startY = 0.0;
        startFootPos = null;
    }

    /**
     * Starts the multi-tick jump-place routine to place scaffolding beneath player feet.
     * Halts all lateral movement so the jump is completely calm and centered.
     */
    public boolean triggerScaffoldStepUp(Minecraft mc, LocalPlayer player) {
        Item item = hasScaffolding(player) ? Items.SCAFFOLDING : InventoryHelper.getAvailableSolidSupportItem(player);
        return triggerPillarStepUp(mc, player, item);
    }

    /**
     * Executes jump-placement of any disposable block (Scaffolding, Cobble, Dirt, Glass, Planks) under player feet.
     * Used for stepping up obstacles and escaping pits/holes.
     */
    public boolean triggerPillarStepUp(Minecraft mc, LocalPlayer player, Item blockItem) {
        if (mc.level == null || player == null || mc.gameMode == null) return false;
        if (jumpState != JumpState.IDLE) return true; // Already in progress

        if (blockItem == null) return false;
        if (!player.isCreative() && !InventoryHelper.hasItem(player, blockItem)) return false;

        long now = System.currentTimeMillis();
        if (now - lastScaffoldAction < 350) return false;

        // Equip block in hand
        InventoryHelper.EquipResult equip = InventoryHelper.equipItem(player, blockItem);
        if (equip == InventoryHelper.EquipResult.MISSING) return false;

        this.activePillarItem = blockItem;

        // Calmly halt all lateral movement keys so the player jumps straight up without drifting into walls
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        if (player.input != null) {
            com.xlrw.compat.InputCompat.up(player, false);
            com.xlrw.compat.InputCompat.down(player, false);
            com.xlrw.compat.InputCompat.left(player, false);
            com.xlrw.compat.InputCompat.right(player, false);
            com.xlrw.compat.InputCompat.forwardImpulse(player, 0.0f);
            com.xlrw.compat.InputCompat.leftImpulse(player, 0.0f);
        }

        startY = player.getY();
        startFootPos = player.blockPosition();
        preJumpPitch = player.getXRot();
        jumpState = JumpState.PREPARING;
        jumpTicks = 0;
        return true;
    }

    /**
     * Ticks the jump-placement state machine every client tick.
     * @return true if the state machine is active.
     */
    public boolean tickScaffoldJump(Minecraft mc, LocalPlayer player) {
        if (jumpState == JumpState.IDLE) return false;
        if (mc.level == null || player == null || mc.gameMode == null) {
            cancelJump();
            return false;
        }

        jumpTicks++;
        if (jumpTicks > 25) { // Timeout safety
            cancelJump();
            return false;
        }

        // Strictly keep lateral movement keys halted throughout the scaffold jump
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        if (player.input != null) {
            com.xlrw.compat.InputCompat.up(player, false);
            com.xlrw.compat.InputCompat.down(player, false);
            com.xlrw.compat.InputCompat.left(player, false);
            com.xlrw.compat.InputCompat.right(player, false);
            com.xlrw.compat.InputCompat.forwardImpulse(player, 0.0f);
            com.xlrw.compat.InputCompat.leftImpulse(player, 0.0f);
        }

        switch (jumpState) {
            case PREPARING -> {
                // Ensure active pillar block is held in main hand
                InventoryHelper.equipItem(player, activePillarItem);
                if (!player.getMainHandItem().is(activePillarItem)) {
                    if (jumpTicks >= 2) {
                        cancelJump();
                        return false;
                    }
                    return true;
                }

                // Send look packet to server without snapping client camera
                player.connection.send(com.xlrw.compat.MC.rot(player.getYRot(), 90.0f, player.onGround()));

                // Jump straight up!
                mc.options.keyJump.setDown(true);
                if (player.input != null) com.xlrw.compat.InputCompat.jumping(player, true);

                jumpState = JumpState.ASCENDING;
            }
            case ASCENDING -> {
                mc.options.keyJump.setDown(true);
                if (player.input != null) com.xlrw.compat.InputCompat.jumping(player, true);

                // Player bounding box must strictly clear the 1.0m block space
                double heightAboveStart = player.getY() - startY;
                if ((heightAboveStart >= 1.05 && jumpTicks >= 4) || (jumpTicks >= 5 && player.getDeltaMovement().y <= 0.06)) {
                    jumpState = JumpState.PLACING;
                }
            }
            case PLACING -> {
                // Strictly verify main hand item is activePillarItem
                InventoryHelper.equipItem(player, activePillarItem);
                if (!player.getMainHandItem().is(activePillarItem)) {
                    cancelJump();
                    return false;
                }

                BlockPos placeTarget = startFootPos != null ? startFootPos : player.blockPosition();
                BlockPos clickPos;
                Direction clickFace = Direction.UP;
                Vec3 hitVec;

                if (activePillarItem == Items.SCAFFOLDING && mc.level.getBlockState(placeTarget).is(Blocks.SCAFFOLDING)) {
                    clickPos = placeTarget;
                    // Vanilla mechanic: To stack scaffolding vertically, click a SIDE face, NOT the top face!
                    clickFace = player.getDirection().getOpposite();
                    if (clickFace.getAxis() == Direction.Axis.Y) clickFace = Direction.NORTH;
                    hitVec = new Vec3(clickPos.getX() + 0.5 + clickFace.getStepX() * 0.49,
                            clickPos.getY() + 0.5,
                            clickPos.getZ() + 0.5 + clickFace.getStepZ() * 0.49);
                } else if (activePillarItem == Items.SCAFFOLDING && mc.level.getBlockState(placeTarget.below()).is(Blocks.SCAFFOLDING)) {
                    // Standing on scaffolding: clicking its TOP face would extend it sideways. Click a SIDE face of the
                    // block below instead -> vanilla stacks the new scaffolding on top of the column, i.e. under us.
                    clickPos = placeTarget.below();
                    clickFace = player.getDirection().getOpposite();
                    if (clickFace.getAxis() == Direction.Axis.Y) clickFace = Direction.NORTH;
                    hitVec = new Vec3(clickPos.getX() + 0.5 + clickFace.getStepX() * 0.49,
                            clickPos.getY() + 0.5,
                            clickPos.getZ() + 0.5 + clickFace.getStepZ() * 0.49);
                } else if (!mc.level.getBlockState(placeTarget.below()).isAir()) {
                    clickPos = placeTarget.below();
                    hitVec = new Vec3(clickPos.getX() + 0.5, clickPos.getY() + 1.0, clickPos.getZ() + 0.5);
                } else {
                    clickPos = player.blockPosition().below();
                    hitVec = new Vec3(clickPos.getX() + 0.5, clickPos.getY() + 1.0, clickPos.getZ() + 0.5);
                }

                mc.options.keyShift.setDown(false);
                if (player.input != null) com.xlrw.compat.InputCompat.shiftKeyDown(player, false);

                player.connection.send(com.xlrw.compat.MC.rot(player.getYRot(), 90.0f, false));
                BlockHitResult hitResult = new BlockHitResult(hitVec, clickFace, clickPos, false);

                net.minecraft.world.InteractionResult res = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
                player.swing(InteractionHand.MAIN_HAND);
                lastScaffoldAction = System.currentTimeMillis();
                if (res.consumesAction() && startFootPos != null) {
                    // The pillar block ends up in the cell we jumped out of
                    com.xlrw.builder.TempBlockTracker.record(startFootPos, activePillarItem);
                }

                mc.options.keyJump.setDown(false);
                if (player.input != null) com.xlrw.compat.InputCompat.jumping(player, false);

                jumpState = JumpState.LANDED;
            }
            case LANDED -> {
                if (player.getY() >= startY + 0.95 || player.onGround() || jumpTicks >= 8) {
                    jumpState = JumpState.IDLE;
                    jumpTicks = 0;
                    startY = 0.0;
                    startFootPos = null;
                    return false;
                }
            }
        }

        return true;
    }

    public boolean hasScaffolding(LocalPlayer player) {
        return InventoryHelper.hasItem(player, Items.SCAFFOLDING);
    }

    /**
     * Checks if the vertical shaft above basePos from baseY up to targetY + 2
     * is completely clear of solid block obstacles (allows air, replaceable, or scaffolding).
     */
    public boolean isColumnClear(ClientLevel level, BlockPos basePos, int targetY) {
        if (level == null || basePos == null) return false;
        int startY = basePos.getY();
        int endY = targetY + 2;
        for (int y = startY; y <= endY; y++) {
            BlockPos p = new BlockPos(basePos.getX(), y, basePos.getZ());
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.canBeReplaced() || s.is(Blocks.SCAFFOLDING)) {
                continue;
            }
            return false; // Obstructed by solid block overhead!
        }
        return true;
    }

    /**
     * Calculates the minimum horizontal (XZ) Euclidean distance from (x, z) to the nearest PHYSICAL (non-air) block in the schematic.
     */
    public double getHorizontalDistanceToSchematic(Schematic schematic, int x, int z) {
        if (schematic == null) return 0.0;
        Map<BlockPos, BlockState> world = schematic.getWorldBlocks();
        if (world.isEmpty()) return 0.0;

        double minSq = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, BlockState> e : world.entrySet()) {
            BlockState s = e.getValue();
            if (s == null || s.isAir() || BuildRules.isSkipped(s)) continue;
            BlockPos p = e.getKey();
            double dx = p.getX() - x;
            double dz = p.getZ() - z;
            double dSq = dx * dx + dz * dz;
            if (dSq < minSq) {
                minSq = dSq;
                if (minSq <= 1.0) return 1.0; // Direct neighbor (distance 1.0)
            }
        }
        return Math.sqrt(minSq);
    }

    /**
     * Checks whether an (X, Z) column is completely free of schematic blocks across all vertical layers.
     * Prevents ever selecting or building a scaffolding elevator shaft that intersects walls, floors, or roofs!
     */
    public boolean isColumnFreeOfSchematic(Schematic schematic, int x, int z) {
        if (schematic == null) return true;
        int minY = schematic.getMinY();
        int maxY = schematic.getMaxY();
        for (int y = minY; y <= maxY + 2; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState exp = schematic.getBlock(p);
            if (exp != null && !exp.isAir() && !BuildRules.isSkipped(exp)) {
                return false;
            }
        }
        return true;
    }

    public BlockPos findClearScaffoldPillar(ClientLevel level, LocalPlayer player, Schematic schematic, int targetY) {
        return findClearScaffoldPillar(level, player, schematic, targetY, null);
    }

    /**
     * Finds the solid ground base for a scaffolding column at (cx, cz).
     * Scans around refY (accounting for slopes/slight elevation changes)
     * ensuring that:
     * 1. The column is 100% free of schematic blocks from foundation to roof.
     * 2. The block directly beneath base is solid/standable ground.
     * 3. The column from base up to targetY + 2 is completely clear of obstacles.
     */
    public BlockPos findStandableColumnBase(ClientLevel level, Schematic schematic, int cx, int cz, int refY, int targetY) {
        if (!isColumnFreeOfSchematic(schematic, cx, cz)) return null;

        int top = Math.max(refY + 3, targetY);
        int bottom = Math.max(com.xlrw.compat.MC.minY(level) + 1, refY - 10);

        // First scan downward from refY + 2 (most common for natural ground / basement)
        for (int y = Math.min(refY + 2, top); y >= bottom; y--) {
            BlockPos ground = new BlockPos(cx, y - 1, cz);
            if (LocalPathfinder.isStandable(level, ground)) {
                BlockPos base = new BlockPos(cx, y, cz);
                if (isColumnClear(level, base, targetY)) {
                    return base;
                }
            }
        }

        // If not found below, check slightly above refY (e.g. raised terrain)
        for (int y = refY + 3; y <= top; y++) {
            BlockPos ground = new BlockPos(cx, y - 1, cz);
            if (LocalPathfinder.isStandable(level, ground)) {
                BlockPos base = new BlockPos(cx, y, cz);
                if (isColumnClear(level, base, targetY)) {
                    return base;
                }
            }
        }

        return null;
    }

    /**
     * Searches for a clear vertical shaft for scaffolding directly adjacent (1 block away)
     * to the ACTUAL PHYSICAL BUILDING BLOCKS (ignoring empty selection box air margins)
     * and closest to the current layer's work area (targetWorkPos).
     */
    public BlockPos findClearScaffoldPillar(ClientLevel level, LocalPlayer player, Schematic schematic, int targetY, BlockPos targetWorkPos) {
        if (level == null || player == null || schematic == null) return null;
        BlockPos footPos = player.blockPosition();

        // 1. Determine anchor work focus: prefer the target block on the current layer
        BlockPos workFocus = targetWorkPos != null ? targetWorkPos : footPos;
        int refY = Math.min(footPos.getY(), schematic.getMinY());

        Set<Long> testedColumns = new HashSet<>();
        List<long[]> columnCoords = new ArrayList<>();
        Direction[] cardinals = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

        // 2. Collect candidate columns: ALWAYS 1 block away (cardinal neighbors) from REAL, PHYSICAL BLOCKS
        // Source A: Blocks on the active layer
        int checkLayerY = Math.min(schematic.getMaxY(), Math.max(schematic.getMinY(), targetY));
        List<Map.Entry<BlockPos, BlockState>> layerBlocks = schematic.getBlocksAtY(checkLayerY);
        for (Map.Entry<BlockPos, BlockState> e : layerBlocks) {
            BlockState state = e.getValue();
            if (state == null || state.isAir() || BuildRules.isSkipped(state)) continue;
            BlockPos b = e.getKey();
            for (Direction d : cardinals) {
                int cx = b.getX() + d.getStepX();
                int cz = b.getZ() + d.getStepZ();
                long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
                if (testedColumns.add(key)) {
                    columnCoords.add(new long[]{cx, cz});
                }
            }
        }

        // Source B: Blocks on the foundation layer (schematic.getMinY())
        if (checkLayerY != schematic.getMinY()) {
            for (Map.Entry<BlockPos, BlockState> e : schematic.getBlocksAtY(schematic.getMinY())) {
                BlockState state = e.getValue();
                if (state == null || state.isAir() || BuildRules.isSkipped(state)) continue;
                BlockPos b = e.getKey();
                for (Direction d : cardinals) {
                    int cx = b.getX() + d.getStepX();
                    int cz = b.getZ() + d.getStepZ();
                    long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
                    if (testedColumns.add(key)) {
                        columnCoords.add(new long[]{cx, cz});
                    }
                }
            }
        }

        // Source C: Physical blocks nearest horizontally to workFocus across any layer
        for (Map.Entry<BlockPos, BlockState> e : schematic.getWorldBlocks().entrySet()) {
            BlockState state = e.getValue();
            if (state == null || state.isAir() || BuildRules.isSkipped(state)) continue;
            BlockPos b = e.getKey();
            if (Math.abs(b.getX() - workFocus.getX()) <= 12 && Math.abs(b.getZ() - workFocus.getZ()) <= 12) {
                for (Direction d : cardinals) {
                    int cx = b.getX() + d.getStepX();
                    int cz = b.getZ() + d.getStepZ();
                    long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
                    if (testedColumns.add(key)) {
                        columnCoords.add(new long[]{cx, cz});
                    }
                }
            }
        }

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (long[] coord : columnCoords) {
            int cx = (int) coord[0];
            int cz = (int) coord[1];

            double distToSchem = getHorizontalDistanceToSchematic(schematic, cx, cz);
            if (distToSchem > 1.8) continue; // Must be strictly adjacent to building (distance 1.0 - 1.414)!

            BlockPos cand = findStandableColumnBase(level, schematic, cx, cz, refY, targetY);
            if (cand == null) continue;
            if (isPillarBlacklisted(cand)) continue;

            // Reachability Check: bot must be able to walk to this tower base without pushing through walls!
            boolean directlyWalkable = PrinterMovement.canWalkDirectly(level, player.position(), cand);
            boolean reachable = directlyWalkable || LocalPathfinder.hasPath(level, footPos, cand, 350);
            if (!reachable) continue;

            double distToWork = Math.hypot(cand.getX() - workFocus.getX(), cand.getZ() - workFocus.getZ());
            double distToPlayer = Math.hypot(cand.getX() - footPos.getX(), cand.getZ() - footPos.getZ());

            // Score: Prioritize proximity to current layer work, proximity to player, and direct adjacency (1.0)
            double score = distToWork * 2.5 + distToPlayer * 0.8 + (distToSchem - 1.0) * 15.0;
            if (directlyWalkable) score -= 12.0; // Strong bonus for direct open line of sight!

            if (score < bestScore) {
                bestScore = score;
                best = cand;
            }
        }

        if (best != null) return best;

        // Fallback: search tightly around workFocus up to distance 2 (NEVER 3-4 blocks away!)
        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int cx = workFocus.getX() + dx;
                    int cz = workFocus.getZ() + dz;

                    double distToSchem = getHorizontalDistanceToSchematic(schematic, cx, cz);
                    if (distToSchem > 2.2) continue; // Hard limit: NEVER exceed 2.2 blocks!

                    BlockPos cand = findStandableColumnBase(level, schematic, cx, cz, refY, targetY);
                    if (cand == null) continue;
                    if (isPillarBlacklisted(cand)) continue;

                    boolean directlyWalkable = PrinterMovement.canWalkDirectly(level, player.position(), cand);
                    boolean reachable = directlyWalkable || LocalPathfinder.hasPath(level, footPos, cand, 350);
                    if (!reachable) continue;

                    double distToWork = Math.hypot(cand.getX() - workFocus.getX(), cand.getZ() - workFocus.getZ());
                    double distToPlayer = Math.hypot(cand.getX() - footPos.getX(), cand.getZ() - footPos.getZ());
                    double score = distToWork * 2.0 + distToPlayer * 0.8 + distToSchem * 6.0;
                    if (directlyWalkable) score -= 12.0;

                    if (score < bestScore) {
                        bestScore = score;
                        best = cand;
                    }
                }
            }
            if (best != null) return best;
        }

        if (best == null) {
            // Player is separated from workFocus by a wall or chasm!
            // Search around player's current feet position so the player can build a tower right here and climb over the wall!
            for (int r = 1; r <= 3; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                        int cx = footPos.getX() + dx;
                        int cz = footPos.getZ() + dz;

                        double distToSchem = getHorizontalDistanceToSchematic(schematic, cx, cz);
                        if (distToSchem > 2.5) continue;

                        BlockPos cand = findStandableColumnBase(level, schematic, cx, cz, refY, targetY);
                        if (cand == null) continue;
                        if (isPillarBlacklisted(cand)) continue;

                        boolean directlyWalkable = PrinterMovement.canWalkDirectly(level, player.position(), cand);
                        boolean reachable = directlyWalkable || LocalPathfinder.hasPath(level, footPos, cand, 350);
                        if (!reachable) continue;

                        double distToPlayer = Math.hypot(cand.getX() - footPos.getX(), cand.getZ() - footPos.getZ());
                        double score = distToPlayer * 2.0 + distToSchem * 4.0;
                        if (directlyWalkable) score -= 12.0;

                        if (score < bestScore) {
                            bestScore = score;
                            best = cand;
                        }
                    }
                }
                if (best != null) return best;
            }
        }

        return best;
    }

    public BlockPos getScaffoldPos(Schematic schematic, LocalPlayer player, int targetElevation) {
        return getScaffoldPos(schematic, player, targetElevation, null);
    }

    public BlockPos getScaffoldPos(Schematic schematic, LocalPlayer player, int targetElevation, BlockPos targetWorkPos) {
        BuilderConfig config = BuilderConfig.getInstance();
        ClientLevel level = player != null ? (ClientLevel) player.level() : null;

        if (config.scaffoldPillarPos != null) {
            boolean validForSchematic = true;
            if (isPillarBlacklisted(config.scaffoldPillarPos)) {
                validForSchematic = false;
            }
            boolean alreadyBuilt = level != null && level.getBlockState(config.scaffoldPillarPos).is(Blocks.SCAFFOLDING);
            boolean playerOnOrNear = player != null && Math.hypot(player.getX() - (config.scaffoldPillarPos.getX() + 0.5), player.getZ() - (config.scaffoldPillarPos.getZ() + 0.5)) < 24.0;

            if (schematic != null) {
                // 1. Tower must NEVER intersect ANY schematic block from foundation to roof!
                if (!isColumnFreeOfSchematic(schematic, config.scaffoldPillarPos.getX(), config.scaffoldPillarPos.getZ())) {
                    validForSchematic = false;
                }
                // 2. Tower must NOT be far away from schematic! Distance must be <= 2.2 blocks!
                double distToSchem = getHorizontalDistanceToSchematic(schematic, config.scaffoldPillarPos.getX(), config.scaffoldPillarPos.getZ());
                if (distToSchem > 2.2) {
                    validForSchematic = false; // Too far from schematic (3-4 blocks)! Reset and move to distance 1!
                }
                // 3. Tower must be within reach of the target work area (only for initial candidate selection, NEVER invalidate an already built tower!)
                if (targetWorkPos != null && !alreadyBuilt) {
                    double distToWork = Math.hypot(config.scaffoldPillarPos.getX() - targetWorkPos.getX(), config.scaffoldPillarPos.getZ() - targetWorkPos.getZ());
                    if (distToWork > 7.0) {
                        validForSchematic = false; // Too far from current work site to bridge safely!
                    }
                }
            }

            if (!alreadyBuilt && !playerOnOrNear && player != null && config.scaffoldPillarPos.distSqr(player.blockPosition()) > 576) {
                validForSchematic = false; // Too far from current player location
            }
            // Verify reachability from current player position (only when starting from ground, never when already on tower)
            if (validForSchematic && !alreadyBuilt && !playerOnOrNear && player != null && level != null) {
                BlockPos footPos = player.blockPosition();
                boolean directlyWalkable = PrinterMovement.canWalkDirectly(level, player.position(), config.scaffoldPillarPos);
                if (!directlyWalkable && !LocalPathfinder.hasPath(level, footPos, config.scaffoldPillarPos, 350)) {
                    validForSchematic = false; // Impassable wall separating player from pillar!
                }
            }
            if (validForSchematic && level != null && isColumnClear(level, config.scaffoldPillarPos, targetElevation)) {
                return config.scaffoldPillarPos;
            }
            // Obsolete pillar from previous schematic, distant location, blocked by wall, or obstructed: reset!
            config.scaffoldPillarPos = null;
            BuilderConfig.save();
        }

        if (level != null && player != null) {
            BlockPos clearPillar = findClearScaffoldPillar(level, player, schematic, targetElevation, targetWorkPos);
            if (clearPillar != null) {
                config.scaffoldPillarPos = clearPillar;
                BuilderConfig.save();
                com.xlrw.debug.XlrwDebug.logTowerSelect(clearPillar, targetElevation, "Tìm thấy vị trí trụ thoáng, targetWorkPos=" + (targetWorkPos != null ? targetWorkPos.toShortString() : "NULL"));
                return clearPillar;
            }
        }

        return BlockPos.ZERO;
    }

    public BlockPos getScaffoldPos(Schematic schematic, LocalPlayer player) {
        return getScaffoldPos(schematic, player, player != null ? player.getBlockY() + 4 : 64, null);
    }

    public void setScaffoldPos(BlockPos pos) {
        BuilderConfig.getInstance().scaffoldPillarPos = pos;
        BuilderConfig.save();
    }

    public int getColumnTopY(Minecraft mc, BlockPos basePos) {
        if (mc.level == null) return basePos.getY();
        int y = basePos.getY();
        while (y < 320 && mc.level.getBlockState(new BlockPos(basePos.getX(), y, basePos.getZ())).is(Blocks.SCAFFOLDING)) {
            y++;
        }
        return y - 1;
    }

    /**
     * Extends a scaffolding column upwards towards the target layer height.
     * Enforces Vanilla Minecraft mechanics:
     * - Headroom check: shaft must be clear to targetY + 2. If obstructed, returns false so alternative is found.
     * - When extending an existing column: looks at a SIDE face (NORTH, SOUTH, EAST, WEST) of the scaffolding
     *   without sneaking, and right-clicks. Vanilla automatically stacks the block onto the top of the column!
     */
    public BlockPos getTowerStandPos(ClientLevel level, BlockPos basePos) {
        if (level == null || basePos == null) return basePos.relative(Direction.NORTH);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos stand = basePos.relative(dir);
            BlockPos above = stand.above();
            BlockPos below = stand.below();
            boolean standClear = level.getBlockState(stand).isAir() || level.getBlockState(stand).canBeReplaced();
            boolean aboveClear = level.getBlockState(above).isAir() || level.getBlockState(above).canBeReplaced();
            boolean groundSolid = !level.getBlockState(below).isAir() && !BuildRules.isLiquid(level.getBlockState(below));
            if (standClear && aboveClear && groundSolid) {
                return stand;
            }
        }
        return basePos.relative(Direction.NORTH);
    }

    private boolean columnRejected = false;

    /** True once after extendScaffoldColumn refused the column for good (build block / obstacle in the way). */
    public boolean consumeColumnRejected() {
        boolean r = columnRejected;
        columnRejected = false;
        return r;
    }

    public boolean extendScaffoldColumn(Minecraft mc, LocalPlayer player, BlockPos basePos, int targetY) {
        if (mc.level == null || player == null || mc.gameMode == null) return false;

        // Scaffolding column must NEVER touch or overlap any schematic building block!
        // (only the cells the tower and the player standing on it will occupy matter)
        Schematic activeSchematic = SchematicManager.getInstance().getActiveSchematic();
        if (activeSchematic != null) {
            com.xlrw.config.BuilderConfig cfg = com.xlrw.config.BuilderConfig.getInstance();
            // Cells of layers ABOVE the one being built are still empty: the tower may use them for now (same rule as
            // the planner - otherwise the planner picks a tower that this check then refuses forever)
            int layerTop = cfg.layerMode ? activeSchematic.getMinY() + cfg.currentLayer : Integer.MAX_VALUE;
            for (int y = basePos.getY(); y <= targetY + 2; y++) {
                if (y > layerTop) break;
                BlockState exp = activeSchematic.getBlock(new BlockPos(basePos.getX(), y, basePos.getZ()));
                if (exp != null && !BuildRules.isSkipped(exp)) {
                    com.xlrw.debug.XlrwDebug.logTowerAction("SCHEMATIC_CONFLICT", basePos, "Cột trụ đụng block công trình tại Y=" + y);
                    columnRejected = true;
                    return false;
                }
            }
        }

        // 1. Headroom detection: check if column has an obstacle overhead
        if (!isColumnClear(mc.level, basePos, targetY)) {
            com.xlrw.debug.XlrwDebug.logTowerAction("COLUMN_BLOCKED", basePos, "Cột trụ bị chặn phía trên (tới Y=" + (targetY + 2) + ")");
            columnRejected = true;
            return false;
        }

        int topY = getColumnTopY(mc, basePos);
        if (topY >= targetY) {
            return true;
        }

        long now = System.currentTimeMillis();
        if (now - lastScaffoldAction < 150) {
            return false;
        }

        if (!hasScaffolding(player)) {
            return false;
        }

        InventoryHelper.equipItem(player, Items.SCAFFOLDING);
        if (!player.getMainHandItem().is(Items.SCAFFOLDING)) {
            return false;
        }

        boolean hasBase = mc.level.getBlockState(basePos).is(Blocks.SCAFFOLDING);
        if (!hasBase) {
            // Place the base block by clicking on the top of the ground block underneath basePos!
            BlockPos groundPos = basePos.below();
            Vec3 hitVec = new Vec3(basePos.getX() + 0.5, groundPos.getY() + 1.0, basePos.getZ() + 0.5);
            float[] rots = HumanAim.calculateRotations(player.getEyePosition(), hitVec);
            player.connection.send(com.xlrw.compat.MC.rot(rots[0], rots[1], player.onGround()));

            mc.options.keyShift.setDown(false);
            if (player.input != null) com.xlrw.compat.InputCompat.shiftKeyDown(player, false);

            BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, groundPos, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
            player.swing(InteractionHand.MAIN_HAND);
            lastScaffoldAction = now;
            com.xlrw.debug.XlrwDebug.logTowerAction("BASE_PLACE", basePos, topY, targetY, "Đặt block scaffolding chân trụ tại " + basePos.toShortString());
            return false;
        } else {
            // Base scaffolding block exists!
            // In Vanilla Minecraft, to raise scaffolding vertically, the player right-clicks
            // on a SIDE face of the bottom-most scaffolding block (basePos) without sneaking!
            // Vanilla automatically stacks the new scaffolding block onto the very top of that column!
            double dx = player.getX() - (basePos.getX() + 0.5);
            double dz = player.getZ() - (basePos.getZ() + 0.5);
            if (Math.hypot(dx, dz) < 0.85) {
                // Cannot place on an exterior side face while standing inside the column!
                return false;
            }

            Direction sideFace = com.xlrw.compat.MC.nearestDir(dx, 0, dz);
            if (sideFace.getAxis() == Direction.Axis.Y) sideFace = Direction.NORTH;

            // Hit point on that side face of the bottom-most scaffolding block
            Vec3 hitVec = new Vec3(
                    basePos.getX() + 0.5 + sideFace.getStepX() * 0.48,
                    basePos.getY() + 0.5,
                    basePos.getZ() + 0.5 + sideFace.getStepZ() * 0.48
            );

            float[] rots = HumanAim.calculateRotations(player.getEyePosition(), hitVec);
            player.connection.send(com.xlrw.compat.MC.rot(rots[0], rots[1], player.onGround()));

            // Sneaking MUST be disabled so vanilla does not place normally against the face!
            mc.options.keyShift.setDown(false);
            if (player.input != null) com.xlrw.compat.InputCompat.shiftKeyDown(player, false);

            BlockHitResult hitResult = new BlockHitResult(hitVec, sideFace, basePos, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
            player.swing(InteractionHand.MAIN_HAND);
            lastScaffoldAction = now;
            com.xlrw.debug.XlrwDebug.logTowerAction("COLUMN_RAISE", basePos, topY, targetY, "Click mặt " + sideFace.getName() + " của chân trụ " + basePos.toShortString() + " để nâng chiều cao tháp");
            return false;
        }
    }

    /**
     * Extends scaffolding horizontally by right-clicking the TOP face of a scaffolding block
     * while facing in the desired horizontal direction (Vanilla Minecraft mechanics).
     * Scaffolding CANNOT attach to mid-air solid blocks; it must start from a scaffolding block!
     */
    public boolean extendScaffoldHorizontal(Minecraft mc, LocalPlayer player, BlockPos currentScaffold, Direction extendDir) {
        if (mc.level == null || player == null || mc.gameMode == null) return false;
        if (extendDir.getAxis() == Direction.Axis.Y) return false;

        // In Vanilla Minecraft, horizontal extension ONLY works from another scaffolding block!
        if (!mc.level.getBlockState(currentScaffold).is(Blocks.SCAFFOLDING)) {
            com.xlrw.debug.XlrwDebug.logScaffoldFail("EXTEND_HORIZONTAL", currentScaffold, "Block hiện tại không phải là SCAFFOLDING (" + mc.level.getBlockState(currentScaffold).getBlock().getName().getString() + "). Không thể đặt ngang lơ lửng!");
            return false;
        }

        // Mid-column guard: cannot extend horizontally from a block that has scaffolding above it (inside vertical shaft)
        if (mc.level.getBlockState(currentScaffold.above()).is(Blocks.SCAFFOLDING)) {
            com.xlrw.debug.XlrwDebug.logScaffoldFail("EXTEND_HORIZONTAL", currentScaffold, "Block phía trên đang có Scaffolding đè lên (thân trụ đứng). Phải leo lên đỉnh trụ trước khi bắc cầu ngang!");
            return false;
        }

        // Stability distance check: scaffolding collapses at distance 7
        BlockPos targetPos = currentScaffold.relative(extendDir);
        if (net.minecraft.world.level.block.ScaffoldingBlock.getDistance(mc.level, targetPos) >= 7) {
            com.xlrw.debug.XlrwDebug.logScaffoldFail("EXTEND_HORIZONTAL", targetPos, "Vượt quá giới hạn chịu lực của Scaffolding (distance >= 7)!");
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - lastScaffoldAction < 150) return false;

        if (!hasScaffolding(player)) return false;

        InventoryHelper.equipItem(player, Items.SCAFFOLDING);
        if (!player.getMainHandItem().is(Items.SCAFFOLDING)) return false;

        // Set player rotation so player.getDirection() on client matches extendDir
        float targetYaw = extendDir.toYRot();
        float targetPitch = 60.0f;
        player.setYRot(targetYaw);
        player.setXRot(targetPitch);
        player.connection.send(com.xlrw.compat.MC.rot(targetYaw, targetPitch, player.onGround()));

        // Ensure sneak is OFF
        mc.options.keyShift.setDown(false);
        if (player.input != null) com.xlrw.compat.InputCompat.shiftKeyDown(player, false);

        // TOP face + facing direction => vanilla extends the row horizontally
        Direction clickFace = Direction.UP;
        Vec3 hitVec = new Vec3(currentScaffold.getX() + 0.5 + extendDir.getStepX() * 0.3,
                currentScaffold.getY() + 1.0,
                currentScaffold.getZ() + 0.5 + extendDir.getStepZ() * 0.3);

        BlockHitResult hitResult = new BlockHitResult(hitVec, clickFace, currentScaffold, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
        player.swing(InteractionHand.MAIN_HAND);
        lastScaffoldAction = now;
        com.xlrw.debug.XlrwDebug.logScaffoldBridge(currentScaffold, extendDir, targetPos, "EXTEND_HORIZONTAL",
                String.format("Yaw=%.1f, Pitch=%.1f, Dist=%d", targetYaw, targetPitch, net.minecraft.world.level.block.ScaffoldingBlock.getDistance(mc.level, targetPos)));
        return true;
    }
}
