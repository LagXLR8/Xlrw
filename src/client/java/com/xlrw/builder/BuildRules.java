package com.xlrw.builder;

import com.xlrw.schematic.Schematic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;

import java.util.List;

/**
 * Classifies schematic blocks into build-phase work, fluid-phase work and ignored entries.
 * <p>
 * Build phase: every solid / decorative block (correct orientation is enforced at placement).
 * Fluid phase: water/lava sources, waterlogging and draining. Runs only after ALL blocks are built
 * so that water never floods the construction site.
 */
public final class BuildRules {

    private BuildRules() {}

    public enum BuildStatus {
        DONE,          // Block present (orientation already correct or irrelevant)
        MISSING,       // Position is air / replaceable -> place block
        STACKABLE,     // Same block but needs another click (double slab, candles, pickles, layers)
        ADJUST,        // Same block, needs right-click state change (open, delay, mode...)
        BLOCKED,       // A different block occupies the position (we never break blocks)
        IGNORED        // Not a build-phase entry
    }

    public enum FluidStatus {
        DONE,
        PLACE_SOURCE,  // Needs a water/lava source from a bucket
        WATERLOG,      // Block present but must be waterlogged
        DRAIN,         // Block present and waterlogged but schematic says dry
        WAITING,       // Base block not placed yet
        IGNORED
    }

    /** Orientation properties that are decided by how the player places the block. */
    public static final List<Property<?>> ORIENTATION_PROPS = List.of(
            BlockStateProperties.FACING,
            BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.VERTICAL_DIRECTION,
            BlockStateProperties.AXIS,
            BlockStateProperties.HORIZONTAL_AXIS,
            BlockStateProperties.HALF,
            BlockStateProperties.SLAB_TYPE,
            BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.ROTATION_16,
            BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.ORIENTATION,
            BlockStateProperties.BELL_ATTACHMENT,
            BlockStateProperties.RAIL_SHAPE,
            BlockStateProperties.RAIL_SHAPE_STRAIGHT,
            BlockStateProperties.CHEST_TYPE
    );

    public static boolean hasOrientation(BlockState state) {
        if (state == null) return false;
        if (state.getBlock() instanceof MultifaceBlock) return true;
        for (Property<?> p : ORIENTATION_PROPS) {
            if (state.hasProperty(p)) return true;
        }
        return false;
    }

    public static boolean isLiquid(BlockState state) {
        return state.getBlock() instanceof LiquidBlock;
    }

    /** Entries that are produced automatically by the game or cannot be placed by hand. */
    public static boolean isSkipped(BlockState state) {
        if (state.isAir()) return true;
        Block b = state.getBlock();
        if (b instanceof LiquidBlock) return true; // handled by fluid phase
        if (b == Blocks.BUBBLE_COLUMN || b == Blocks.KELP || b == Blocks.KELP_PLANT
                || b == Blocks.SEAGRASS || b == Blocks.TALL_SEAGRASS) return true;
        if (b == Blocks.PISTON_HEAD || b == Blocks.MOVING_PISTON) return true;
        if (b == Blocks.NETHER_PORTAL || b == Blocks.END_PORTAL || b == Blocks.END_GATEWAY) return true;
        if (b instanceof BaseFireBlock) return true;
        if (b == Blocks.FROSTED_ICE) return true;
        // Upper halves (doors, tall flowers, tall grass) are created together with the lower half
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
        // Bed head is created together with the foot
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return true;
        return false;
    }

    public static boolean isReplaceable(BlockState world) {
        return world.isAir() || world.canBeReplaced() || isLiquid(world);
    }

    public static boolean orientationMatches(BlockState actual, BlockState required) {
        if (actual == null || required == null) return false;
        if (actual.getBlock() != required.getBlock()) return false;

        if (required.getBlock() instanceof MultifaceBlock) {
            for (Direction dir : Direction.values()) {
                net.minecraft.world.level.block.state.properties.BooleanProperty prop = MultifaceBlock.getFaceProperty(dir);
                if (actual.hasProperty(prop) && required.hasProperty(prop)) {
                    if (!actual.getValue(prop).equals(required.getValue(prop))) return false;
                }
            }
            return true;
        }

        for (Property<?> p : ORIENTATION_PROPS) {
            if (!required.hasProperty(p) || !actual.hasProperty(p)) continue;
            if (p == BlockStateProperties.SLAB_TYPE && required.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
                continue; // any half is a valid first step towards a double slab
            }
            if (p == BlockStateProperties.CHEST_TYPE && actual.getValue(BlockStateProperties.CHEST_TYPE) == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                continue; // single chest is a valid first step towards a double chest
            }
            if (!actual.getValue(p).equals(required.getValue(p))) return false;
        }
        return true;
    }

    public static BuildStatus buildStatus(BlockState world, BlockState required) {
        if (isSkipped(required)) return BuildStatus.IGNORED;

        if (world.getBlock() == required.getBlock()) {
            if (needsStacking(world, required)) return BuildStatus.STACKABLE;
            if (BlockStateAdjuster.needsAdjustment(world, required)) return BuildStatus.ADJUST;
            return BuildStatus.DONE;
        }
        if (isReplaceable(world)) return BuildStatus.MISSING;
        return BuildStatus.BLOCKED;
    }

    /** Same block present but more items must be added at the same position. */
    public static boolean needsStacking(BlockState world, BlockState required) {
        if (required.hasProperty(BlockStateProperties.SLAB_TYPE) && world.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            return required.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
                    && world.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE;
        }
        if (lessThan(world, required, BlockStateProperties.CANDLES)) return true;
        if (lessThan(world, required, BlockStateProperties.PICKLES)) return true;
        if (lessThan(world, required, BlockStateProperties.EGGS)) return true;
        if (lessThan(world, required, BlockStateProperties.LAYERS)) return true;
        if (lessThan(world, required, BlockStateProperties.FLOWER_AMOUNT)) return true;
        return false;
    }

    private static boolean lessThan(BlockState world, BlockState required,
                                    net.minecraft.world.level.block.state.properties.IntegerProperty p) {
        return world.hasProperty(p) && required.hasProperty(p) && world.getValue(p) < required.getValue(p);
    }

    public static FluidStatus fluidStatus(BlockState world, BlockState required) {
        if (required.getBlock() instanceof LiquidBlock) {
            // Only sources can be placed; flowing entries form on their own
            if (required.getValue(LiquidBlock.LEVEL) != 0) return FluidStatus.IGNORED;
            boolean water = required.is(Blocks.WATER);
            if (world.getBlock() == required.getBlock() && world.getFluidState().isSource()) return FluidStatus.DONE;
            if (!isReplaceable(world)) return FluidStatus.IGNORED;
            if (!water && world.getFluidState().is(Fluids.WATER)) return FluidStatus.IGNORED;
            return FluidStatus.PLACE_SOURCE;
        }

        if (required.hasProperty(BlockStateProperties.WATERLOGGED)) {
            if (isSkipped(required)) {
                return FluidStatus.IGNORED;
            }
            if (world.getBlock() != required.getBlock()) return FluidStatus.WAITING;
            if (!world.hasProperty(BlockStateProperties.WATERLOGGED)) return FluidStatus.IGNORED;
            boolean want = required.getValue(BlockStateProperties.WATERLOGGED);
            boolean have = world.getValue(BlockStateProperties.WATERLOGGED);
            if (want && !have) {
                // Double slabs cannot hold water
                if (world.hasProperty(BlockStateProperties.SLAB_TYPE)
                        && world.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) return FluidStatus.IGNORED;
                return FluidStatus.WATERLOG;
            }
            if (!want && have) return FluidStatus.DRAIN;
            return FluidStatus.DONE;
        }
        return FluidStatus.IGNORED;
    }

    /** Blocks whose right-click would interact (open GUI, toggle...) instead of placing against them. */
    public static boolean isInteractable(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof BaseEntityBlock) return true; // chests, furnaces, hoppers, signs, beds, barrels, lecterns...
        return b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof DiodeBlock
                || b instanceof NoteBlock || b instanceof BedBlock || b instanceof CakeBlock
                || b instanceof AbstractCauldronBlock || b instanceof ComposterBlock
                || b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof GrindstoneBlock
                || b instanceof StonecutterBlock || b instanceof LoomBlock || b instanceof RespawnAnchorBlock
                || b instanceof FlowerPotBlock || b instanceof RedStoneWireBlock || b instanceof DragonEggBlock
                || b instanceof CandleBlock || b instanceof CandleCakeBlock || b instanceof CaveVinesBlock
                || b instanceof CaveVinesPlantBlock || b instanceof SweetBerryBushBlock || b instanceof PumpkinBlock
                || b instanceof RedStoneOreBlock || b instanceof TntBlock;
    }

    /**
     * True if the block is a piston base (normal piston or sticky piston).
     * Used to enforce build order: normal blocks & redstone > pistons > liquids.
     */
    public static boolean isPiston(BlockState state) {
        if (state == null) return false;
        Block b = state.getBlock();
        return b instanceof PistonBaseBlock || b == Blocks.PISTON || b == Blocks.STICKY_PISTON;
    }

    /**
     * Checks if placing a block at {@code targetPos} would completely enclose and seal any unplaced
     * neighbor block in the schematic, leaving that neighbor with zero open/passable faces.
     * Prevents missing interior blocks from being blocked or trapped inside walls.
     */
    public static boolean wouldSealUnplacedNeighbor(ClientLevel level, Schematic schematic, BlockPos targetPos) {
        if (level == null || schematic == null || targetPos == null) return false;

        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = targetPos.relative(dir);
            BlockState expectedNeighbor = schematic.getBlock(neighborPos);
            if (expectedNeighbor == null || expectedNeighbor.isAir() || isSkipped(expectedNeighbor)) {
                continue;
            }

            BlockState inWorldNeighbor = level.getBlockState(neighborPos);
            // If the neighbor in the world is already built and solid, placing next to it cannot seal it
            if (!inWorldNeighbor.isAir() && !inWorldNeighbor.canBeReplaced() && !isLiquid(inWorldNeighbor)) {
                continue;
            }

            // neighborPos is an UNPLACED schematic block!
            // Count how many open neighbor faces neighborPos will have EXCLUDING targetPos:
            int otherOpenNeighbors = 0;
            for (Direction nDir : Direction.values()) {
                BlockPos openCheck = neighborPos.relative(nDir);
                if (openCheck.equals(targetPos)) continue; // This will become solid once targetPos is placed

                BlockState checkState = level.getBlockState(openCheck);
                if (checkState.isAir() || checkState.canBeReplaced() || isLiquid(checkState)) {
                    otherOpenNeighbors++;
                }
            }

            // If placing targetPos would leave neighborPos with 0 open neighbor faces, it seals it!
            if (otherOpenNeighbors == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * "Closing pressure" of an unplaced cell: how many of its 4 horizontal neighbours are solid in the FINISHED build
     * AND already standing in the world. A cell whose final neighbours are all solid (a repeater in a 1x1 obsidian
     * hole, a piston walled in...) must be placed before the last wall goes up, otherwise it can only be reached from
     * straight above with a narrow line of sight and the bot stalls. Returns 0 when the cell is not a pocket
     * (fewer than 3 final solid neighbours) or is already correct.
     */
    public static int closingPressure(ClientLevel level, Schematic schematic, BlockPos pos) {
        if (level == null || schematic == null || pos == null) return 0;
        int finalSolid = 0;
        int placed = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = pos.relative(d);
            BlockState exp = schematic.getBlock(n);
            if (exp == null || exp.isAir() || isSkipped(exp) || isLiquid(exp)) continue;
            finalSolid++;
            BlockState w = level.getBlockState(n);
            if (!w.isAir() && !w.canBeReplaced() && !isLiquid(w)) placed++;
        }
        if (finalSolid < 3) return 0;
        return placed >= 2 ? placed + (finalSolid == 4 ? 1 : 0) : 0;
    }

    /** True when placing {@code target} would put up the LAST horizontal wall around a still-unplaced pocket cell. */
    public static boolean wouldClosePocket(ClientLevel level, Schematic schematic, BlockPos target) {
        if (level == null || schematic == null || target == null) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = target.relative(d);
            BlockState exp = schematic.getBlock(n);
            if (exp == null || exp.isAir() || isSkipped(exp) || isLiquid(exp)) continue;
            BlockState w = level.getBlockState(n);
            if (w.getBlock() == exp.getBlock()) continue; // already built
            int walled = 0;
            for (Direction d2 : Direction.Plane.HORIZONTAL) {
                BlockPos m = n.relative(d2);
                if (m.equals(target)) { walled++; continue; }
                BlockState e2 = schematic.getBlock(m);
                if (e2 == null || e2.isAir() || isSkipped(e2) || isLiquid(e2)) break;
                BlockState w2 = level.getBlockState(m);
                if (!w2.isAir() && !w2.canBeReplaced() && !isLiquid(w2)) walled++;
            }
            if (walled == 4) return true;
        }
        return false;
    }
}
