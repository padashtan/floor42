package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Optional;

/** IAM mode off, for unit tests that build a bean by hand (the constructor is package-private). */
public final class IamSettingsOff {
    private IamSettingsOff() {}

    public static IamSettings in(Path dir) {
        return new IamSettings(dir, Optional.of(false), Optional.empty(), Optional.empty(), new ObjectMapper());
    }
}
