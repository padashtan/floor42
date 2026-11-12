package dev.floor42.domain;

public record QuestionId(String value) implements Identifier {
    public QuestionId {
        Identifier.check(value, "question");
    }

    @Override
    public String toString() {
        return value;
    }
}
