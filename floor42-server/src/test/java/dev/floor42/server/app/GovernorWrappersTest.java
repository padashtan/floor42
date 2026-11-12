package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.runtime.AgentRuntime.Governor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A governor that wraps another one passes every call through, or decides it on purpose: a default method left
 * un-overridden answers for the session without asking the wrapped governor. MeetingHold wrapped every real session
 * and left {@code authorized} to the default, which refused every broker call (pilot, M-17).
 */
class GovernorWrappersTest {

    static final List<String> WRAPPERS = List.of(
            "dev.floor42.server.app.MeetingHold",
            "dev.floor42.server.app.SampleWrites",
            "dev.floor42.server.runtime.SessionTally");

    @Test
    void everyWrapperOverridesEveryDefaultMethodOfTheGovernor() throws Exception {
        List<Method> defaults = Arrays.stream(Governor.class.getMethods())
                .filter(Method::isDefault)
                .toList();
        assertThat(defaults).isNotEmpty();
        for (String name : WRAPPERS) {
            Class<?> wrapper = Class.forName(name);
            assertThat(Arrays.stream(wrapper.getDeclaredFields())
                            .anyMatch(f -> Governor.class.isAssignableFrom(f.getType())
                                    && !Modifier.isStatic(f.getModifiers())))
                    .as("%s wraps a governor", name)
                    .isTrue();
            for (Method m : defaults) {
                Method found = wrapper.getMethod(m.getName(), m.getParameterTypes());
                assertThat(found.getDeclaringClass())
                        .as("%s.%s passes the call to the governor it wraps", wrapper.getSimpleName(), m.getName())
                        .isEqualTo(wrapper);
            }
        }
    }
}
