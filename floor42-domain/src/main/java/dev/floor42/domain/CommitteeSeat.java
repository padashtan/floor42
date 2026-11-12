package dev.floor42.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A committee seat the director defined, beside Floor42's built-in ones: a specialist with their own lens, who only reads
 * and gives an opinion, like every committee member.
 *
 * @param key how the director picks the seat for a committee; also the agent's id
 * @param lens what the member judges by, and on what evidence: their instructions
 * @param panels the committees the seat sits on: {@code architecture}, {@code idea}
 */
public record CommitteeSeat(
        String key, String name, String title, String model, String effort, String lens, List<String> panels) {
    public CommitteeSeat {
        panels = panels == null ? null : Collections.unmodifiableList(new ArrayList<>(panels));
    }

    public AgentId agent() {
        return new AgentId(key);
    }
}
