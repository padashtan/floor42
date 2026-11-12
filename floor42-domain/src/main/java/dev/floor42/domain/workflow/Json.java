package dev.floor42.domain.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The little JSON a workflow document needs, read and written by the domain itself (JDK only): objects, arrays,
 * strings, small non-negative integers, {@code true}, {@code false} and {@code null}. Bounded (ADR-0050): a document of
 * at most {@link #MAX_LENGTH} characters, nested at most {@link #MAX_DEPTH} levels, numbers of at most two digits.
 * Every malformed input is refused with a {@link Refused} naming the position; no other exception leaves it.
 */
final class Json {

    static final int MAX_LENGTH = 8 * 1024;
    static final int MAX_DEPTH = 4;
    private static final int MAX_DIGITS = 2;

    /** A document this reader refuses, with the reason the director reads. */
    static final class Refused extends Exception {
        Refused(String reason) {
            super(reason);
        }
    }

    /** The {@code null} literal, told apart from a missing key. */
    static final Object NULL = new Object();

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /** Objects read as ordered maps (a key twice is refused), arrays as lists, integers as {@link Integer}. */
    static Object parse(String text) throws Refused {
        return parse(text, MAX_LENGTH);
    }

    /** As {@link #parse(String)}, for a reader whose documents may be longer (schema version 2, ADR-0052). */
    static Object parse(String text, int maxLength) throws Refused {
        if (text == null || text.isEmpty()) throw new Refused("The document is empty");
        if (text.length() > maxLength) throw new Refused("The document is longer than " + maxLength + " characters");
        Json json = new Json(text);
        Object value = json.value(1);
        json.blank();
        if (json.at != text.length()) throw json.refused("Text after the document");
        return value;
    }

    private Object value(int depth) throws Refused {
        blank();
        if (at >= text.length()) throw refused("The document ends too early");
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", NULL);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) throws Refused {
        nest(depth);
        at++;
        Map<String, Object> map = new LinkedHashMap<>();
        if (next() == '}') {
            at++;
            return map;
        }
        do {
            blank();
            if (at >= text.length() || text.charAt(at) != '"') throw refused("A key is expected");
            String key = string();
            if (map.containsKey(key)) throw refused("The key \"" + key + "\" is given twice");
            expect(':');
            map.put(key, value(depth + 1));
        } while (separator('}'));
        return map;
    }

    private List<Object> array(int depth) throws Refused {
        nest(depth);
        at++;
        List<Object> list = new ArrayList<>();
        if (next() == ']') {
            at++;
            return list;
        }
        do list.add(value(depth + 1));
        while (separator(']'));
        return list;
    }

    /** Counted before descending: no document can nest deep enough to exhaust the stack. */
    private void nest(int depth) throws Refused {
        if (depth > MAX_DEPTH) throw refused("The document nests more than " + MAX_DEPTH + " levels");
    }

    /** After a member: a comma goes on, the closing character ends. */
    private boolean separator(char close) throws Refused {
        char c = next();
        at++;
        if (c == ',') return true;
        if (c == close) return false;
        throw refused("',' or '" + close + "' is expected");
    }

    private void expect(char c) throws Refused {
        if (next() != c) throw refused("'" + c + "' is expected");
        at++;
    }

    /** The next character that is not blank; refused at the end of the text. */
    private char next() throws Refused {
        blank();
        if (at >= text.length()) throw refused("The document ends too early");
        return text.charAt(at);
    }

    private void blank() {
        while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++;
    }

    private Object literal(String word, Object value) throws Refused {
        if (!text.startsWith(word, at)) throw refused("Unknown word");
        at += word.length();
        return value;
    }

    private Integer number() throws Refused {
        int start = at;
        while (at < text.length() && Character.isDigit(text.charAt(at)) && text.charAt(at) < 128) at++;
        if (at == start) throw refused("A value is expected");
        if (at - start > MAX_DIGITS) throw refused("A number of more than " + MAX_DIGITS + " digits");
        return Integer.valueOf(text.substring(start, at));
    }

    private String string() throws Refused {
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) throw refused("A text is not closed");
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw refused("A control character inside a text");
            if (c != '\\') out.append(c);
            else out.append(escape());
        }
    }

    private char escape() throws Refused {
        if (at >= text.length()) throw refused("A text is not closed");
        char c = text.charAt(at++);
        return switch (c) {
            case '"', '\\', '/' -> c;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> unicode();
            default -> throw refused("Unknown escape \\" + c);
        };
    }

    /** Four hexadecimal digits, checked before they are decoded. */
    private char unicode() throws Refused {
        if (at + 4 > text.length()) throw refused("A \\u escape needs four hexadecimal digits");
        int code = 0;
        for (int i = 0; i < 4; i++) {
            int d = Character.digit(text.charAt(at + i), 16);
            if (d < 0) throw refused("A \\u escape needs four hexadecimal digits");
            code = code * 16 + d;
        }
        at += 4;
        return (char) code;
    }

    private Refused refused(String why) {
        return new Refused(why + " (at character " + (at + 1) + ")");
    }

    /** A JSON string literal of the text. */
    static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    /** The text of a value this reader returned: objects, arrays, strings, integers, booleans and the null literal. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    out.append(quote((String) e.getKey())).append(':');
                    write(e.getValue(), out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(',');
                    write(list.get(i), out);
                }
                out.append(']');
            }
            case String s -> out.append(quote(s));
            default -> out.append(value == NULL ? "null" : String.valueOf(value));
        }
    }
}
