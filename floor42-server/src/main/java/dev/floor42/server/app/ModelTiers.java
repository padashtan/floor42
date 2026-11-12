package dev.floor42.server.app;

import java.util.Locale;

/**
 * How dear a model is, relative to the others (ADR-0062): the family in its name, haiku under sonnet under opus. Only
 * the order is used, to give a light-tier node to a cheaper agent when one is free; a model of an unknown family ranks
 * as sonnet, so it is never taken for a cheaper one by mistake.
 */
final class ModelTiers {

    private ModelTiers() {}

    static int rank(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (m.contains("haiku")) return 1;
        if (m.contains("opus")) return 3;
        return 2;
    }
}
