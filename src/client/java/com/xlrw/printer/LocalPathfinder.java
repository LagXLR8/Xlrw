package com.xlrw.printer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

/**
 * Baritone-inspired 3D pathfinding for the printer.
 *
 * <p>All movement rules live in ONE place ({@link #forEachMove}) and are shared by:
 * <ul>
 *   <li>{@link #findPath} - A* to a single goal (used by the walker)</li>
 *   <li>{@link #flood}    - Dijkstra flood from the player (used by the station planner, so that a station is only
 *       ever chosen if the walker can really get there, and the real walking cost is known)</li>
 * </ul>
 * Keeping both on the same rule set is what stops the bot from planning a route it then refuses to walk.
 *
 * <p>Moves (feet positions):
 * <ul>
 *   <li>flat walk, jump up 1, step/drop down 1..3 (a 3 block fall deals no damage)</li>
 *   <li>diagonal walk / diagonal jump with corner clearance</li>
 *   <li>1-block parkour gap (expensive, only used when nothing else works)</li>
 *   <li>scaffolding: climb up while the feet are inside a scaffolding block, sneak down when scaffolding is below</li>
 * </ul>
 */
public class LocalPathfinder {

    private static final Direction[] CARDINALS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    public static final double COST_FLAT = 1.0;
    public static final double COST_DIAG = 1.414;
    public static final double COST_JUMP = 1.8;
    public static final double COST_DROP1 = 1.3;
    public static final double COST_DROP2 = 1.9;
    public static final double COST_DROP3 = 2.6;
    public static final double COST_PARKOUR = 3.5;
    public static final double COST_SCAFFOLD_UP = 1.6;
    public static final double COST_SCAFFOLD_DOWN = 1.4;
    public static final double COST_WATER = 2.5;

    @FunctionalInterface
    public interface MoveConsumer {
        void accept(BlockPos to, double cost);
    }

    public static class Node implements Comparable<Node> {
        public final BlockPos pos;
        public final Node parent;
        public final double gCost;
        public final double hCost;

        public Node(BlockPos pos, Node parent, double gCost, double hCost) {
            this.pos = pos;
            this.parent = parent;
            this.gCost = gCost;
            this.hCost = hCost;
        }

        public double fCost() {
            return gCost + hCost;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.fCost(), o.fCost());
        }
    }

    /** Result of a Dijkstra flood: walking cost from the start to every reachable feet position. */
    public static final class Reachability {
        public final BlockPos start;
        private final Map<BlockPos, Double> cost;

        Reachability(BlockPos start, Map<BlockPos, Double> cost) {
            this.start = start;
            this.cost = cost;
        }

        public boolean isReachable(BlockPos feet) {
            return cost.containsKey(feet);
        }

        /** Walking cost to {@code feet}, or {@link Double#MAX_VALUE} when not reachable. */
        public double costTo(BlockPos feet) {
            Double c = cost.get(feet);
            return c == null ? Double.MAX_VALUE : c;
        }

        public int size() {
            return cost.size();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Movement rules
    // ------------------------------------------------------------------------------------------------

    /** Enumerates every legal move from feet position {@code from}. */
    public static void forEachMove(ClientLevel level, BlockPos from, MoveConsumer out) {
        boolean headClear2 = isPassable(level, from.above(2));
        boolean fromInWater = isShallowWater(level, from);

        for (Direction dir : CARDINALS) {
            BlockPos flat = from.relative(dir);
            boolean flatFeet = isFeetPassable(level, flat);
            boolean flatHead = flatFeet && isPassable(level, flat.above());

            // A. flat walk
            if (flatHead && isStandable(level, flat.below())) {
                // Leaving a water stream: only if the current does not push straight against us
                if (!fromInWater || flowAlong(level, from, dir) > -0.2) {
                    out.accept(flat, COST_FLAT + (fromInWater ? COST_WATER : 0));
                }
            }

            // A2. wade through 1-deep water: with or across the current, never against it (it pushes harder than we walk)
            if (!flatFeet && isShallowWater(level, flat) && isPassable(level, flat.above())
                    && flowAlong(level, flat, dir) > -0.2 && (!fromInWater || flowAlong(level, from, dir) > -0.2)) {
                out.accept(flat, COST_FLAT + COST_WATER);
            }

            // B. jump up one block (needs head room above the start and a full block to land on)
            BlockPos jump = flat.above();
            if (headClear2 && isPassable(level, jump) && isPassable(level, jump.above()) && isStandable(level, flat)) {
                out.accept(jump, COST_JUMP);
            }

            // A3. step down one block into STILL knee-deep water (a pool that is part of the build)
            if (flatHead && isShallowWater(level, flat.below()) && level.getFluidState(flat.below()).isSource()
                    && level.getFluidState(flat.below()).getFlow(level, flat.below()).lengthSqr() < 1.0e-4) {
                out.accept(flat.below(), COST_DROP1 + COST_WATER);
            }

            if (flatHead && !isStandable(level, flat.below())) {
                // C/D/E. walk off the edge and fall 1..3 blocks
                BlockPos d1 = flat.below();
                if (isFeetPassable(level, d1)) {
                    if (isStandable(level, d1.below())) {
                        out.accept(d1, COST_DROP1);
                    } else {
                        BlockPos d2 = d1.below();
                        if (isPassable(level, d2)) {
                            if (isStandable(level, d2.below())) {
                                out.accept(d2, COST_DROP2);
                            } else {
                                BlockPos d3 = d2.below();
                                if (isPassable(level, d3) && isStandable(level, d3.below())) {
                                    out.accept(d3, COST_DROP3);
                                }
                            }
                        }
                    }
                }

                // F. parkour over a 1 block gap (same level only - the walker can execute this reliably)
                BlockPos landing = flat.relative(dir);
                // Only over SHALLOW gaps: a failed jump over a deep shaft (top of the build) is a long fall and a
                // stuck bot, which costs far more than walking around
                boolean shallowGap = isStandable(level, flat.below(2)) || isStandable(level, flat.below(3));
                if (headClear2 && shallowGap && isPassable(level, flat.above(2))
                        && isPassable(level, landing) && isPassable(level, landing.above())
                        && isStandable(level, landing.below())) {
                    out.accept(landing, COST_PARKOUR);
                }
            }
        }

        // Diagonals with strict corner clearance (both side cells must be free at feet and head height)
        for (Direction xDir : new Direction[]{Direction.EAST, Direction.WEST}) {
            for (Direction zDir : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
                BlockPos cornerX = from.relative(xDir);
                BlockPos cornerZ = from.relative(zDir);
                if (!isPassable(level, cornerX) || !isPassable(level, cornerX.above())) continue;
                if (!isPassable(level, cornerZ) || !isPassable(level, cornerZ.above())) continue;
                // No corner cutting over holes: the body sweeps across both corner cells on a diagonal step,
                // so both need a floor (a 1x1 shaft at a corner swallowed the bot more than once)
                if (!isStandable(level, cornerX.below()) || !isStandable(level, cornerZ.below())) continue;
                BlockPos diag = cornerX.relative(zDir);
                if (isPassable(level, diag) && isPassable(level, diag.above()) && isStandable(level, diag.below())) {
                    out.accept(diag, COST_DIAG);
                }
            }
        }

        // Scaffolding: feet inside scaffolding -> hold JUMP to rise one block
        if (isScaffold(level, from)) {
            BlockPos up = from.above();
            if (isPassable(level, up) && isPassable(level, up.above())) {
                out.accept(up, COST_SCAFFOLD_UP);
            }
        }
        // Scaffolding below the feet -> hold SNEAK to sink one block
        if (isScaffold(level, from.below())) {
            out.accept(from.below(), COST_SCAFFOLD_DOWN);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // A*
    // ------------------------------------------------------------------------------------------------

    public static List<BlockPos> findPath(ClientLevel level, BlockPos start, BlockPos goal, int maxNodes) {
        if (level == null || start == null || goal == null) return Collections.emptyList();
        start = normalizeStart(level, start);

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<BlockPos, Double> bestG = new HashMap<>();
        Set<BlockPos> closedSet = new HashSet<>();

        Node startNode = new Node(start, null, 0, heuristic(start, goal));
        openSet.add(startNode);
        bestG.put(start, 0.0);

        Node goalNode = null;
        Node closest = startNode;
        double closestDistSq = start.distSqr(goal);
        int iterations = 0;

        if (start.equals(goal)) {
            return Collections.emptyList();
        }

        while (!openSet.isEmpty() && iterations < maxNodes) {
            Node current = openSet.poll();
            if (!closedSet.add(current.pos)) continue;
            iterations++;

            if (current.pos.equals(goal)) {
                goalNode = current;
                break;
            }
            double dSq = current.pos.distSqr(goal);
            if (dSq < closestDistSq) {
                closestDistSq = dSq;
                closest = current;
            }

            final Node parent = current;
            forEachMove(level, current.pos, (to, cost) -> {
                if (closedSet.contains(to)) return;
                double g = parent.gCost + cost;
                if (g < bestG.getOrDefault(to, Double.MAX_VALUE)) {
                    bestG.put(to, g);
                    openSet.add(new Node(to, parent, g, heuristic(to, goal)));
                }
            });
        }

        // Goal is not a standing cell (chest, block centre, item...): settle for the closest cell next to it
        if (goalNode == null && closestDistSq <= 2.25 && closest != startNode && !isValidStand(level, goal)) {
            goalNode = closest;
        }

        if (goalNode == null) {
            String reason;
            if (!isStandable(level, goal.below()) && !isScaffold(level, goal)) {
                reason = "Dưới chân Goal " + goal.below().toShortString() + " không đứng được (" + level.getBlockState(goal.below()).getBlock().getName().getString() + ")";
            } else if (!isPassable(level, goal)) {
                reason = "Vị trí Goal " + goal.toShortString() + " bị vật cản (" + level.getBlockState(goal).getBlock().getName().getString() + ")";
            } else if (!isPassable(level, goal.above())) {
                reason = "Khoảng không trên đầu Goal bị cản (" + level.getBlockState(goal.above()).getBlock().getName().getString() + ")";
            } else if (iterations >= maxNodes) {
                reason = "Hết ngân sách tìm kiếm (" + maxNodes + " nodes)";
            } else {
                reason = "Bị tường/vực thẳm chia cắt không có lối đi liền mạch";
            }
            com.xlrw.debug.XlrwDebug.logPathFail(start, goal, iterations, closest.pos, reason);
            return Collections.emptyList();
        }

        List<BlockPos> path = new ArrayList<>();
        Node curr = goalNode;
        while (curr != null && !curr.pos.equals(start)) {
            path.add(curr.pos);
            curr = curr.parent;
        }
        Collections.reverse(path);
        com.xlrw.debug.XlrwDebug.logPathSuccess(start, goal, path.size(), iterations);
        return path;
    }

    public static boolean hasPath(ClientLevel level, BlockPos start, BlockPos goal, int maxNodes) {
        if (level == null || start == null || goal == null) return false;
        if (normalizeStart(level, start).equals(goal)) return true;
        return !findPath(level, start, goal, maxNodes).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------
    // Dijkstra flood
    // ------------------------------------------------------------------------------------------------

    /**
     * Computes the walking cost from {@code start} to every reachable feet position, bounded by
     * {@code maxNodes} settled nodes and a horizontal {@code radius} around the start.
     */
    public static Reachability flood(ClientLevel level, BlockPos start, int maxNodes, int radius) {
        Map<BlockPos, Double> settled = new HashMap<>();
        if (level == null || start == null) return new Reachability(start, settled);
        final BlockPos origin = normalizeStart(level, start);

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<BlockPos, Double> best = new HashMap<>();
        open.add(new Node(origin, null, 0, 0));
        best.put(origin, 0.0);

        while (!open.isEmpty() && settled.size() < maxNodes) {
            Node cur = open.poll();
            if (settled.containsKey(cur.pos)) continue;
            settled.put(cur.pos, cur.gCost);

            final Node parent = cur;
            forEachMove(level, cur.pos, (to, cost) -> {
                if (settled.containsKey(to)) return;
                if (Math.abs(to.getX() - origin.getX()) > radius || Math.abs(to.getZ() - origin.getZ()) > radius) return;
                double g = parent.gCost + cost;
                if (g < best.getOrDefault(to, Double.MAX_VALUE)) {
                    best.put(to, g);
                    open.add(new Node(to, null, g, 0));
                }
            });
        }
        return new Reachability(origin, settled);
    }

    /**
     * Mid-jump / mid-fall the player's block position floats in the air; snap it down to the cell the player will
     * actually land in so that searches start from a real standing position.
     */
    public static BlockPos normalizeStart(ClientLevel level, BlockPos pos) {
        if (level == null || pos == null) return pos;
        if (isStandable(level, pos.below()) || isScaffold(level, pos) || isScaffold(level, pos.below())) return pos;
        if (isLowCollision(level, pos)) return pos;
        if (isShallowWater(level, pos)) return pos;
        for (int k = 1; k <= 3; k++) {
            BlockPos p = pos.below(k);
            if (!isPassable(level, p)) break;
            if (isStandable(level, p.below()) || isScaffold(level, p)) return p;
        }
        return pos;
    }

    // ------------------------------------------------------------------------------------------------
    // Block predicates
    // ------------------------------------------------------------------------------------------------

    /**
     * Fences, walls, closed fence gates...: collision 1.5 blocks high. Nobody can jump on top of them, and their
     * collision sticks half a block into the cell above, so that cell is not walkable either.
     */
    public static boolean isTallCollision(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        VoxelShape c = state.getCollisionShape(level, pos);
        return !c.isEmpty() && c.max(net.minecraft.core.Direction.Axis.Y) > 1.0;
    }

    public static boolean isPassable(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isTallCollision(level, pos.below())) return false; // fence / wall top pokes into this cell
        if (state.isAir()) return true;
        if (state.is(Blocks.SCAFFOLDING)) return true;
        if (isHazard(state)) return false;

        VoxelShape collision = state.getCollisionShape(level, pos);
        return collision.isEmpty();
    }

    /**
     * Very low blocks (repeater, comparator, carpet, trapdoor lying down... at most 0.25 high): the player stands IN
     * that cell, slightly raised - it is a feet cell, not a block to climb on top of.
     */
    public static boolean isLowCollision(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.SCAFFOLDING) || isHazard(state)) return false;
        VoxelShape c = state.getCollisionShape(level, pos);
        return !c.isEmpty() && c.max(net.minecraft.core.Direction.Axis.Y) <= 0.25;
    }

    /** Free for the FEET: passable, or a very low block the player simply stands on inside the cell. */
    public static boolean isFeetPassable(ClientLevel level, BlockPos pos) {
        return isPassable(level, pos) || (isLowCollision(level, pos) && !isTallCollision(level, pos.below()));
    }

    public static boolean isStandable(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.SCAFFOLDING)) return true;
        if (isLowCollision(level, pos)) return false; // you stand IN a repeater cell, never one block above it
        if (isTallCollision(level, pos)) return false; // can't get on top of a fence / wall
        if (isHazard(state)) return false;
        if (state.isAir()) return false;

        VoxelShape collision = state.getCollisionShape(level, pos);
        return !collision.isEmpty();
    }

    /** Water the player can wade through: water at the feet, nothing solid in it, and real ground right below. */
    public static boolean isShallowWater(ClientLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) return false;
        if (!s.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return false;
        return isStandable(level, pos.below());
    }

    /** How strongly the water current at {@code pos} pushes in direction {@code dir} (-1 = straight against). */
    public static double flowAlong(ClientLevel level, BlockPos pos, Direction dir) {
        net.minecraft.world.level.material.FluidState fs = level.getFluidState(pos);
        if (fs.isEmpty()) return 0;
        net.minecraft.world.phys.Vec3 flow = fs.getFlow(level, pos);
        double len = Math.hypot(flow.x, flow.z);
        if (len < 1e-3) return 0;
        return (flow.x * dir.getStepX() + flow.z * dir.getStepZ()) / len;
    }

    /** A cell the player can stand in: free at feet and head, with a floor (or scaffolding) to stand on. */
    public static boolean isValidStand(ClientLevel level, BlockPos feet) {
        return isFeetPassable(level, feet) && isPassable(level, feet.above())
                && (isStandable(level, feet.below()) || isScaffold(level, feet));
    }

    public static boolean isScaffold(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.SCAFFOLDING);
    }

    public static boolean isHazard(BlockState state) {
        if (state == null) return false;
        if (state.is(Blocks.WATER) || state.getFluidState().is(net.minecraft.tags.FluidTags.WATER) || !state.getFluidState().isEmpty()) {
            return true;
        }
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.CACTUS) || state.is(Blocks.POWDER_SNOW);
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        int minXZ = Math.min(dx, dz);
        int maxXZ = Math.max(dx, dz);
        return (minXZ * COST_DIAG + (maxXZ - minXZ) * COST_FLAT) + dy * 1.2;
    }
}
