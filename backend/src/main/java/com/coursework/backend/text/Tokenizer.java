package com.coursework.backend.text;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class Tokenizer {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*(?:'[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*)*");

    /** NFC, locale-independent lowercase, Unicode letters/digits and internal apostrophes. */
    public List<String> tokenize(String text) {
        String normalized = Normalizer.normalize(Objects.requireNonNull(text, "text"), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).replace('’', '\'').replace('ʼ', '\'');
        return TOKEN.matcher(normalized).results().map(match -> match.group()).toList();
    }
}
