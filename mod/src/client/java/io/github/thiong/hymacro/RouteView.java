package io.github.thiong.hymacro;

import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

/**
 * Shows the route where it happens: a box standing on every point, squares
 * along the ground between them, and what each leg does written above it.
 *
 * <p>A list of coordinates in chat is a poor answer to <em>where does this go</em>.
 * Standing on the plot and looking at it is the better one, so there is no
 * command to print a route.
 *
 * <p>Colour carries the same answer from further away than the text can be read
 * from: green where a key is held for the whole leg, orange where something is
 * clicked repeatedly, grey where nothing has been set and the player only walks.
 *
 * <p>Drawn as gizmos rather than by driving the render pipeline. Gizmos are what
 * this version of the game gives mods for exactly this, and a box is one call
 * instead of twelve lines of matrix arithmetic against an API with no published
 * mappings.
 */
public final class RouteView {
	private static final int GREY = 0xFFB0B0B0;
	private static final int GREEN = 0xFF4CE066;
	private static final int ORANGE = 0xFFFF9922;

	private static final float STROKE = 2.0f;
	private static final float TEXT_SCALE = 0.8f;
	private static final double SPACING = 1.0;
	private static final int MAX_MARKERS = 64;
	private static final double MAX_LEG = 400.0;

	private RouteView() {
	}

	public static void register(Supplier<Route> source) {
		LevelRenderEvents.BEFORE_GIZMOS.register(context -> draw(source.get()));
	}

	private static void draw(Route route) {
		if (!route.visible || route.isEmpty()) {
			return;
		}

		List<Route.Waypoint> points = route.waypoints;
		for (int i = 0; i < points.size(); i++) {
			Route.Waypoint to = points.get(i);
			int colour = colourFor(to);

			Gizmos.cuboid(stand(to), GizmoStyle.strokeAndFill(colour, STROKE, translucent(colour)))
				.setAlwaysOnTop();
			Gizmos.billboardTextOverBlock(describe(i + 1, to), above(to), colour, 0, TEXT_SCALE)
				.setAlwaysOnTop();

			if (points.size() > 1) {
				trail(points.get((i + points.size() - 1) % points.size()), to, colour);
			}
		}
	}

	/** A full block at the point itself, so it reads as somewhere to stand. */
	private static AABB stand(Route.Waypoint point) {
		return new AABB(
			point.x - 0.5, point.y, point.z - 0.5,
			point.x + 0.5, point.y + 1.0, point.z + 0.5);
	}

	private static BlockPos above(Route.Waypoint point) {
		return BlockPos.containing(point.x, point.y + 1.0, point.z);
	}

	/**
	 * Flat squares along the ground rather than one long line, so the path reads
	 * as ground being covered. Long legs space them out instead of drawing
	 * hundreds.
	 */
	private static void trail(Route.Waypoint from, Route.Waypoint to, int colour) {
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < SPACING || length > MAX_LEG) {
			return;
		}

		int steps = (int) Math.min(MAX_MARKERS, Math.floor(length / SPACING));
		GizmoStyle style = GizmoStyle.fill(translucent(colour));
		for (int i = 1; i < steps; i++) {
			double t = (double) i / steps;
			double x = from.x + dx * t;
			double y = from.y + dy * t;
			double z = from.z + dz * t;
			Gizmos.cuboid(new AABB(x - 0.35, y + 0.02, z - 0.35, x + 0.35, y + 0.06, z + 0.35), style);
		}
	}

	private static int translucent(int colour) {
		return (colour & 0x00FFFFFF) | 0x33000000;
	}

	private static int colourFor(Route.Waypoint point) {
		if (point.actions.isEmpty()) {
			return GREY;
		}
		for (Route.Action action : point.actions) {
			if (action.isSpam()) {
				return ORANGE;
			}
		}
		return GREEN;
	}

	private static String describe(int leg, Route.Waypoint point) {
		StringBuilder text = new StringBuilder().append(leg).append("  ");
		if (point.actions.isEmpty()) {
			return text.append("walk").toString();
		}
		for (int i = 0; i < point.actions.size(); i++) {
			Route.Action action = point.actions.get(i);
			if (i > 0) {
				text.append(" + ");
			}
			text.append(action.isSpam()
				? "spam " + action.key + " /" + action.intervalTicks + "t"
				: "hold " + action.key);
		}
		return text.toString();
	}
}
