package io.github.thiong.hymacro;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;

/**
 * Draws the route in the world with particles.
 *
 * <p>Particles rather than translucent boxes on purpose: a box needs the render
 * pipeline, which changes shape between Minecraft versions more than anything
 * else, and this version publishes no mappings to check against. Particles cost
 * one call and show the same thing.
 */
public final class Markers {
	private static final int EVERY_TICKS = 4;
	private static final double STEP = 0.5;

	private Markers() {
	}

	public static void draw(Minecraft client, List<Route.Waypoint> waypoints, int tick) {
		if (client.level == null || waypoints.isEmpty() || tick % EVERY_TICKS != 0) {
			return;
		}

		for (int i = 0; i < waypoints.size(); i++) {
			Route.Waypoint point = waypoints.get(i);
			client.level.addParticle(
				ParticleTypes.END_ROD, point.x, point.y + 0.2, point.z, 0.0, 0.0, 0.0);

			Route.Waypoint next = waypoints.get((i + 1) % waypoints.size());
			if (waypoints.size() > 1) {
				trail(client, point, next);
			}
		}
	}

	private static void trail(Minecraft client, Route.Waypoint from, Route.Waypoint to) {
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (length < 0.01 || length > 200.0) {
			return;
		}

		int steps = (int) Math.ceil(length / STEP);
		for (int i = 1; i < steps; i++) {
			double t = (double) i / steps;
			client.level.addParticle(
				ParticleTypes.COMPOSTER,
				from.x + dx * t,
				from.y + dy * t + 0.1,
				from.z + dz * t,
				0.0, 0.0, 0.0);
		}
	}
}
