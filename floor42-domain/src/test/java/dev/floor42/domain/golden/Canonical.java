package dev.floor42.domain.golden;

import dev.floor42.domain.Identifier;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The canonical text of any value of the domain: deep, deterministic, and blind to where a value is kept. Ordered
 * collections keep their order (an insertion order is behaviour); unordered ones are sorted, since their iteration order
 * is not.
 */
public final class Canonical {

    private final IdentityHashMap<Object, Boolean> path = new IdentityHashMap<>();
    private final StringBuilder out = new StringBuilder();

    private Canonical() {}

    public static String of(Object value) {
        Canonical c = new Canonical();
        c.write(value);
        return c.out.toString();
    }

    private void write(Object v) {
        switch (v) {
            case null -> out.append("null");
            case String s -> quote(s);
            case Number n -> out.append(n);
            case Boolean b -> out.append(b);
            case Character ch -> quote(String.valueOf(ch));
            case Enum<?> e -> out.append(e.name());
            case Identifier id -> quote(id.value());
            case TemporalAccessor t -> out.append(t);
            case java.time.Duration d -> out.append(d);
            case Optional<?> o -> optional(o);
            case Map<?, ?> m -> nested(m, () -> map(m));
            case Collection<?> c -> nested(c, () -> collection(c));
            case Object[] a -> nested(a, () -> collection(List.of(a)));
            case java.util.regex.Pattern p -> quote(p.pattern());
            default -> nested(v, () -> object(v));
        }
    }

    private void optional(Optional<?> o) {
        if (o.isEmpty()) {
            out.append("Optional.empty");
            return;
        }
        out.append("Optional[");
        write(o.get());
        out.append(']');
    }

    private void nested(Object v, Runnable body) {
        if (path.containsKey(v)) {
            out.append("<cycle>");
            return;
        }
        path.put(v, Boolean.TRUE);
        body.run();
        path.remove(v);
    }

    private void map(Map<?, ?> m) {
        List<String[]> entries = new ArrayList<>();
        m.forEach((k, val) -> entries.add(new String[] {of(k), of(val)}));
        if (unordered(m)) entries.sort(Comparator.comparing((String[] e) -> e[0]));
        out.append('{');
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(entries.get(i)[0]).append('=').append(entries.get(i)[1]);
        }
        out.append('}');
    }

    private void collection(Collection<?> c) {
        List<String> items = new ArrayList<>();
        for (Object x : c) {
            Canonical inner = new Canonical();
            inner.path.putAll(path);
            inner.write(x);
            items.add(inner.out.toString());
        }
        if (unordered(c)) items.sort(Comparator.naturalOrder());
        out.append('[').append(String.join(", ", items)).append(']');
    }

    private static boolean unordered(Object container) {
        if (container instanceof LinkedHashMap<?, ?> || container instanceof LinkedHashSet<?>) return false;
        if (container instanceof HashMap<?, ?> || container instanceof HashSet<?>) return true;
        String name = container.getClass().getName();
        // Map.of, Set.of, Map.copyOf, Set.copyOf: their iteration order changes from one run to the next.
        return name.startsWith("java.util.ImmutableCollections$Map")
                || name.startsWith("java.util.ImmutableCollections$Set");
    }

    private void object(Object v) {
        Class<?> type = v.getClass();
        if (type.isHidden() || type.isSynthetic()) {
            out.append("<function>");
            return;
        }
        if (type.isRecord()) {
            record(v, type);
            return;
        }
        if (!type.getName().startsWith("dev.floor42.")) {
            out.append(type.getName()).append(':');
            quote(String.valueOf(v));
            return;
        }
        out.append(type.getSimpleName()).append('{');
        boolean first = true;
        for (Field f : fields(type)) {
            if (!first) out.append(", ");
            first = false;
            out.append(f.getName()).append('=');
            write(read(f, v));
        }
        out.append('}');
    }

    private void record(Object v, Class<?> type) {
        out.append(type.getSimpleName()).append('(');
        RecordComponent[] components = type.getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            if (i > 0) out.append(", ");
            out.append(components[i].getName()).append('=');
            try {
                var accessor = components[i].getAccessor();
                accessor.setAccessible(true);
                write(accessor.invoke(v));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        out.append(')');
    }

    /** The instance fields of a class and its superclasses, by name: declaration order is not part of behaviour. */
    static List<Field> fields(Class<?> type) {
        List<Field> all = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                // transient: a read-only detail of a view (a closing time), not part of the state the goldens
                // fingerprint
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers()) || f.isSynthetic())
                    continue;
                f.setAccessible(true);
                all.add(f);
            }
        }
        all.sort(Comparator.comparing(Field::getName));
        return all;
    }

    static Object read(Field f, Object owner) {
        try {
            return f.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private void quote(String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        out.append('"');
    }
}
