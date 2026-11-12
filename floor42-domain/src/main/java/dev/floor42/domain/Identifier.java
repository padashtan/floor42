package dev.floor42.domain;

import java.util.regex.Pattern;

/** Common contract of every typed identifier: a short, printable, non-blank string. */
public interface Identifier {

    Pattern FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    String value();

    static String check(String value, String kind) {
        // "." and ".." are the format's only names for another folder: an id is used as a file or folder name.
        if (value == null || !FORMAT.matcher(value).matches() || value.chars().allMatch(c -> c == '.')) {
            throw new InvalidIdentifierException("Invalid " + kind + " id: " + value);
        }
        return value;
    }
}
