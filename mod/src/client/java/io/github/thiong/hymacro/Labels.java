package io.github.thiong.hymacro;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Writes what each leg does above the middle of that leg.
 *
 * <p>The boxes say where the route goes and roughly what kind of work happens
 * on it; the text says exactly which keys. It is drawn far enough away to be
 * read while walking the plot, and dropped past a distance so a long route does
 * not turn the horizon into a wall of words.
 */
public final class Labels {
	private static final float SCALE = 0.03f;
	private static final double RANGE = 48.0;
	private static final float HEIGHT = 1.7f;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int BACKDROP = 0x50000000;
	private static final int FULL_BRIGHT = 0x00F000F0;

	private Labels() {
	}

	public static void register(Supplier<Route> source) {
		WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
			Route route = source.get();
			if (!route.visible || route.isEmpty()) {
				return;
			}

			Font font = Minecraft.getInstance().font;
			PoseStack pose = context.matrixStack();
			MultiBufferSource consumers = context.consumers();
			if (font == null || pose == null || consumers == null) {
				return;
			}

			Vec3 camera = context.camera().getPosition();
			Quaternionf facing = context.camera().rotation();
			List<Route.Waypoint> points = route.waypoints;

			for (int i = 0; i < points.size(); i++) {
				Route.Waypoint to = points.get(i);
				Route.Waypoint from = points.get((i + points.size() - 1) % points.size());
				double x = (from.x + to.x) / 2.0;
				double y = (from.y + to.y) / 2.0 + HEIGHT;
				double z = (from.z + to.z) / 2.0;
				if (camera.distanceToSqr(x, y, z) > RANGE * RANGE) {
					continue;
				}
				draw(pose, consumers, font, facing, camera, x, y, z, describe(i + 1, to));
			}
		});
	}

	private static void draw(
			PoseStack pose, MultiBufferSource consumers, Font font, Quaternionf facing,
			Vec3 camera, double x, double y, double z, String text) {
		pose.pushPose();
		pose.translate(x - camera.x, y - camera.y, z - camera.z);
		pose.mulPose(facing);
		pose.scale(-SCALE, -SCALE, SCALE);
		font.drawInBatch(
			Component.literal(text),
			-font.width(text) / 2.0f, 0.0f,
			WHITE, false,
			pose.last().pose(), consumers,
			Font.DisplayMode.SEE_THROUGH,
			BACKDROP, FULL_BRIGHT);
		pose.popPose();
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
