package xyz.nim.modDetectorPlugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TemporaryExceptionsTest {
    @TempDir Path directory;
    final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    @Test void exceptionsAreScopedPersistentAndExpireAtTheBoundary() throws Exception {
        Path file = directory.resolve("exceptions.yml");
        UUID player = UUID.randomUUID();
        var clock = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.instant()).thenReturn(now);
        var exceptions = new TemporaryExceptions(file, clock);
        exceptions.grant(player, "JADE", 1);
        assertTrue(exceptions.allows(player, "jade"));
        assertFalse(exceptions.allows(UUID.randomUUID(), "jade"));
        assertFalse(exceptions.allows(player, "servux"));
        assertFalse(exceptions.allows(player, null));
        assertTrue(new TemporaryExceptions(file, clock).allows(player, "jade"));
        org.mockito.Mockito.when(clock.instant()).thenReturn(now.plusSeconds(60));
        assertFalse(exceptions.allows(player, "jade"));
        assertEquals(Set.of(player), exceptions.expire());
        assertTrue(exceptions.expire().isEmpty());
        assertTrue(new TemporaryExceptions(file, clock).list().isEmpty());
    }
    @Test void removalPersistsAndFailedWritesDoNotGrantExceptions() throws Exception {
        Path file = directory.resolve("exceptions.yml");
        UUID player = UUID.randomUUID();
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        var exceptions = new TemporaryExceptions(file, clock);
        exceptions.grant(player, "jade", 5);
        exceptions.remove(player, "jade");
        assertFalse(new TemporaryExceptions(file, clock).allows(player, "jade"));
        Files.createDirectory(directory.resolve("exceptions.yml.tmp"));
        assertThrows(java.io.IOException.class, () -> exceptions.grant(player, "jade", 5));
        assertFalse(exceptions.allows(player, "jade"));
        assertThrows(IllegalArgumentException.class, () -> exceptions.grant(player, "jade", 0));
    }
    @Test void malformedFilesAreNotSilentlyOverwritten() throws Exception {
        Path file = directory.resolve("exceptions.yml");
        Files.writeString(file, "invalid: [yaml");
        assertThrows(java.io.IOException.class, () -> new TemporaryExceptions(file, Clock.systemUTC()));
    }
}
