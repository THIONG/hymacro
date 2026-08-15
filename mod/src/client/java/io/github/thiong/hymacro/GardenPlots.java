package io.github.thiong.hymacro;

/**
 * Where a plot number is, on the ground.
 *
 * <p>The Garden is a fixed five by five of ninety six block plots with the barn
 * in the middle, numbered outwards in a spiral. That never moves, so a number
 * from the tab list becomes somewhere to fly to with a lookup and no searching.
 *
 * <p>The spiral below is the part worth doubting. It is what the plot menu shows
 * and it is not something the game tells the client, so
 * {@code /hymacro pests plot} exists to check it against where the server says
 * you are standing rather than to be believed on sight.
 */
public final class GardenPlots {
	/** North west to south east, the barn as zero. */
	private static final int[][] LAYOUT = {
		{21, 13,  9, 14, 22},
		{17,  5,  1,  6, 18},
		{11,  3,  0,  4, 12},
		{19,  7,  2,  8, 20},
		{23, 15, 10, 16, 24},
	};

	private static final int SIZE = 96;
	private static final int MIDDLE = 2;

	private GardenPlots() {
	}

	/** The middle of a plot, or null if there is no such plot. */
	public static double[] centreOf(int plot) {
		for (int row = 0; row < LAYOUT.length; row++) {
			for (int col = 0; col < LAYOUT[row].length; col++) {
				if (LAYOUT[row][col] == plot) {
					return new double[] {
						(col - MIDDLE) * (double) SIZE,
						(row - MIDDLE) * (double) SIZE,
					};
				}
			}
		}
		return null;
	}

	/** The plot a position falls in, 0 for the barn and -1 for off the grid. */
	public static int plotAt(double x, double z) {
		int col = (int) Math.floor(x / SIZE + 0.5) + MIDDLE;
		int row = (int) Math.floor(z / SIZE + 0.5) + MIDDLE;
		if (col < 0 || row < 0 || row >= LAYOUT.length || col >= LAYOUT[row].length) {
			return -1;
		}
		return LAYOUT[row][col];
	}
}
