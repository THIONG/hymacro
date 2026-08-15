package io.github.thiong.hymacro;

/**
 * Where a plot number is, on the ground.
 *
 * <p>The Garden is a fixed five by five of ninety six block plots with the barn
 * in the middle, numbered outwards in a spiral. That never moves, so a number
 * from the tab list becomes somewhere to fly to with a lookup and no searching.
 *
 * <p>{@code /hymacro pests plot} checks the table against where the server says
 * you are standing, which is the only thing that can settle it.
 */
public final class GardenPlots {
	/**
	 * North west to south east, the barn as zero.
	 *
	 * <p>Taken from SkyHanni, which has had this right for years, rather than
	 * from reading a menu and hoping. Written out from the menu it was wrong in
	 * four of its five rows: the spiral is not the one it looks like.
	 */
	private static final int[][] LAYOUT = {
		{21, 13,  9, 14, 22},
		{15,  5,  1,  6, 16},
		{10,  2,  0,  3, 11},
		{17,  7,  4,  8, 18},
		{23, 19, 12, 20, 24},
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
