package com.shijie.resumehelper.interview;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Hot100SelectionTest {
    @Test
    void injectedRandomSelectionStaysAtRequestedDifficultyAndExcludesUsedProblems() {
        AtomicInteger cursor = new AtomicInteger();
        Hot100Catalog catalog = new Hot100Catalog(bound -> Math.floorMod(cursor.getAndIncrement(), bound));
        for (String difficulty : Set.of("EASY", "MEDIUM", "HARD")) {
            for (int i = 0; i < 20; i++) {
                Hot100Catalog.Problem selected = catalog.select(difficulty, Set.of());
                assertEquals(difficulty, selected.difficulty());
            }
            Set<String> excluded = Hot100Catalog.problems().stream().filter(p -> p.difficulty().equals(difficulty))
                    .limit(1).map(Hot100Catalog.Problem::id).collect(java.util.stream.Collectors.toSet());
            assertFalse(excluded.contains(catalog.select(difficulty, excluded).id()));
        }
    }

    @Test
    void rejectsInvalidDifficultyAndReportsAnExhaustedPool() {
        Hot100Catalog catalog = new Hot100Catalog(bound -> 0);
        assertThrows(IllegalArgumentException.class, () -> catalog.select("BEGINNER", Set.of()));
        Set<String> allEasy = Hot100Catalog.problems().stream().filter(p -> p.difficulty().equals("EASY"))
                .map(Hot100Catalog.Problem::id).collect(java.util.stream.Collectors.toSet());
        assertThrows(IllegalStateException.class, () -> catalog.select("EASY", allEasy));
        assertDoesNotThrow(() -> catalog.select("EASY", allEasy, true));
    }
}
