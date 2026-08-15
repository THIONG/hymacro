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

	/** Arrowheads for the whole macro, shared out, rather than per leg. */
	private static final int MOST_ARROWS = 96;

	private static final Vec3[] NO_ARROWS = new Vec3[0];

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

	/**
	 * Everything the macro draws, worked out once.
	 *
	 * <p>Nothing here depends on the frame: a macro is a fixed set of points, and
	 * its shapes and its words are the same this frame as last. Building them
	 * again a hundred times a second was making a heap of rubbish for the
	 * collector to sweep, which is felt as the game hitching rather than as
	 * memory running out.
	 *
	 * <p>Arrows are the bulk of it. They are shared out across the legs rather
	 * than counted per leg, so a macro with more legs does not cost more to look
	 * at than one with fewer.
	 */
	private static final class Drawing {
		AABB[] boxes;
		GizmoStyle[] boxStyles;
		Vec3[] numberAt;
		TextGizmo.Style[] numberStyles;
		String[] numbers;

		Vec3[] captionAt;
		String[] captions;
		TextGizmo.Style[] captionStyles;

		Vec3[] lineFrom;
		Vec3[] lineTo;
		int[] lineColour;
		float[] lineWidth;

		Vec3[] midpoint;
		Vec3[][] arrowFrom;
		Vec3[][] arrowTo;
		int[] arrowColour;
		float[] arrowWidth;
	}

	private static Drawing drawing;
	private static long stamp;

	private RouteView() {
	}

	public static void register(Supplier<Route> source) {
		LevelRenderEvents.BEFORE_GIZMOS.register(context -> draw(source.get()));
	}

	private static void draw(Route route) {
		if (route == null || !route.visible || route.isEmpty()) {
			return;
		}

		long now = stampOf(route);
		if (drawing == null || stamp != now) {
			drawing = build(route);
			stamp = now;
		}

		var viewer = Minecraft.getInstance().player;
		boolean measurable = viewer != null;
		double viewX = measurable ? viewer.getX() : 0.0;
		double viewZ = measurable ? viewer.getZ() : 0.0;

		Drawing shapes = drawing;
		for (int i = 0; i < shapes.boxes.length; i++) {
			Gizmos.cuboid(shapes.boxes[i], shapes.boxStyles[i]).setAlwaysOnTop();
			Gizmos.billboardText(shapes.numbers[i], shapes.numberAt[i], shapes.numberStyles[i])
				.setAlwaysOnTop();
			if (shapes.captionAt[i] != null) {
				Gizmos.billboardText(shapes.captions[i], shapes.captionAt[i],
						shapes.captionStyles[i])
					.setAlwaysOnTop();
			}
			if (shapes.lineFrom[i] == null) {
				continue;
			}
			Gizmos.line(shapes.lineFrom[i], shapes.lineTo[i], shapes.lineColour[i],
					shapes.lineWidth[i])
				.setAlwaysOnTop();

			if (measurable && far(shapes.midpoint[i], viewX, viewZ)) {
				continue;
			}
			Vec3[] from = shapes.arrowFrom[i];
			Vec3[] to = shapes.arrowTo[i];
			for (int a = 0; a < from.length; a++) {
				Gizmos.arrow(from[a], to[a], shapes.arrowColour[i], shapes.arrowWidth[i])
					.setAlwaysOnTop();
			}
		}
	}

	/**
	 * A number that changes exactly when the macro does.
	 *
	 * <p>Points are replaced rather than edited, so their identities answer the
	 * question. A handful of lookups a frame to avoid five hundred allocations is
	 * a trade worth making without thinking about it.
	 */
	private static long stampOf(Route route) {
		long value = route.waypoints.size();
		for (int i = 0; i < route.waypoints.size(); i++) {
			value = value * 31L + System.identityHashCode(route.waypoints.get(i));
		}
		return value;
	}

	private static Drawing build(Route route) {
		List<Route.Waypoint> points = route.waypoints;
		int count = points.size();

		Drawing made = new Drawing();
		made.boxes = new AABB[count];
		made.boxStyles = new GizmoStyle[count];
		made.numberAt = new Vec3[count];
		made.numberStyles = new TextGizmo.Style[count];
		made.numbers = new String[count];
		made.captionAt = new Vec3[count];
		made.captions = new String[count];
		made.captionStyles = new TextGizmo.Style[count];
		made.lineFrom = new Vec3[count];
		made.lineTo = new Vec3[count];
		made.lineColour = new int[count];
		made.lineWidth = new float[count];
		made.midpoint = new Vec3[count];
		made.arrowFrom = new Vec3[count][];
		made.arrowTo = new Vec3[count][];
		made.arrowColour = new int[count];
		made.arrowWidth = new float[count];

		int budget = Math.max(1, MOST_ARROWS / Math.max(1, count));

		for (int i = 0; i < count; i++) {
			Route.Waypoint to = points.get(i);
			int colour = colourFor(to);
			made.boxes[i] = stand(to);
			made.boxStyles[i] = boxStyle(colour);
			made.numberAt[i] = over(to, NUMBER_HEIGHT);
			made.numberStyles[i] = numberStyle(colour);
			made.numbers[i] = String.valueOf(i + 1);
			made.arrowFrom[i] = NO_ARROWS;
			made.arrowTo[i] = NO_ARROWS;

			if (count < 2) {
				made.captionAt[i] = over(to, TEXT_HEIGHT);
				made.captions[i] = describe(to);
				made.captionStyles[i] = CAPTION;
				continue;
			}

			Route.Waypoint from = points.get((i + count - 1) % count);
			boolean closing = i == 0;
			if (closing && from.sends()) {
				continue;
			}

			made.captionAt[i] = middle(from, to);
			made.captions[i] = describe(to);
			made.captionStyles[i] = closing ? RETURN_CAPTION : CAPTION;
			buildLeg(made, i, from, to, closing, budget);
		}
		return made;
	}

	private static void buildLeg(Drawing made, int i, Route.Waypoint from, Route.Waypoint to,
			boolean closing, int budget) {
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 1.0 || length > MAX_LEG) {
			return;
		}

		int colour = closing ? RETURN : colourFor(to);
		made.lineFrom[i] = along(from, dx, dy, dz, 0.0);
		made.lineTo[i] = along(from, dx, dy, dz, 1.0);
		made.lineColour[i] = colour;
		made.lineWidth[i] = closing ? RETURN_PATH_WIDTH : PATH_WIDTH;
		made.midpoint[i] = along(from, dx, dy, dz, 0.5);
		made.arrowColour[i] = colour;
		made.arrowWidth[i] = closing ? RETURN_ARROW : ARROW;

		double spacing = Math.max(SPACING, length / budget);
		int steps = (int) Math.floor(length / spacing);
		if (steps <= 0) {
			return;
		}
		double head = Math.min(ARROW_LENGTH, spacing * 0.6) / length;

		Vec3[] starts = new Vec3[steps];
		Vec3[] ends = new Vec3[steps];
		for (int a = 0; a < steps; a++) {
			double at = (a + 0.5) * spacing / length;
			starts[a] = along(from, dx, dy, dz, at);
			ends[a] = along(from, dx, dy, dz, Math.min(1.0, at + head));
		}
		made.arrowFrom[i] = starts;
		made.arrowTo[i] = ends;
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

	private static Vec3 along(Route.Waypoint from, double dx, double dy, double dz, double t) {
		return new Vec3(from.x + dx * t, from.y + dy * t + PATH_HEIGHT, from.z + dz * t);
	}

	private static boolean far(Vec3 at, double viewX, double viewZ) {
		double dx = at.x - viewX;
		double dz = at.z - viewZ;
		return dx * dx + dz * dz > ARROW_RANGE * ARROW_RANGE;
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
