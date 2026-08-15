package io.github.thiong.hymacro;

import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shows the macro where it happens: a box on every point, its number above it,
 * what that leg does under the number, and arrows along the ground pointing the
 * way it travels.
 *
 * <p>A list of coordinates in chat is a poor answer to <em>where does this go</em>.
 * Standing on the plot and looking at it is the better one, so there is no
 * command to print a macro.
 *
 * <p>Arrows rather than a plain trail, because direction is the one thing a
 * still picture of a route cannot otherwise say and the first thing anyone wants
 * to know. The leg closing the loop is drawn faintly, since it is the way back
 * rather than more of the same work, and is left out when the last point warps,
 * because then it is not walked at all.
 *
 * <p>Colour carries the kind of work from further away than the text can be read
 * from: green where a key is held, orange where something is clicked repeatedly,
 * grey where nothing is set and the player only walks.
 */
public final class RouteView {
	private static final int GREY = 0xFFE4E4E4;
	private static final int GREEN = 0xFF43F06B;
	private static final int ORANGE = 0xFFFFA023;
	private static final int RETURN = 0xFF7C8CA0;
	private static final int FAINT = 0xFFCFCFCF;

	private static final float STROKE = 2.0f;
	private static final float PATH_WIDTH = 3.0f;
	private static final float RETURN_PATH_WIDTH = 1.5f;
	private static final float ARROW = 0.8f;
	private static final float RETURN_ARROW = 0.45f;
	private static final float NUMBER_SCALE = 1.2f;
	private static final float TEXT_SCALE = 0.8f;

	/** Far enough apart that a tall number and its caption never touch. */
	private static final double NUMBER_HEIGHT = 2.6;
	private static final double TEXT_HEIGHT = 1.7;
	private static final double LEG_TEXT_HEIGHT = 1.1;

	/** Off the ground, or a flat line is invisible at a grazing angle. */
	private static final double PATH_HEIGHT = 0.35;
	private static final double SPACING = 3.5;
	private static final double ARROW_LENGTH = 2.0;
	private static final int MAX_ARROWS = 40;
	private static final double MAX_LEG = 400.0;

	/**
	 * How far away a leg still gets its arrowheads.
	 *
	 * <p>Every one of them is built again from nothing on every frame, and a
	 * long macro can ask for hundreds. Beyond a hundred blocks an arrow is a
	 * couple of pixels and says nothing the line it sits on does not, so the
	 * line is kept and the heads are dropped. Nothing you could read disappears.
	 */
	private static final double ARROW_RANGE = 128.0;

	/**
	 * The styles, built once.
	 *
	 * <p>They are pure description and never change, so building three of them
	 * per point per frame was making rubbish for the collector to sweep up for
	 * the whole length of a run.
	 */
	private static final GizmoStyle GREY_BOX = box(GREY);
	private static final GizmoStyle GREEN_BOX = box(GREEN);
	private static final GizmoStyle ORANGE_BOX = box(ORANGE);
	private static final TextGizmo.Style GREY_NUMBER = number(GREY);
	private static final TextGizmo.Style GREEN_NUMBER = number(GREEN);
	private static final TextGizmo.Style ORANGE_NUMBER = number(ORANGE);
	private static final TextGizmo.Style CAPTION = caption(FAINT);
	private static final TextGizmo.Style RETURN_CAPTION = caption(RETURN);

	private RouteView() {
	}

	public static void register(Supplier<Route> source) {
		LevelRenderEvents.BEFORE_GIZMOS.register(context -> draw(source.get()));
	}

	private static void draw(Route route) {
		if (route == null || !route.visible || route.isEmpty()) {
			return;
		}

		// Where it is being looked at from, for deciding what is too far away to
		// be worth building. Without a player there is nothing to measure from,
		// so everything is drawn, which is what always happened.
		var viewer = Minecraft.getInstance().player;
		boolean measurable = viewer != null;
		double viewX = measurable ? viewer.getX() : 0.0;
		double viewZ = measurable ? viewer.getZ() : 0.0;

		List<Route.Waypoint> points = route.waypoints;
		for (int i = 0; i < points.size(); i++) {
			Route.Waypoint to = points.get(i);
			int colour = colourFor(to);

			Gizmos.cuboid(stand(to), boxStyle(colour)).setAlwaysOnTop();
			Gizmos.billboardText(String.valueOf(i + 1), over(to, NUMBER_HEIGHT), numberStyle(colour))
				.setAlwaysOnTop();

			if (points.size() < 2) {
				Gizmos.billboardText(label(to), over(to, TEXT_HEIGHT), CAPTION).setAlwaysOnTop();
				continue;
			}

			Route.Waypoint from = points.get((i + points.size() - 1) % points.size());
			boolean closing = i == 0;
			if (closing && from.sends()) {
				continue;
			}

			// Over the middle of the stretch it describes, not over its end. A
			// caption above a point reads as belonging to the point, and the leg
			// that ends there is the one before it.
			Gizmos.billboardText(label(to), middle(from, to),
					closing ? RETURN_CAPTION : CAPTION)
				.setAlwaysOnTop();
			flow(from, to, closing, measurable, viewX, viewZ);
		}
	}

	/** The caption for a point, worked out once and kept on the point. */
	private static String label(Route.Waypoint point) {
		String text = point.label;
		if (text == null) {
			text = describe(point);
			point.label = text;
		}
		return text;
	}

	/** A full block at the point itself, so it reads as somewhere to stand. */
	private static AABB stand(Route.Waypoint point) {
		return new AABB(
			point.x - 0.5, point.y, point.z - 0.5,
			point.x + 0.5, point.y + 1.0, point.z + 0.5);
	}

	private static Vec3 over(Route.Waypoint point, double height) {
		return new Vec3(point.x, point.y + height, point.z);
	}

	private static Vec3 middle(Route.Waypoint from, Route.Waypoint to) {
		return new Vec3(
			(from.x + to.x) / 2.0,
			(from.y + to.y) / 2.0 + LEG_TEXT_HEIGHT,
			(from.z + to.z) / 2.0);
	}

	/**
	 * One line the length of the leg, with arrowheads repeated along it.
	 *
	 * <p>The line is what makes the path visible; the arrows are what make its
	 * direction visible. Arrows alone were too thin to read across a field, and
	 * one arrow the length of the leg would be a single enormous head.
	 */
	private static void flow(Route.Waypoint from, Route.Waypoint to, boolean closing,
			boolean measurable, double viewX, double viewZ) {
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 1.0 || length > MAX_LEG) {
			return;
		}

		int colour = closing ? RETURN : colourFor(to);
		Gizmos.line(along(from, dx, dy, dz, 0.0), along(from, dx, dy, dz, 1.0), colour,
				closing ? RETURN_PATH_WIDTH : PATH_WIDTH)
			.setAlwaysOnTop();

		if (measurable && beyond(from, to, viewX, viewZ)) {
			return;
		}

		double spacing = Math.max(SPACING, length / MAX_ARROWS);
		int steps = (int) Math.floor(length / spacing);
		double head = Math.min(ARROW_LENGTH, spacing * 0.6) / length;
		float width = closing ? RETURN_ARROW : ARROW;

		for (int i = 0; i < steps; i++) {
			double start = (i + 0.5) * spacing / length;
			double end = Math.min(1.0, start + head);
			Gizmos.arrow(along(from, dx, dy, dz, start), along(from, dx, dy, dz, end), colour, width)
				.setAlwaysOnTop();
		}
	}

	private static Vec3 along(Route.Waypoint from, double dx, double dy, double dz, double t) {
		return new Vec3(from.x + dx * t, from.y + dy * t + PATH_HEIGHT, from.z + dz * t);
	}

	/** True when neither end of a leg is close enough to make out an arrowhead. */
	private static boolean beyond(Route.Waypoint from, Route.Waypoint to,
			double viewX, double viewZ) {
		return away(from, viewX, viewZ) > ARROW_RANGE && away(to, viewX, viewZ) > ARROW_RANGE;
	}

	private static double away(Route.Waypoint point, double viewX, double viewZ) {
		double dx = point.x - viewX;
		double dz = point.z - viewZ;
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static int translucent(int colour) {
		return (colour & 0x00FFFFFF) | 0x33000000;
	}

	private static GizmoStyle box(int colour) {
		return GizmoStyle.strokeAndFill(colour, STROKE, translucent(colour));
	}

	private static TextGizmo.Style number(int colour) {
		return TextGizmo.Style.forColorAndCentered(colour).withScale(NUMBER_SCALE);
	}

	private static TextGizmo.Style caption(int colour) {
		return TextGizmo.Style.forColorAndCentered(colour).withScale(TEXT_SCALE);
	}

	private static GizmoStyle boxStyle(int colour) {
		if (colour == GREEN) {
			return GREEN_BOX;
		}
		return colour == ORANGE ? ORANGE_BOX : GREY_BOX;
	}

	private static TextGizmo.Style numberStyle(int colour) {
		if (colour == GREEN) {
			return GREEN_NUMBER;
		}
		return colour == ORANGE ? ORANGE_NUMBER : GREY_NUMBER;
	}

	private static int colourFor(Route.Waypoint point) {
		if (point.actions.isEmpty() && !point.walk) {
			return GREY;
		}
		for (Route.Action action : point.actions) {
			if (action.isSpam()) {
				return ORANGE;
			}
		}
		return GREEN;
	}

	private static String describe(Route.Waypoint point) {
		StringBuilder text = new StringBuilder();
		if (point.walk) {
			text.append("walk here");
		} else if (point.actions.isEmpty()) {
			text.append("nothing set");
		}
		for (int i = 0; i < point.actions.size(); i++) {
			if (i == 0 && text.length() > 0) {
				text.append(" + ");
			}
			Route.Action action = point.actions.get(i);
			if (i > 0) {
				text.append(" + ");
			}
			text.append(switch (action.mode) {
				case Route.SPAM -> "spam " + action.key + " /" + action.intervalTicks + "t";
				case Route.ONCE -> "click " + action.key;
				default -> "hold " + action.key;
			});
		}
		if (point.sends()) {
			text.append("  >> ").append(point.send);
		}
		return text.toString();
	}
}
