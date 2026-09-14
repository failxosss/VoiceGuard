package cz.voiceguard.db;

import java.util.UUID;

public record ViolationRecord(
        long id,
        UUID playerId,
        String playerName,
        long timestamp,
        String recognizedText,
        String detectedWord,
        long punishmentDurationMillis
) {
}
