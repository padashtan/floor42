package dev.floor42.domain;

import static dev.floor42.domain.Rules.requireText;

/**
 * The folder of a project, as a command names it. Only at command input: entities and events keep the plain string, so
 * a journal written before this type existed reads the same.
 */
record ProjectPath(String value) {

    ProjectPath {
        requireText(value, "path");
    }
}
