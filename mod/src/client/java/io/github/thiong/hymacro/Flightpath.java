package io.github.thiong.hymacro;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A way through, when there is no way straight there.
 *
 * <p>Flying at a pest works until something is between the two of you, and the
 * shapes that get in the way are not all one shape: a roof built so pests spawn
 * on top of it, a wall around a plot, the gaps between rows of cocoa. Rules for
 * each of those are rules that keep needing another rule. This searches instead.
 *
 * <p>An ordinary A* over blocks, with two things that keep it honest inside a
 * running game. It searches a box around the two ends rather than the world, so
 * the cost has a ceiling however far away the pest is; and it gives up after a
 * fixed number of blocks looked at, so a sealed room costs a known amount and
 * then hands back nothing rather than hunting for ever.
 *
 * <p>What comes back is corners, not steps. The follower aims at the furthest
 * one it can see, so a path that goes up in stair steps is flown as a straight
 * line, and only the turns that matter are turned.
 */
public final class Flightpath {
	/** Blocks looked at before giving up. Enough for a plot, not for a world. */
	private static final int MAX_LOOKED_AT = 6000;

	/** How far out of the way it may go, measured from the straight line. */
	private static final int MARGIN = 24;

	/** Nothing is worth pathing round from further off than this. */
	public static final double RANGE = 96.0;

	private static final int[][] STEPS = steps();

	private Flightpath() {
	}

	/**
	 * Corners from one point to another, or null if there is no way.
	 *
	 * <p>The end is the nearest block with room in it rather than the exact spot
	 * asked for: a pest sits inside its own body, and the block a mob occupies is
	 * frequently one no player fits in.
	 */
	public static List<Vec3> between(Level level, Vec3 from, Vec3 to) {
		BlockPos start = BlockPos.containing(from);
		BlockPos goal = roomiestNear(level, BlockPos.containing(to));
		if (goal == null || start.equals(goal)) {
			return null;
		}

		int minX = Math.min(start.getX(), goal.getX()) - MARGIN;
		int minY = Math.max(level.getMinY() + 1, Math.min(start.getY(), goal.getY()) - MARGIN);
		int minZ = Math.min(start.getZ(), goal.getZ()) - MARGIN;
		int maxX = Math.max(start.getX(), goal.getX()) + MARGIN;
		int maxY = Math.min(level.getMaxY() - 1, Math.max(start.getY(), goal.getY()) + MARGIN);
		int maxZ = Math.max(start.getZ(), goal.getZ()) + MARGIN;

		Map<Long, Long> cameFrom = new HashMap<>();
		Map<Long, Double> best = new HashMap<>();
		Set<Long> done = new HashSet<>();
		PriorityQueue<long[]> queue =
			new PriorityQueue<>(Comparator.comparingDouble(node -> Double.longBitsToDouble(node[1])));

		best.put(start.asLong(), 0.0);
		queue.add(new long[] {start.asLong(), Double.doubleToLongBits(distance(start, goal))});

		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
		int lookedAt = 0;

		while (!queue.isEmpty() && lookedAt++ < MAX_LOOKED_AT) {
			long here = queue.poll()[0];
			if (!done.add(here)) {
				continue;
			}
			if (here == goal.asLong()) {
				return corners(level, from, cameFrom, here);
			}

			BlockPos pos = BlockPos.of(here);
			double soFar = best.getOrDefault(here, Double.MAX_VALUE);
			for (int[] step : STEPS) {
				at.set(pos.getX() + step[0], pos.getY() + step[1], pos.getZ() + step[2]);
				if (at.getX() < minX || at.getX() > maxX
					|| at.getY() < minY || at.getY() > maxY
					|| at.getZ() < minZ || at.getZ() > maxZ) {
					continue;
				}
				if (!roomy(level, at) || !squeezable(level, pos, step, side)) {
					continue;
				}

				long next = at.asLong();
				double cost = soFar + Math.sqrt(
					step[0] * step[0] + step[1] * step[1] + step[2] * step[2]);
				if (cost >= best.getOrDefault(next, Double.MAX_VALUE)) {
					continue;
				}
				best.put(next, cost);
				cameFrom.put(next, here);
				queue.add(new long[] {
					next, Double.doubleToLongBits(cost + distance(at, goal)),
				});
			}
		}
		return null;
	}

	/**
	 * A diagonal must not cut a corner it could not fit through.
	 *
	 * <p>Two blocks meeting at an edge leave a gap on the diagonal that is a gap
	 * on paper and a wall in the game. Requiring the straight neighbours to be
	 * clear as well costs three more lookups and keeps the path flyable.
	 */
	private static boolean squeezable(
			Level level, BlockPos from, int[] step, BlockPos.MutableBlockPos side) {
		if (step[0] != 0 && !roomy(level, side.set(from.getX() + step[0], from.getY(), from.getZ()))) {
			return false;
		}
		if (step[1] != 0 && !roomy(level, side.set(from.getX(), from.getY() + step[1], from.getZ()))) {
			return false;
		}
		return step[2] == 0
			|| roomy(level, side.set(from.getX(), from.getY(), from.getZ() + step[2]));
	}

	/** Whether a player fits here: this block and the one over it both clear. */
	private static boolean roomy(Level level, BlockPos pos) {
		return open(level, pos) && open(level, pos.above());
	}

	private static boolean open(Level level, BlockPos pos) {
		if (!level.hasChunkAt(pos)) {
			return false;
		}
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	/** The wanted block if a player fits, else the nearest one that does. */
	private static BlockPos roomiestNear(Level level, BlockPos wanted) {
		if (roomy(level, wanted)) {
			return wanted;
		}
		BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
		for (int out = 1; out <= 3; out++) {
			for (int dy = out; dy >= -out; dy--) {
				for (int dx = -out; dx <= out; dx++) {
					for (int dz = -out; dz <= out; dz++) {
						near.set(wanted.getX() + dx, wanted.getY() + dy, wanted.getZ() + dz);
						if (roomy(level, near)) {
							return near.immutable();
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * The path back to front, with everything that can be seen past left out.
	 *
	 * <p>A* on blocks returns a staircase. Keeping only the corners where the
	 * line of sight actually breaks turns it back into the few straight runs it
	 * really is, which is both shorter to fly and steadier to follow.
	 */
	private static List<Vec3> corners(
			Level level, Vec3 from, Map<Long, Long> cameFrom, long end) {
		List<Vec3> back = new ArrayList<>();
		long at = end;
		while (true) {
			BlockPos pos = BlockPos.of(at);
			back.add(new Vec3(pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5));
			Long before = cameFrom.get(at);
			if (before == null) {
				break;
			}
			at = before;
		}

		List<Vec3> path = new ArrayList<>(back.size());
		for (int i = back.size() - 1; i >= 0; i--) {
			path.add(back.get(i));
		}

		List<Vec3> pulled = new ArrayList<>();
		Vec3 standing = from;
		int i = 0;
		while (i < path.size()) {
			int furthest = i;
			for (int j = path.size() - 1; j > i; j--) {
				if (visible(level, standing, path.get(j))) {
					furthest = j;
					break;
				}
			}
			pulled.add(path.get(furthest));
			standing = path.get(furthest);
			i = furthest + 1;
		}
		return pulled;
	}

	/** Whether the blocks between two points all have room in them. */
	private static boolean visible(Level level, Vec3 from, Vec3 to) {
		double span = from.distanceTo(to);
		int steps = (int) Math.ceil(span * 2.0);
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int i = 1; i < steps; i++) {
			double t = (double) i / steps;
			at.set(
				(int) Math.floor(from.x + (to.x - from.x) * t),
				(int) Math.floor(from.y + (to.y - from.y) * t),
				(int) Math.floor(from.z + (to.z - from.z) * t));
			if (!roomy(level, at)) {
				return false;
			}
		}
		return true;
	}

	private static double distance(BlockPos from, BlockPos to) {
		double dx = from.getX() - to.getX();
		double dy = from.getY() - to.getY();
		double dz = from.getZ() - to.getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Every direction but standing still. */
	private static int[][] steps() {
		List<int[]> all = new ArrayList<>(26);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx != 0 || dy != 0 || dz != 0) {
						all.add(new int[] {dx, dy, dz});
					}
				}
			}
		}
		return all.toArray(new int[0][]);
	}
}
