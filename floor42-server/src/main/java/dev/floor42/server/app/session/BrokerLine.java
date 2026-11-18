package dev.floor42.server.app.session;

import dev.floor42.server.runtime.ToolCatalog;
import dev.floor42.server.security.Jti;
import dev.floor42.server.security.SecretMasker;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks the result of a tool call, sparing the one identifier Floor42's own broker line carries. That line reads
 * {@code Resource <name> <METHOD> <path> -> <status> (exchanged token <jti> for <audience>)} or {@code MCP <server>
 * <tool> -> ok (exchanged token <jti> for <audience>)}: the {@code token <jti>} would read as a secret, yet a jti is an
 * identifier, and {@code IdentityAudit} and the proof dossier read it back from the journal. Only the gateway's own
 * tools get the exception, and only a jti of the shape the identity provider issues (a UUID, with an optional type
 * prefix such as {@code onrtac:}): any other value in that place is masked like the rest.
 */
public final class BrokerLine {

    private static final Pattern LINE = Pattern.compile(
            "^(?<pre>(?:Resource \\S+ \\S+ \\S+ -> \\d+|MCP \\S+ \\S+ -> " + "(?:ok|error)) \\(exchanged token )(?<jti>"
                    + Jti.SHAPE.pattern() + ")(?<post> for [^\\s,]+(?:, fga:[^\\s)]+)?\\))$");

    private BrokerLine() {}

    /** The text with every secret masked, but the jti of a broker line the gateway's own {@code tool} returned. */
    public static String mask(String output, String tool) {
        if (output == null
                || tool == null
                || !(tool.equals(ToolCatalog.FLOOR42_TOOL_PREFIX + "call_resource")
                        || tool.equals(ToolCatalog.FLOOR42_TOOL_PREFIX + "call_mcp")))
            return SecretMasker.maskText(output);
        int eol = output.indexOf('\n');
        String first = eol < 0 ? output : output.substring(0, eol);
        Matcher m = LINE.matcher(first);
        if (!m.matches()) return SecretMasker.maskText(output);
        String rest = eol < 0 ? "" : SecretMasker.maskText(output.substring(eol));
        return SecretMasker.maskText(m.group("pre")) + m.group("jti") + SecretMasker.maskText(m.group("post")) + rest;
    }
}
