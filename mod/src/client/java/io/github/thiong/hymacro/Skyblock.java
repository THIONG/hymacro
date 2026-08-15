package io.github.thiong.hymacro;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Where on Skyblock the player is.
 *
 * <p>Read from the scoreboard, where every island writes its name after a marker
 * of its own. Nothing has to be sent to ask, which matters: the moment worth
 * noticing is a server restart, and a restart is a poor time to be waiting on a
 * reply from the server that just went away.
 *
 * <p>Not knowing is a third answer and kept apart from the other two. Between
 * worlds the board is empty for a moment, and an empty board read as "somewhere
 * else" would fire on every loading screen.
 */
public final class Skyblock {
	/**
	 * The mark Skyblock puts before the island name on the scoreboard.
	 *
	 * <p>Written as its number rather than as itself, so that what the compiler
	 * reads cannot depend on how this file was saved or on the encoding of the
	 * machine building it.
	 */
	private static final char AREA = '⏣';

	private Skyblock() {
	}

	/** The island the player is on, or null when the board does not say. */
	public static String location(Minecraft client) {
		for (String line : sidebar(client)) {
			int mark = line.indexOf(AREA);
			if (mark < 0) {
				continue;
			}
			String name = tidy(line.substring(mark + 1));
			if (!name.isEmpty()) {
				return name;
			}
		}
		return null;
	}

	/**
	 * The scoreboard as text.
	 *
	 * <p>Public because failing to find the island is worth showing rather than
	 * only reporting: what the board actually said is the whole of the evidence,
	 * and asking for it separately is a command that exists for one bad day.
	 */
	public static List<String> sidebar(Minecraft client) {
		List<String> lines = new ArrayList<>();
		if (client.level == null) {
			return lines;
		}
		Scoreboard board = client.level.getScoreboard();
		Objective side = board.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (side == null) {
			return lines;
		}
		for (PlayerScoreEntry entry : board.listPlayerScores(side)) {
			String line = plain(shown(board, entry)).trim();
			if (!line.isEmpty()) {
				lines.add(line);
			}
		}
		return lines;
	}

	private static String shown(Scoreboard board, PlayerScoreEntry entry) {
		PlayerTeam team = board.getPlayersTeam(entry.owner());
		return team == null
			? entry.owner()
			: PlayerTeam.formatNameForTeam(team, Component.literal(entry.owner())).getString();
	}

	/**
	 * The name alone.
	 *
	 * <p>The line carries more than the island: a guest count, a lobby icon, the
	 * odd emblem. Keeping letters, digits and spaces leaves the name and drops
	 * the rest, so the same island reads the same however it is decorated today.
	 */
	private static String tidy(String text) {
		StringBuilder name = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char letter = text.charAt(i);
			if (Character.isLetterOrDigit(letter) || letter == ' ' || letter == '\'') {
				name.append(letter);
			} else if (name.length() > 0) {
				// Anything else ends the name: what follows it is decoration.
				break;
			}
		}
		return name.toString().trim();
	}

	/** Colour codes out, so a name is compared on its letters. */
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
}
