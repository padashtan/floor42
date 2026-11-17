package dev.floor42.server.api.mission;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.api.mission.QuestionViews.QuestionView;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one kind of question a script token answers, on the MCP tool and, for a token bound to Keycloak (ADR-0083), on
 * the REST route: an agent's own free question. Every decision of Floor42's stays the director's.
 */
class AgentsFreeQuestionTest {

    /** A view with every field empty except the ones named, whatever fields the view gains later. */
    private static QuestionView view(Map<String, Object> set) throws Exception {
        RecordComponent[] parts = QuestionView.class.getRecordComponents();
        Object[] args = new Object[parts.length];
        Class<?>[] types = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) {
            types[i] = parts[i].getType();
            Class<?> t = types[i];
            Object value = set.get(parts[i].getName());
            args[i] = value != null
                    ? value
                    : t == boolean.class ? false : t == List.class ? List.of() : t == Map.class ? Map.of() : null;
        }
        Constructor<QuestionView> c = QuestionView.class.getDeclaredConstructor(types);
        return c.newInstance(args);
    }

    @Test
    void anAgentsFreeQuestionIsAnswerable() throws Exception {
        assertThat(QuestionsResource.agentsFreeQuestion(view(Map.of("kind", "OPEN", "askedBy", "chris"))))
                .isTrue();
    }

    @Test
    void everyDecisionOfFloor42sStaysHuman() throws Exception {
        for (String kind : List.of(
                "BUDGET_REACHED",
                "PLAN_LIMIT",
                "STAFFING",
                "REVIEW_ESCALATION",
                "COMMITTEE_ARBITRATION",
                "ACTION_APPROVAL")) {
            assertThat(QuestionsResource.agentsFreeQuestion(view(Map.of("kind", kind, "askedBy", "floor42"))))
                    .as(kind)
                    .isFalse();
            assertThat(QuestionsResource.agentsFreeQuestion(view(Map.of("kind", kind, "askedBy", "chris"))))
                    .as(kind + " by an agent")
                    .isFalse();
        }
    }

    @Test
    void anOpenQuestionOfFloor42sOrWithAnActionIsNotAnAgentsFreeOne() throws Exception {
        assertThat(QuestionsResource.agentsFreeQuestion(view(Map.of("kind", "OPEN", "askedBy", "floor42"))))
                .isFalse();
        assertThat(QuestionsResource.agentsFreeQuestion(
                        view(Map.of("kind", "OPEN", "askedBy", "floor42", "action", "A-1"))))
                .isFalse();
        assertThat(QuestionsResource.agentsFreeQuestion(
                        view(Map.of("kind", "OPEN", "askedBy", "chris", "action", "A-1"))))
                .isFalse();
        assertThat(QuestionsResource.agentsFreeQuestion(view(Map.of("kind", "OPEN"))))
                .isFalse();
    }
}
