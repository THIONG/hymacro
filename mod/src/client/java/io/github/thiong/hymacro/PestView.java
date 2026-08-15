package io.github.thiong.hymacro;

import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Drawing a pest in red: an outline on it, and a line from you to it.
 *
 * <p>The two answer different questions. The outline answers <em>which one of
 * these is the pest</em>, and is depth tested like anything else in the world:
 * it comes into view as the pest does, and a wall hides it exactly as the wall
 * hides the mob. Nothing is revealed that was not already on the screen.
 *
 * <p>The line answers <em>where is it</em>, which is the part that is actually
 * hard. A pest is a few pixels at thirty blocks and the same colour as the
 * ground it stands on, so knowing one exists is no help at all without a
 * direction to walk in. The line is drawn through terrain because a direction
 * you can only see once you have already found the thing is not a direction.
 *
 * <p>The label is the same answer in numbers, for when the line is nearly
 * end on.
 */
public final class PestView {
	private static final int RED = 0xFFFF3B30;

	/** Dimmer, for a place one was rather than a pest that is there. */
	private static final int REMEMBERED = 0xFFB2453E;

	/** A remembered pest is a spot on the ground, so it is drawn as one. */
	private static final double MARK_SIZE = 0.45;

	private static final float STROKE = 2.0f;
	/** Thick enough to pick out across a plot, which is what it is for. */
	private static final float LINE_WIDTH = 4.5f;
	private static final float TEXT_SCALE = 0.8f;

	/** Enough that the outline sits off the mob rather than in it. */
	private static final double MARGIN = 0.12;
	private static final double LABEL_HEIGHT = 0.6;

	/** The line leaves at ankle height, like the route's own paths do. */
	private static final double LINE_HEIGHT = 0.35;

	private static final GizmoStyle OUTLINE =
		GizmoStyle.strokeAndFill(RED, STROKE, (RED & 0x00FFFFFF) | 0x33000000);
	private static final GizmoStyle MARK =
		GizmoStyle.strokeAndFill(REMEMBERED, STROKE, (REMEMBERED & 0x00FFFFFF) | 0x33000000);
	private static final TextGizmo.Style LABEL =
		TextGizmo.Style.forColorAndCentered(RED).withScale(TEXT_SCALE);
	private static final TextGizmo.Style FADED =
		TextGizmo.Style.forColorAndCentered(REMEMBERED).withScale(TEXT_SCALE);

	private PestView() {
	}

	public static void register(Supplier<Pests> source) {
		LevelRenderEvents.BEFORE_GIZMOS.register(context -> draw(source.get()));
	}

	/**
	 * Drawn from where the pest is this instant, not where it was this tick.
	 *
	 * <p>A mob moves twenty times a second and is drawn a hundred, and the game
	 * covers the gap by rendering it part of the way between its last two
	 * positions. An outline built from the tick position alone sits on where the
	 * mob was rather than where it is, which on anything that moves reads as the
	 * box lagging behind and shuddering. Asking for the same interpolated
	 * position the mob itself is drawn at puts the box on the mob.
	 *
	 * <p>The pests are looked up by id rather than found by walking the world.
	 * There is normally one of them and can be hundreds of entities, and this
	 * runs on every frame.
	 */
	private static void draw(Pests pests) {
		if (pests == null || pests.isEmpty()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) {
			return;
		}

		double px = client.player.getX();
		double py = client.player.getY();
		double pz = client.player.getZ();
		Vec3 from = new Vec3(px, py + LINE_HEIGHT, pz);
		float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);

		List<Pests.Tracked> tracked = pests.tracked();
		for (int i = 0; i < tracked.size(); i++) {
			Pests.Tracked pest = tracked.get(i);
			Entity entity = client.level.getEntity(pest.id());
			if (entity == null) {
				continue;
			}

			// The box the game collides with, carried to where the mob is being
			// drawn this frame.
			Vec3 at = entity.getPosition(partial);
			AABB box = entity.getBoundingBox()
				.move(at.x - entity.getX(), at.y - entity.getY(), at.z - entity.getZ())
				.inflate(MARGIN);
			Gizmos.cuboid(box, OUTLINE);

			double x = (box.minX + box.maxX) / 2.0;
			double y = (box.minY + box.maxY) / 2.0;
			double z = (box.minZ + box.maxZ) / 2.0;
			Gizmos.line(from, new Vec3(x, y, z), RED, LINE_WIDTH).setAlwaysOnTop();

			Gizmos.billboardText(pest.name() + "  " + away(px, py, pz, x, y, z) + "m",
					new Vec3(x, box.maxY + LABEL_HEIGHT, z), LABEL)
				.setAlwaysOnTop();
		}

		// The ones the server has stopped sending, drawn where they were last
		// actually seen. A pest stays in the plot it spawned in, so that is
		// still the answer to which way to walk, which is the whole question
		// once it is too far away to be a mob at all.
		List<Pests.Mark> remembered = pests.remembered();
		for (int i = 0; i < remembered.size(); i++) {
			Pests.Mark mark = remembered.get(i);
			Gizmos.cuboid(new AABB(
					mark.x() - MARK_SIZE, mark.y(), mark.z() - MARK_SIZE,
					mark.x() + MARK_SIZE, mark.y() + MARK_SIZE * 2.0, mark.z() + MARK_SIZE), MARK)
				.setAlwaysOnTop();
			Gizmos.line(from, new Vec3(mark.x(), mark.y() + MARK_SIZE, mark.z()),
					REMEMBERED, LINE_WIDTH)
				.setAlwaysOnTop();
			Gizmos.billboardText(
					mark.name() + "  ~" + away(px, py, pz, mark.x(), mark.y(), mark.z()) + "m",
					new Vec3(mark.x(), mark.y() + MARK_SIZE * 2.0 + LABEL_HEIGHT, mark.z()), FADED)
				.setAlwaysOnTop();
		}
	}

	private static long away(double px, double py, double pz, double x, double y, double z) {
		double dx = x - px;
		double dy = y - py;
		double dz = z - pz;
		return Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
	}
}
