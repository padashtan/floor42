package dev.floor42.domain;

import java.util.regex.Pattern;

/** Common contract of every typed identifier: a short, printable, non-blank string. */
public interface Identifier {

    Pattern FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    String value();

    static String check(String value, String kind) {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new InvalidIdentifierException("Invalid " + kind + " id: " + value);
        }
        return value;
    }
}
