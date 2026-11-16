package dev.floor42.domain;

/** The one rule that turns an objective into a mission title: its first non-blank line, cut on a word. */
public final class MissionTitles {

    /** The length a title is read at on a board card (the screen's suggestion cuts the same way). */
    public static final int SUGGESTED_MAX = 60;

    private MissionTitles() {}

    public static String fromObjective(String objective) {
        String line = objective
                .lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty())
                .findFirst()
                .orElse("");
        if (line.length() <= SUGGESTED_MAX) return line;
        String cut = line.substring(0, SUGGESTED_MAX);
        int space = cut.lastIndexOf(' ');
        return (space > SUGGESTED_MAX / 2 ? cut.substring(0, space) : cut).stripTrailing();
    }
}
