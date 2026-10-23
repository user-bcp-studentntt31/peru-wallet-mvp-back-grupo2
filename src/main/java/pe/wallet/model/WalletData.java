package pe.wallet.model;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WalletData {
    private WalletData() {}

    public static final String DEMO = "DEMO";
    public static final String PRODUCTION = "PRODUCTION";

    public enum Step { PROFILE, VERIFICATION, CONSENT }
    public enum Status { ONBOARDING, PENDING_VERIFICATION, VERIFICATION_REJECTED, ACTIVE }
    /** No existe un evento de salida: una salida explicita no es observable de forma fiable (D5 pendiente). */
    public enum EventType { ONBOARDING_STARTED, STEP_COMPLETED, ONBOARDING_COMPLETED, WALLET_ACTIVITY }

    /** {@code mode} es DEMO o PRODUCTION; un valor nulo en datos antiguos se interpreta como DEMO. */
    public record Wallet(UUID id, String accessTokenHash, String country, String channel,
                         String displayName, String email, Status status, List<Step> completedSteps,
                         Instant startedAt, Instant completedAt, String mode) {
        public Wallet(UUID id, String accessTokenHash, String country, String channel,
                      String displayName, String email, Status status, List<Step> completedSteps,
                      Instant startedAt, Instant completedAt) {
            this(id, accessTokenHash, country, channel, displayName, email, status, completedSteps, startedAt, completedAt, null);
        }

        public Wallet with(String displayName, String email, Status status, List<Step> completedSteps, Instant completedAt) {
            return new Wallet(id, accessTokenHash, country, channel, displayName, email, status, completedSteps, startedAt, completedAt, mode);
        }

        public Wallet with(Status status, List<Step> completedSteps, Instant completedAt) {
            return with(displayName, email, status, completedSteps, completedAt);
        }
    }

    public record Event(UUID id, UUID walletId, EventType type, Step step, Instant occurredAt, String mode) {
        public Event(UUID id, UUID walletId, EventType type, Step step, Instant occurredAt) {
            this(id, walletId, type, step, occurredAt, null);
        }
    }

    /** Importe de adquisicion en centimos de PEN, nunca como decimal. */
    public record Expense(UUID id, YearMonth month, String channel, long amountCents, Instant recordedAt, String mode) {
        public Expense(UUID id, YearMonth month, String channel, long amountCents, Instant recordedAt) {
            this(id, month, channel, amountCents, recordedAt, null);
        }
    }

    public record ActivityReceipt(UUID eventId, Instant occurredAt) {}

    public static String modeOf(String mode) { return mode == null ? DEMO : mode; }

    public static final class State {
        public int schemaVersion = 2;
        public Map<UUID, Wallet> wallets = new LinkedHashMap<>();
        public Map<String, UUID> registrations = new LinkedHashMap<>();
        public List<Event> events = new ArrayList<>();
        public Map<String, Expense> expenses = new LinkedHashMap<>();
        public Map<String, ActivityReceipt> activities = new LinkedHashMap<>();
    }
}