package dev.redish.store;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public enum EvictionPolicy {
    NOEVICTION,
    ALLKEYS_RANDOM;

    public static EvictionPolicy fromString(String s) {
        return switch (s.toLowerCase()) {
            case "allkeys-random" -> ALLKEYS_RANDOM;
            default -> NOEVICTION;
        };
    }

    public String configName() {
        return switch (this) {
            case NOEVICTION -> "noeviction";
            case ALLKEYS_RANDOM -> "allkeys-random";
        };
    }

    public String selectVictim(Set<String> keys) {
        return switch (this) {
            case NOEVICTION -> null;
            case ALLKEYS_RANDOM -> {
                if (keys.isEmpty()) yield null;
                int idx = ThreadLocalRandom.current().nextInt(keys.size());
                int i = 0;
                for (String k : keys) { if (i++ == idx) yield k; }
                yield null;
            }
        };
    }
}
