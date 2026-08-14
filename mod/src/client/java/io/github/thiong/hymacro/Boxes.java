package io.github.thiong.hymacro;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the route in the world: a box standing on every point, and flat boxes
 * along the ground between them.
 *
 * <p>The colour says what a leg does, so its shape can be read at a glance
 * without asking for a list: green where a key is held, orange where something
 * is clicked repeatedly, grey where nothing has been set yet.
 */
public final class Boxes {
	private static final double SPACING = 1.0;
	private static final double MAX_LEG = 400.0;
	private static final float TRAIL_ALPHA = 0.6f;

	private static final float[] IDLE = {0.6f, 0.6f, 0.6f};
	private static final float[] SPAM = {1.0f, 0.6f, 0.1f};
	private static final float[] HOLD = {0.3f, 0.9f, 0.4f};

	private Boxes() {
	}

	public static void register(Supplier<Route> source) {
		WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
			Route route = source.get();
			if (!route.visible || route.isEmpty()) {
				return;
			}

			PoseStack pose = context.matrixStack();
			MultiBufferSource consumers = context.consumers();
			if (pose == null || consumers == null) {
				return;
			}

			Vec3 camera = context.camera().getPosition();
			VertexConsumer lines = consumers.getBuffer(RenderType.lines());
			List<Route.Waypoint> points = route.waypoints;

			pose.pushPose();
			pose.translate(-camera.x, -camera.y, -camera.z);
			for (int i = 0; i < points.size(); i++) {
				Route.Waypoint to = points.get(i);
				float[] colour = colourFor(to);
				LevelRenderer.renderLineBox(
					pose, lines, stand(to), colour[0], colour[1], colour[2], 1.0f);
				if (points.size() > 1) {
					trail(pose, lines, points.get((i + points.size() - 1) % points.size()), to, colour);
				}
			}
			pose.popPose();
		});
	}

	/** A full block at the point itself, so it reads as somewhere to stand. */
	private static AABB stand(Route.Waypoint point) {
		return new AABB(
			point.x - 0.5, point.y, point.z - 0.5,
			point.x + 0.5, point.y + 1.0, point.z + 0.5);
	}

	private static void trail(
			PoseStack pose, VertexConsumer lines,
			Route.Waypoint from, Route.Waypoint to, float[] colour) {
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < SPACING || length > MAX_LEG) {
			return;
		}

		int steps = (int) Math.floor(length / SPACING);
		for (int i = 1; i < steps; i++) {
			double t = (double) i / steps;
			double x = from.x + dx * t;
			double y = from.y + dy * t;
			double z = from.z + dz * t;
			AABB flat = new AABB(x - 0.35, y + 0.02, z - 0.35, x + 0.35, y + 0.06, z + 0.35);
			LevelRenderer.renderLineBox(
				pose, lines, flat, colour[0], colour[1], colour[2], TRAIL_ALPHA);
		}
	}

	private static float[] colourFor(Route.Waypoint point) {
		if (point.actions.isEmpty()) {
			return IDLE;
		}
		for (Route.Action action : point.actions) {
			if (action.isSpam()) {
				return SPAM;
			}
		}
		return HOLD;
	}
}
