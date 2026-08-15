package io.github.thiong.hymacro;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * Finding the pests eating the plot, wherever they are.
 *
 * <p>A pest is the one thing that reliably ruins a run: it eats the crops the
 * macro is walking through, and the first sign of it is usually the yield
 * dropping rather than the pest itself. They are small, they are the colour of
 * the ground, and they are behind a wall of wart as often as not.
 *
 * <p>So they are looked for everywhere the client knows about, and drawn by
 * {@link PestView} once found. What "everywhere" means is worth being exact
 * about: a client is told about the entities near it and nothing else, so this
 * finds a pest anywhere in range of the plot you are on and cannot invent one
 * further out.
 *
 * <p>What is kept between ticks is entity <em>ids</em>, never entities. Holding
 * a mob would hold the world it belongs to, and a mod that quietly keeps every
 * world you have visited is a slow leak with no symptom until the game stops.
 * An int cannot do that.
 */
public final class Pests {
	/**
	 * What the Garden calls them, one per crop.
	 *
	 * <p>Matched by name because that is what the server sends: underneath, a
	 * pest is an ordinary bat or silverfish wearing a custom head, which is not
	 * something worth telling apart from the rest of the world's bats. If
	 * Hypixel adds one this list does not know, {@code /hymacro pests scan}
	 * shows what is actually out there and the new name goes here.
	 */
	static final List<String> NAMES = List.of(
		"Fly",            // wheat
		"Cricket",        // carrot
		"Locust",         // potato
		"Rat",            // pumpkin
		"Mosquito",       // sugar cane
		"Earthworm",      // melon
		"Mite",           // cactus
		"Moth",           // cocoa beans
		"Slug",           // mushroom
		"Beetle",         // nether wart
		"Dragonfly",      // sunflower, daytime only
		"Firefly",        // moonflower, night only
		"Praying Mantis", // wild rose
		"Lunar Moth",     // any of the three flowers
		"Field Mouse");   // any crop at all

	/**
	 * The same names, lowered once.
	 *
	 * <p>Matching lowered the entity's name once per candidate, so every entity
	 * in range was lowered fifteen times a scan and each of those built a
	 * string. Four scans a second across a busy plot is thousands of them a
	 * second for an answer that never changes.
	 */
	private static final List<String> LOWERED = NAMES.stream()
		.map(name -> name.toLowerCase(Locale.ROOT))
		.toList();

	/** Four times a second. A pest walks; it does not teleport. */
	private static final int EVERY_TICKS = 5;

	/** How far a floating label may sit from the thing it names. */
	private static final double TAG_REACH = 2.5;

	/** Under this, a box is a label's mounting rather than a body. */
	private static final double BODY = 0.3;

	/** Where a scan looks, and how much of it it prints. */
	private static final double SCAN_RANGE = 32.0;
	private static final int SCAN_LINES = 24;

	/**
	 * How close you have to be before not seeing one means it is gone.
	 *
	 * <p>A pest out of range and a pest killed look identical from here: in both
	 * cases the server simply stops sending it. The difference is where you are
	 * standing. Well inside the range things are sent from, an empty spot is an
	 * empty spot, so the memory is dropped. From across the Garden it means
	 * nothing, so it is kept.
	 */
	private static final double WOULD_SEE_IT = 24.0;

	/** Ten minutes. Long enough to cross the Garden and come back. */
	private static final int REMEMBER_TICKS = 12000;

	/** A pest the server is still sending, to be looked up and drawn on. */
	public record Tracked(int id, String name) {
	}

	/** A pest, and the last place it was actually seen. */
	public record Mark(String name, double x, double y, double z) {
	}

	private static final class Sighting {
		final int id;
		final String name;
		double x;
		double y;
		double z;
		boolean live;
		int unseenTicks;

		Sighting(int id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	private final Map<Integer, Sighting> seen = new HashMap<>();

	/**
	 * What the drawing walks, rebuilt when the world is searched rather than
	 * when it is drawn.
	 *
	 * <p>Drawing happens a hundred times a second and searching four, so a list
	 * built for the drawing to read is built four times a second instead of a
	 * hundred. Nothing here holds an entity: an id is looked up against the
	 * world each frame, which is a lookup rather than a reference, and cannot
	 * keep a world alive after it is left.
	 */
	private final List<Tracked> live = new ArrayList<>();
	private final List<Mark> marks = new ArrayList<>();

	private int countdown;

	/**
	 * Everything the client knows about, in one place.
	 *
	 * <p>This is the only call in the mod that asks the world for its contents,
	 * and this Minecraft version publishes no mappings to check a name against.
	 * Kept to one method, being wrong about it is a line to change rather than
	 * four, and a round trip through CI rather than several.
	 */
	static Iterable<Entity> entities(Minecraft client) {
		return client.level.entitiesForRendering();
	}

	/** Whether there is anything to draw, cheap enough to ask every frame. */
	public boolean isEmpty() {
		return live.isEmpty() && marks.isEmpty();
	}

	/** How many are in front of you right now. */
	public int count() {
		return live.size();
	}

	/** The ones the server is still sending, to be drawn on where they are. */
	public List<Tracked> tracked() {
		return live;
	}

	/** Where the ones out of range were last actually seen. */
	public List<Mark> remembered() {
		return marks;
	}

	public void forget() {
		seen.clear();
		live.clear();
		marks.clear();
	}

	public void tick(Minecraft client) {
		if (client.level == null || client.player == null) {
			forget();
			return;
		}
		if (countdown > 0) {
			countdown--;
			return;
		}
		countdown = EVERY_TICKS;
		scan(client);
	}

	/**
	 * What is in front of you, and what used to be.
	 *
	 * <p>A client is only told about the entities near it, so a pest twenty
	 * blocks away is a mob and the same pest across the Garden is nothing at
	 * all. Marking only what is being sent means the mark goes out exactly when
	 * you walk far enough away to need it.
	 *
	 * <p>So where one was last actually seen is kept. A pest stays in the plot
	 * it spawned in, which makes a remembered position as good as a live one for
	 * the only question being asked of it: which way do I walk.
	 */
	private void scan(Minecraft client) {
		for (Sighting sighting : seen.values()) {
			sighting.live = false;
		}

		for (Entity entity : entities(client)) {
			String name = named(entity);
			if (name == null) {
				continue;
			}
			Entity marked = hasBody(entity) ? entity : bodyUnder(client, entity);
			Sighting sighting = seen.get(marked.getId());
			if (sighting == null || !sighting.name.equals(name)) {
				sighting = new Sighting(marked.getId(), name);
				seen.put(marked.getId(), sighting);
			}
			sighting.x = marked.getX();
			sighting.y = marked.getY();
			sighting.z = marked.getZ();
			sighting.live = true;
			sighting.unseenTicks = 0;
		}

		forgetWhatIsGone(client);
	}

	/**
	 * Dropping the memories that have stopped meaning anything.
	 *
	 * <p>Two ways one stops. Standing where it was and finding nothing there is
	 * the pest having died, since at that range it would be being sent. And
	 * anything unseen for ten minutes is stale whatever the reason, because a
	 * red mark you cannot explain is worse than no mark.
	 */
	private void forgetWhatIsGone(Minecraft client) {
		double px = client.player.getX();
		double py = client.player.getY();
		double pz = client.player.getZ();

		Iterator<Sighting> memories = seen.values().iterator();
		while (memories.hasNext()) {
			Sighting sighting = memories.next();
			if (sighting.live) {
				continue;
			}
			sighting.unseenTicks += EVERY_TICKS;

			double dx = sighting.x - px;
			double dy = sighting.y - py;
			double dz = sighting.z - pz;
			boolean lookedAndEmpty = dx * dx + dy * dy + dz * dz < WOULD_SEE_IT * WOULD_SEE_IT;
			if (lookedAndEmpty || sighting.unseenTicks > REMEMBER_TICKS) {
				memories.remove();
			}
		}

		live.clear();
		marks.clear();
		for (Sighting sighting : seen.values()) {
			if (sighting.live) {
				live.add(new Tracked(sighting.id, sighting.name));
			} else {
				marks.add(new Mark(sighting.name, sighting.x, sighting.y, sighting.z));
			}
		}
	}

	/**
	 * The pest an entity is called, or null.
	 *
	 * <p>Whole words only. A player called Piratebay is not a rat, and the cost
	 * of getting that wrong is a red box on somebody's back for the rest of the
	 * session. It also keeps a dragonfly from reading as a fly, which it is not:
	 * they eat different crops and one of them only comes out at night.
	 *
	 * <p>The longest name wins, because two of them contain another. A Lunar
	 * Moth is a moth by the letters and something else entirely by the loot.
	 */
	private static String named(Entity entity) {
		if (entity instanceof Player) {
			return null;
		}
		Component custom = entity.getCustomName();
		if (custom == null) {
			return null;
		}
		String text = plain(custom.getString()).toLowerCase(Locale.ROOT);
		String best = null;
		for (int i = 0; i < NAMES.size(); i++) {
			String name = NAMES.get(i);
			if (best != null && name.length() <= best.length()) {
				continue;
			}
			if (mentions(text, LOWERED.get(i))) {
				best = name;
			}
		}
		return best;
	}

	/**
	 * The thing the label is attached to, or the label itself.
	 *
	 * <p>A named mob is sometimes the mob and sometimes an invisible marker
	 * floating over it, and which one it is has never been ours to decide. A
	 * marker has no body worth drawing a box around, so the nearest body under
	 * it is used instead: a box around a nametag is a box around nothing.
	 */
	private static Entity bodyUnder(Minecraft client, Entity tag) {
		Entity best = tag;
		double nearest = TAG_REACH * TAG_REACH;
		for (Entity other : entities(client)) {
			if (other == tag || other instanceof Player || other.getCustomName() != null) {
				continue;
			}
			if (!hasBody(other) || other.getY() > tag.getY() + 0.5) {
				continue;
			}
			double dx = other.getX() - tag.getX();
			double dy = other.getY() - tag.getY();
			double dz = other.getZ() - tag.getZ();
			double away = dx * dx + dy * dy + dz * dz;
			if (away < nearest) {
				nearest = away;
				best = other;
			}
		}
		return best;
	}

	private static boolean hasBody(Entity entity) {
		AABB box = entity.getBoundingBox();
		return box.maxY - box.minY > BODY && box.maxX - box.minX > BODY;
	}

	/** Colour codes out, so a name is matched on its letters. */
	private static String plain(String text) {
		if (text.indexOf('§') < 0) {
			return text;
		}
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char letter = text.charAt(i);
			if (letter == '§') {
				i++;
				continue;
			}
			out.append(letter);
		}
		return out.toString();
	}

	/** Both already lowered, so this allocates nothing. */
	private static boolean mentions(String haystack, String needle) {
		int at = haystack.indexOf(needle);
		while (at >= 0) {
			int end = at + needle.length();
			boolean clearBefore = at == 0 || !Character.isLetter(haystack.charAt(at - 1));
			boolean clearAfter = end == haystack.length() || !Character.isLetter(haystack.charAt(end));
			if (clearBefore && clearAfter) {
				return true;
			}
			at = haystack.indexOf(needle, at + 1);
		}
		return false;
	}

	/**
	 * Everything around you and what it is called, nearest first.
	 *
	 * <p>The names above are a guess at what Hypixel sends until somebody stands
	 * next to a pest and looks. This is how you look: it prints what the server
	 * actually said, so a pest that goes unmarked can be named rather than
	 * argued about.
	 */
	public List<String> nearby(Minecraft client) {
		List<String> lines = new ArrayList<>();
		if (client.level == null || client.player == null) {
			return lines;
		}

		double px = client.player.getX();
		double py = client.player.getY();
		double pz = client.player.getZ();

		List<Entity> around = new ArrayList<>();
		for (Entity entity : entities(client)) {
			if (entity == client.player || away(entity, px, py, pz) > SCAN_RANGE) {
				continue;
			}
			around.add(entity);
		}
		around.sort(Comparator.comparingDouble((Entity entity) -> away(entity, px, py, pz)));

		for (int i = 0; i < around.size() && i < SCAN_LINES; i++) {
			Entity entity = around.get(i);
			Component custom = entity.getCustomName();
			String label = custom == null ? "-" : "\"" + plain(custom.getString()) + "\"";
			lines.add(Math.round(away(entity, px, py, pz)) + "m  "
				+ entity.getClass().getSimpleName() + "  " + label
				+ (named(entity) == null ? "" : "  <- pest"));
		}
		if (around.size() > SCAN_LINES) {
			lines.add("and " + (around.size() - SCAN_LINES) + " more");
		}
		return lines;
	}

	/**
	 * Everywhere the server writes text, for finding where it says what.
	 *
	 * <p>Entities only exist near you, so a pest three plots away is nothing the
	 * client has been told about as a thing in the world. It has been told in
	 * words, though: the count per plot is on screen the whole time, which is how
	 * the other mods draw a box over a plot that is not even loaded.
	 *
	 * <p>Which of the several places it arrives in is not something to guess at.
	 * This prints them, so the parsing is written against what the server
	 * actually sends rather than against a hope.
	 */
	public List<String> sources(Minecraft client) {
		List<String> lines = new ArrayList<>();
		if (client.getConnection() == null) {
			return lines;
		}

		lines.add("-- tab list --");
		int quiet = 0;
		for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
			Component shown = info.getTabListDisplayName();
			String text = shown == null ? "" : plain(shown.getString()).trim();
			if (text.isEmpty()) {
				quiet++;
				continue;
			}
			if (interesting(text)) {
				lines.add("  " + text);
			} else {
				quiet++;
			}
		}
		lines.add("  (" + quiet + " more with nothing about pests or plots)");

		if (client.level == null) {
			return lines;
		}

		Scoreboard board = client.level.getScoreboard();
		Objective side = board.getDisplayObjective(DisplaySlot.SIDEBAR);
		lines.add("-- sidebar --");
		if (side == null) {
			lines.add("  (none)");
			return lines;
		}
		lines.add("  title: " + plain(side.getDisplayName().getString()));
		for (PlayerScoreEntry entry : board.listPlayerScores(side)) {
			PlayerTeam team = board.getPlayersTeam(entry.owner());
			String text = team == null
				? entry.owner()
				: plain(PlayerTeam.formatNameForTeam(team, Component.literal(entry.owner()))
					.getString());
			text = text.trim();
			if (!text.isEmpty()) {
				lines.add("  " + text);
			}
		}
		return lines;
	}

	/** Only the lines that could plausibly be about this. */
	private static boolean interesting(String text) {
		String lowered = text.toLowerCase(Locale.ROOT);
		return lowered.contains("pest") || lowered.contains("plot");
	}

	private static double away(Entity entity, double x, double y, double z) {
		double dx = entity.getX() - x;
		double dy = entity.getY() - y;
		double dz = entity.getZ() - z;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
