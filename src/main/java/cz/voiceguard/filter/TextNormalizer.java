package cz.voiceguard.filter;

import cz.voiceguard.config.ConfigManager;

import java.text.Normalizer;
import java.util.regex.Pattern;

public final class TextNormalizer {

    // Matches sequences like "s.l.o.v.o" or "s-l-o-v-o" where single letters
    // are separated by punctuation/dashes/dots - a common filter-dodge trick.
    // Deliberately conservative (requires at least 3 separated letters) to
    // avoid mangling normal short words or punctuation.
    private static final Pattern LETTER_SPACING = Pattern.compile(
            "\\b(\\p{L})([.\\-_])(\\p{L})(?:\\2(\\p{L})){2,}\\b"
    );
    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{Punct}]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private final ConfigManager config;

    public TextNormalizer(ConfigManager config) {
        this.config = config;
    }

    public String normalize(String input) {
        if (input == null) {
            return "";
        }
        if (!config.isNormalizationEnabled()) {
            return input;
        }

        String text = input;

        if (config.isCollapseLetterSpacing()) {
            text = collapseLetterSpacing(text);
        }
        if (config.isLowercase()) {
            text = text.toLowerCase();
        }
        if (config.isRemovePunctuation()) {
            text = PUNCTUATION.matcher(text).replaceAll(" ");
        }
        if (config.isNormalizeSpaces()) {
            text = MULTI_SPACE.matcher(text).replaceAll(" ").trim();
        }
        return text;
    }

    /**
     * Collapses "s.l.o.v.o" / "s-l-o-v-o" style spaced-out words back into "slovo".
     * Requires the SAME separator repeated at least 3 times to avoid touching
     * normal sentences with the occasional dash or dot.
     */
    private String collapseLetterSpacing(String text) {
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        var matcher = LETTER_SPACING.matcher(text);
        while (matcher.find()) {
            result.append(text, lastEnd, matcher.start());
            String separator = matcher.group(2);
            String whole = matcher.group();
            String collapsed = whole.replace(separator, "");
            result.append(collapsed);
            lastEnd = matcher.end();
        }
        result.append(text.substring(lastEnd));
        return result.toString();
    }

    /**
     * Strips diacritics as an optional extra normalization step, useful if you
     * want "hloupy" to also match "hloupý". Not enabled by default because it
     * increases false-positive risk; call explicitly if desired.
     */
    public static String stripDiacritics(String input) {
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "");
    }
}
