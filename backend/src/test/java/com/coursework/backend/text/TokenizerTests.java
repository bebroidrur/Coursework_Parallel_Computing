package com.coursework.backend.text;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class TokenizerTests {
    private final Tokenizer tokenizer = new Tokenizer();
    @Test void handlesUnicodeCasePunctuationAndNumbers() {
        assertEquals(List.of("java", "пошук", "2026", "паралельні", "обчислення"), tokenizer.tokenize("JAVA, ПОШУК! 2026; паралельні-обчислення"));
    }
    @Test void normalizesApostrophesAndComposedCharacters() {
        assertEquals(List.of("пам'ять", "пам'ять", "пам'ять", "café"), tokenizer.tokenize("Пам’ять памʼять пам'ять cafe\u0301"));
    }
    @Test void preservesRepeatedTerms() { assertEquals(List.of("java", "java"), tokenizer.tokenize("Java JAVA")); }
    @Test void handlesEmptyAndPunctuationOnly() {
        assertTrue(tokenizer.tokenize("").isEmpty());
        assertTrue(tokenizer.tokenize(" ... — ! ").isEmpty());
    }
    @Test void rejectsNull() { assertThrows(NullPointerException.class, () -> tokenizer.tokenize(null)); }
}
