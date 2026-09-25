package io.github.zoyluo.aibot.persist;

import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public record MissionRecord(String missionId, MissionSpec spec, Map<String, String> checkpoint) {
    public static final int RECOVERY_ATTEMPT_LIMIT = 3;
    private static final int MAX_TRACKED_RELEVANT_ITEMS = 256;
    private static final int MAX_RECOVERY_KEY_CHARS = 256;

    public MissionRecord {
        missionId = missionId == null ? "" : missionId;
        checkpoint = checkpoint == null ? Map.of() : Map.copyOf(checkpoint);
    }

    /** Bounded mission facts kept in the existing checkpoint map for schema compatibility. */
    public record RecoveryState(long progressRevision,
                                long snapProgressRevision,
                                int explorationHighWater,
                                Map<String, Integer> itemHighWater,
                                int attemptsUsed,
                                Set<String> attemptedMethods) {
        public RecoveryState {
            itemHighWater = itemHighWater == null ? Map.of() : Map.copyOf(itemHighWater);
            attemptedMethods = attemptedMethods == null ? Set.of() : Set.copyOf(attemptedMethods);
        }

        public static RecoveryState legacy() {
            return new RecoveryState(0, 0, 0, Map.of(), 0, Set.of());
        }
    }

    public static Map<String, String> encodeRecoveryState(RecoveryState state) {
        Map<String, String> values = new TreeMap<>();
        values.put("mission.schema", "1");
        values.put("mission.progress_revision", Long.toString(state.progressRevision()));
        values.put("mission.snap_progress_revision", Long.toString(state.snapProgressRevision()));
        values.put("mission.exploration_high_water", Integer.toString(state.explorationHighWater()));
        values.put("mission.item_count", Integer.toString(state.itemHighWater().size()));
        int itemIndex = 0;
        for (Map.Entry<String, Integer> entry : new TreeMap<>(state.itemHighWater()).entrySet()) {
            values.put("mission.item." + itemIndex + ".id", entry.getKey());
            values.put("mission.item." + itemIndex + ".count", Integer.toString(entry.getValue()));
            itemIndex++;
        }
        values.put("mission.recovery_attempts_used", Integer.toString(state.attemptsUsed()));
        values.put("mission.recovery_attempt_count", Integer.toString(state.attemptedMethods().size()));
        int attemptIndex = 0;
        for (String attempt : new TreeSet<>(state.attemptedMethods())) {
            values.put("mission.recovery_attempt." + attemptIndex++, attempt);
        }
        return Map.copyOf(values);
    }

    public static Optional<RecoveryState> decodeRecoveryState(Map<String, String> checkpoint) {
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        Set<String> present = values.keySet().stream()
                .filter(key -> key != null && key.startsWith("mission."))
                .collect(java.util.stream.Collectors.toSet());
        if (present.isEmpty()) return Optional.of(RecoveryState.legacy());
        try {
            if (!"1".equals(values.get("mission.schema"))) return Optional.empty();
            long progress = nonNegativeLong(values.get("mission.progress_revision"));
            long snapProgress = nonNegativeLong(values.get("mission.snap_progress_revision"));
            int exploration = nonNegativeInt(values.get("mission.exploration_high_water"));
            int itemCount = nonNegativeInt(values.get("mission.item_count"));
            int attemptsUsed = nonNegativeInt(values.get("mission.recovery_attempts_used"));
            int attemptCount = nonNegativeInt(values.get("mission.recovery_attempt_count"));
            if (snapProgress > progress || itemCount > MAX_TRACKED_RELEVANT_ITEMS
                    || attemptCount > RECOVERY_ATTEMPT_LIMIT || attemptsUsed != attemptCount) {
                return Optional.empty();
            }
            Map<String, Integer> items = new TreeMap<>();
            for (int index = 0; index < itemCount; index++) {
                String id = values.get("mission.item." + index + ".id");
                int count = nonNegativeInt(values.get("mission.item." + index + ".count"));
                net.minecraft.util.Identifier identifier = net.minecraft.util.Identifier.of(id);
                if (!identifier.toString().equals(id)
                        || items.putIfAbsent(id, count) != null) return Optional.empty();
            }
            Set<String> attempts = new TreeSet<>();
            for (int index = 0; index < attemptCount; index++) {
                String attempt = values.get("mission.recovery_attempt." + index);
                if (attempt == null || attempt.length() > 1024
                        || !canonicalAttempt(attempt) || !attempts.add(attempt)) return Optional.empty();
            }
            Set<String> expected = new HashSet<>(Set.of(
                    "mission.schema", "mission.progress_revision", "mission.snap_progress_revision",
                    "mission.exploration_high_water", "mission.item_count",
                    "mission.recovery_attempts_used", "mission.recovery_attempt_count"));
            for (int i = 0; i < itemCount; i++) {
                expected.add("mission.item." + i + ".id");
                expected.add("mission.item." + i + ".count");
            }
            for (int i = 0; i < attemptCount; i++) expected.add("mission.recovery_attempt." + i);
            if (!expected.equals(present)) return Optional.empty();
            return Optional.of(new RecoveryState(progress, snapProgress, exploration,
                    items, attemptsUsed, attempts));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public static Optional<RecoveryState> reserveRecoveryAttempt(
            RecoveryState state, String condition, String method) {
        if (state == null || condition == null || condition.isBlank()
                || method == null || method.isBlank()
                || condition.length() > MAX_RECOVERY_KEY_CHARS
                || method.length() > MAX_RECOVERY_KEY_CHARS
                || state.attemptsUsed() >= RECOVERY_ATTEMPT_LIMIT) return Optional.empty();
        String identity = attemptIdentity(condition, method, state.progressRevision());
        if (state.attemptedMethods().contains(identity)) return Optional.empty();
        Set<String> attempts = new TreeSet<>(state.attemptedMethods());
        attempts.add(identity);
        return Optional.of(new RecoveryState(state.progressRevision(), state.snapProgressRevision(),
                state.explorationHighWater(), state.itemHighWater(), state.attemptsUsed() + 1, attempts));
    }

    public static boolean relevantItemAdvanced(int previousHighWater, int observedCount) {
        return observedCount > previousHighWater;
    }

    public static RecoveryState observeRelevantItem(
            RecoveryState state, String itemId, int observedCount) {
        if (state == null || itemId == null || observedCount < 0) return state;
        Integer previous = state.itemHighWater().get(itemId);
        if (previous == null || !relevantItemAdvanced(previous, observedCount)) return state;
        Map<String, Integer> highWater = new TreeMap<>(state.itemHighWater());
        highWater.put(itemId, observedCount);
        long revision = state.progressRevision() == Long.MAX_VALUE
                ? Long.MAX_VALUE : state.progressRevision() + 1;
        return new RecoveryState(revision, state.snapProgressRevision(),
                state.explorationHighWater(), highWater, state.attemptsUsed(),
                state.attemptedMethods());
    }

    private static String attemptIdentity(String condition, String method, long progressRevision) {
        String payload = condition + "\u0000" + method + "\u0000" + progressRevision;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean canonicalAttempt(String value) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int first = plain.indexOf('\0');
            int second = first < 0 ? -1 : plain.indexOf('\0', first + 1);
            if (first <= 0 || second <= first + 1 || second == plain.length() - 1
                    || plain.indexOf('\0', second + 1) >= 0) return false;
            String revision = plain.substring(second + 1);
            long parsed = nonNegativeLong(revision);
            return attemptIdentity(plain.substring(0, first), plain.substring(first + 1, second),
                    parsed).equals(value);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static int nonNegativeInt(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException();
        return Integer.parseInt(value);
    }

    private static long nonNegativeLong(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException();
        return Long.parseLong(value);
    }
}
