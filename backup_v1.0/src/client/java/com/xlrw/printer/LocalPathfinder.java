package com.xlrw.printer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

/**
 * Baritone-inspired 3D A* Pathfinding Engine for local obstacle avoidance and navigation.
 * Features:
 * - 4 Cardinal and 4 Diagonal traversals with strict 0.6m corner clearance checks.
 * - Jump-ascend (+1 Y) and controlled drops (-1 to -3 Y).
 * - Scaffolding column climbing and scaffold stepping.
 * - Hazard avoidance (fire, lava, magma, berry bushes).
 */
public class LocalPathfinder {

    private static final Direction[] CARDINALS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

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

    public static List<BlockPos> findPath(ClientLevel level, BlockPos start, BlockPos goal, int maxNodes) {
        if (level == null || start == null || goal == null) return Collections.emptyList();

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<BlockPos, Double> bestG = new HashMap<>();
        Set<BlockPos> closedSet = new HashSet<>();

        Node startNode = new Node(start, null, 0, heuristic(start, goal));
        openSet.add(startNode);
        bestG.put(start, 0.0);

        Node bestNode = startNode;
        double bestDistSq = start.distSqr(goal);
        int iterations = 0;
        boolean reached = false;

        // If start is already at destination
        if (bestDistSq <= 2.25) {
            reached = true;
        }

        while (!reached && !openSet.isEmpty() && iterations++ < maxNodes) {
            Node current = openSet.poll();

            if (closedSet.contains(current.pos)) continue;
            closedSet.add(current.pos);

            double distSq = current.pos.distSqr(goal);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestNode = current;
            }

            // Close enough to destination (standing within 1.5 blocks horizontal & vertical of goal)
            if (distSq <= 2.25) {
                bestNode = current;
                reached = true;
                break;
            }

            // 1. Explore 4 Cardinal Directions
            for (Direction dir : CARDINALS) {
                exploreTransition(level, current, dir, goal, openSet, bestG, closedSet);
            }

            // 2. Explore 4 Diagonals with strict corner clearance (Baritone diagonal rule)
            exploreDiagonals(level, current, goal, openSet, bestG, closedSet);

            // 3. Scaffolding climbing (Vertical ascent/descent inside scaffolding)
            if (isScaffold(level, current.pos) || isScaffold(level, current.pos.below())) {
                BlockPos scaffoldUp = current.pos.above();
                if (isPassable(level, scaffoldUp) || isScaffold(level, scaffoldUp)) {
                    checkNeighbor(current, scaffoldUp, 1.0, goal, openSet, bestG, closedSet);
                }
                BlockPos scaffoldDown = current.pos.below();
                if (isPassable(level, scaffoldDown) || isScaffold(level, scaffoldDown)) {
                    checkNeighbor(current, scaffoldDown, 1.0, goal, openSet, bestG, closedSet);
                }
            }
        }

        // Only return a path if we actually reached the destination!
        // Never return a partial path that dead-ends into an impassable solid wall!
        if (!reached) {
            String reason;
            if (!isStandable(level, goal.below())) {
                reason = "Dưới chân Goal " + goal.below().toShortString() + " là AIR/không đứng được (" + level.getBlockState(goal.below()).getBlock().getName().getString() + ")";
            } else if (!isPassable(level, goal)) {
                reason = "Vị trí Goal " + goal.toShortString() + " bị vật cản (" + level.getBlockState(goal).getBlock().getName().getString() + ")";
            } else if (!isPassable(level, goal.above())) {
                reason = "Khoảng không trên đầu Goal bị cản (" + level.getBlockState(goal.above()).getBlock().getName().getString() + ")";
            } else {
                reason = "Bị tường/vực thẳm chia cắt không có lối đi liền mạch";
            }
            com.xlrw.debug.XlrwDebug.logPathFail(start, goal, iterations, bestNode != null ? bestNode.pos : null, reason);
            return Collections.emptyList();
        }

        // Reconstruct path
        List<BlockPos> path = new ArrayList<>();
        Node curr = bestNode;
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
        if (start.distSqr(goal) <= 2.25) return true;
        return !findPath(level, start, goal, maxNodes).isEmpty();
    }

    private static void exploreTransition(ClientLevel level, Node current, Direction dir, BlockPos goal,
                                          PriorityQueue<Node> openSet, Map<BlockPos, Double> bestG, Set<BlockPos> closedSet) {
        BlockPos flat = current.pos.relative(dir);

        // A. Flat walk (same Y)
        if (isPassable(level, flat) && isPassable(level, flat.above()) && isStandable(level, flat.below())) {
            checkNeighbor(current, flat, 1.0, goal, openSet, bestG, closedSet);
        }

        // B. Jump Up 1 Block (Y + 1)
        BlockPos jump = flat.above();
        if (isPassable(level, current.pos.above(2))
                && isPassable(level, jump)
                && isPassable(level, jump.above())
                && isStandable(level, jump.below())) {
            checkNeighbor(current, jump, 1.7, goal, openSet, bestG, closedSet);
        }

        // C. Step Down 1 Block (Y - 1)
        BlockPos drop1 = flat.below();
        if (isPassable(level, flat)
                && isPassable(level, drop1)
                && isPassable(level, drop1.above())
                && isStandable(level, drop1.below())) {
            checkNeighbor(current, drop1, 1.3, goal, openSet, bestG, closedSet);
        }

        // D. Drop Down 2 Blocks (Y - 2)
        BlockPos drop2 = flat.below(2);
        if (isPassable(level, flat)
                && isPassable(level, drop1)
                && isPassable(level, drop2)
                && isPassable(level, drop2.above())
                && isStandable(level, drop2.below())) {
            checkNeighbor(current, drop2, 1.8, goal, openSet, bestG, closedSet);
        }

        // E. Drop Down 3 Blocks (Y - 3, safe max fall)
        BlockPos drop3 = flat.below(3);
        if (isPassable(level, flat)
                && isPassable(level, drop1)
                && isPassable(level, drop2)
                && isPassable(level, drop3)
                && isPassable(level, drop3.above())
                && isStandable(level, drop3.below())) {
            checkNeighbor(current, drop3, 2.4, goal, openSet, bestG, closedSet);
        }

        // F. Parkour: 1-Block Gap Jump (same Y or 1 down)
        // current -> gap (air) -> landing (standable)
        BlockPos gap = flat;
        BlockPos landing = flat.relative(dir);
        if (isPassable(level, current.pos.above(2))
                && isPassable(level, gap) && isPassable(level, gap.above())
                && !isStandable(level, gap.below())
                && isPassable(level, landing) && isPassable(level, landing.above())
                && isStandable(level, landing.below())) {
            checkNeighbor(current, landing, 2.0, goal, openSet, bestG, closedSet);
        } else if (isPassable(level, current.pos.above(2))
                && isPassable(level, gap) && isPassable(level, gap.above())
                && !isStandable(level, gap.below())
                && isPassable(level, landing.below()) && isPassable(level, landing)
                && isStandable(level, landing.below(2))) {
            checkNeighbor(current, landing.below(), 2.2, goal, openSet, bestG, closedSet);
        }
    }

    private static void exploreDiagonals(ClientLevel level, Node current, BlockPos goal,
                                         PriorityQueue<Node> openSet, Map<BlockPos, Double> bestG, Set<BlockPos> closedSet) {
        // 4 diagonal directions: NE, NW, SE, SW
        Direction[] xDirs = {Direction.EAST, Direction.WEST};
        Direction[] zDirs = {Direction.NORTH, Direction.SOUTH};

        for (Direction xDir : xDirs) {
            for (Direction zDir : zDirs) {
                BlockPos cornerX = current.pos.relative(xDir);
                BlockPos cornerZ = current.pos.relative(zDir);
                BlockPos diag = cornerX.relative(zDir);

                // Baritone corner clearance: Both adjacent sides must be passable at feet and head
                boolean clearCornerX = isPassable(level, cornerX) && isPassable(level, cornerX.above());
                boolean clearCornerZ = isPassable(level, cornerZ) && isPassable(level, cornerZ.above());

                if (clearCornerX && clearCornerZ) {
                    // Flat diagonal
                    if (isPassable(level, diag) && isPassable(level, diag.above()) && isStandable(level, diag.below())) {
                        checkNeighbor(current, diag, 1.414, goal, openSet, bestG, closedSet);
                    }
                    // Diagonal jump up
                    BlockPos diagJump = diag.above();
                    if (isPassable(level, current.pos.above(2))
                            && isPassable(level, diagJump) && isPassable(level, diagJump.above())
                            && isStandable(level, diagJump.below())) {
                        checkNeighbor(current, diagJump, 2.2, goal, openSet, bestG, closedSet);
                    }
                }
            }
        }
    }

    private static void checkNeighbor(Node current, BlockPos neighborPos, double moveCost, BlockPos goal,
                                      PriorityQueue<Node> openSet, Map<BlockPos, Double> bestG, Set<BlockPos> closedSet) {
        if (closedSet.contains(neighborPos)) return;

        double tentativeG = current.gCost + moveCost;
        if (tentativeG < bestG.getOrDefault(neighborPos, Double.MAX_VALUE)) {
            bestG.put(neighborPos, tentativeG);
            openSet.add(new Node(neighborPos, current, tentativeG, heuristic(neighborPos, goal)));
        }
    }

    public static boolean isPassable(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return true;
        if (state.is(Blocks.SCAFFOLDING)) return true;
        if (isHazard(state)) return false;

        VoxelShape collision = state.getCollisionShape(level, pos);
        return collision.isEmpty();
    }

    public static boolean isStandable(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.SCAFFOLDING)) return true;
        if (isHazard(state)) return false;
        if (state.isAir()) return false;

        VoxelShape collision = state.getCollisionShape(level, pos);
        return !collision.isEmpty();
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
        // Octile 3D distance
        int minXZ = Math.min(dx, dz);
        int maxXZ = Math.max(dx, dz);
        return (minXZ * 1.414 + (maxXZ - minXZ)) + dy * 1.5;
    }
}
