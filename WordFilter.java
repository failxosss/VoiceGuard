package cz.voiceguard.filter;

import cz.voiceguard.config.ConfigManager;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks normalized recognized text against the configured blocked-word list.
 * Matching is whole-word and case-insensitive (text is already lowercased by
 * {@link TextNormalizer} when normalization is enabled).
 */
public final class WordFilter {

    private final ConfigManager config;

    public WordFilter(ConfigManager config) {
        this.config = config;
    }

    /**
     * @param normalizedText text that has already been through {@link TextNormalizer}
     * @return the first blocked word found, or empty if none matched
     */
    public Optional<String> findBlockedWord(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return Optional.empty();
        }
        Set<String> blocked = config.getBlockedWords();
        if (blocked.isEmpty()) {
            return Optional.empty();
        }
        String haystackLower = normalizedText.toLowerCase();
        for (String word : blocked) {
            if (word.isBlank()) {
                continue;
            }
            Pattern pattern = Pattern.compile("(?<![\\p{L}0-9])" + Pattern.quote(word) + "(?![\\p{L}0-9])",
                    Pattern.CASE_INSENSITIVE);
            if (pattern.matcher(haystackLower).find()) {
                return Optional.of(word);
            }
        }
        return Optional.empty();
    }
}
