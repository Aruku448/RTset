package com.rtest.client;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Computes the Section additions/removals needed when the RT capture window moves. */
final class SceneWindowDelta {
    private SceneWindowDelta() {
    }

    static Delta between(Collection<Long> currentOrigins, Collection<Long> desiredOrigins) {
        Set<Long> current = new LinkedHashSet<>(currentOrigins);
        Set<Long> desired = new LinkedHashSet<>(desiredOrigins);
        Set<Long> removed = new LinkedHashSet<>(current);
        removed.removeAll(desired);
        Set<Long> added = new LinkedHashSet<>(desired);
        added.removeAll(current);
        return new Delta(Set.copyOf(removed), Set.copyOf(added));
    }

    record Delta(Set<Long> removed, Set<Long> added) {
    }
}
