package pe.wallet.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import pe.wallet.model.WalletData;
import pe.wallet.model.WalletData.*;
import pe.wallet.repository.JsonWalletRepository;
import pe.wallet.view.ApiModels.*;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class AnalyticsService {
    private static final ZoneId ZONE = ZoneId.of("America/Lima");
    private static final String MAU_PENDING = "No disponible: falta la definicion aprobada de actividad mensual y una fuente "
            + "aprobada de uso o pagos (D7). Las activaciones y los eventos de actividad mock no sustituyen a MAU.";
    private static final String CAC_PENDING = "No disponible: faltan la fuente de gasto, la regla o ventana de atribucion y el "
            + "denominador aprobados (D8). No se afirma que el objetivo de CAC sea verificable.";
    @Inject JsonWalletRepository repository;
    @Inject AccessControl access;
    @Inject RuntimeMode runtime;
    @ConfigProperty(name = "wallet.metrics.mau-source-approved", defaultValue = "false") boolean mauSourceApproved;
    @ConfigProperty(name = "wallet.metrics.cac-source-approved", defaultValue = "false") boolean cacSourceApproved;

    public Expense cost(CostRequest request, String key) {
        String requestKey = access.requestKey(key);
        YearMonth month = parseMonth(request.month());
        long amountCents = request.amountCents();
        return repository.update(state -> {
            Expense existing = state.expenses.get(requestKey);
            if (existing != null) {
                if (!existing.month().equals(month) || !existing.channel().equals(request.channel())
                        || existing.amountCents() != amountCents) {
                    throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "La clave del gasto ya se uso con otros valores.");
                }
                return existing;
            }
            Expense expense = new Expense(UUID.randomUUID(), month, request.channel(), amountCents, Instant.now(), runtime.label());
            state.expenses.put(requestKey, expense);
            return expense;
        });
    }

    public MonthlyReport monthly(String requestedMonth, String channel) {
        YearMonth month = parseMonth(requestedMonth);
        if (channel != null && !channel.matches("[a-z][a-z0-9_-]{2,39}")) {
            throw new ApiException(400, "INVALID_CHANNEL", "Usa el mismo identificador de canal que en el onboarding.");
        }
        String mode = runtime.label();
        return repository.read(state -> {
            // Solo se agregan datos del modo vigente: los datos demo nunca alimentan una metrica productiva.
            List<Wallet> cohort = state.wallets.values().stream()
                    .filter(wallet -> "PE".equals(wallet.country()) && mode.equals(WalletData.modeOf(wallet.mode()))
                            && (channel == null || channel.equals(wallet.channel()))).toList();
            List<Wallet> activated = cohort.stream().filter(wallet -> wallet.completedAt() != null && inMonth(wallet.completedAt(), month)).toList();
            List<Expense> expenses = state.expenses.values().stream().filter(expense -> expense.month().equals(month)
                    && mode.equals(WalletData.modeOf(expense.mode())) && (channel == null || expense.channel().equals(channel))).toList();
            Long spend = expenses.isEmpty() ? null : expenses.stream().mapToLong(Expense::amountCents).sum();
            return new MonthlyReport(month.toString(), ZONE.getId(), channel == null ? "ALL" : channel, mode, activated.size(),
                    median(activated), spend, !expenses.isEmpty(), mau(state, cohort, month), cac(spend, activated.size()),
                    180, 1000, 2000, 1000, "NOT_EVALUATED: cohorte, fecha de lanzamiento y periodo pendientes de aprobacion de Producto (D6). "
                    + "Los objetivos son referencias, no resultados garantizados.",
                    List.of("Propuesta demo, pendiente de aprobacion del PO: no es evidencia de metas alcanzadas.",
                            "activatedWallets: wallets activadas en el mes (America/Lima). No es MAU.",
                            "Duracion: ONBOARDING_STARTED hasta ONBOARDING_COMPLETED, incluye pausas y reanudaciones; el inicio no se reinicia al retomar.",
                            "MAU y CAC se informan como no disponibles mientras no existan las definiciones y fuentes aprobadas (D7, D8).",
                            "Importes en centimos de PEN (enteros). CAC redondea a centimo, mitad hacia arriba.",
                            "No se registra onboarding_exited: una salida explicita no es observable de forma fiable (D5).",
                            "Meses calendario en America/Lima. Los meses 1-3 se evaluan respecto de una fecha de lanzamiento aun no acordada."));
        });
    }

    private MetricAvailability mau(State state, List<Wallet> cohort, YearMonth month) {
        if (!mauSourceApproved) { return new MetricAvailability(false, null, "wallets", MAU_PENDING); }
        Set<UUID> cohortIds = cohort.stream().map(Wallet::id).collect(Collectors.toSet());
        long active = state.events.stream().filter(event -> event.type() == EventType.WALLET_ACTIVITY
                && inMonth(event.occurredAt(), month) && cohortIds.contains(event.walletId())).map(Event::walletId).distinct().count();
        return new MetricAvailability(true, active, "wallets", null);
    }

    private MetricAvailability cac(Long spendCents, int activated) {
        if (!cacSourceApproved) { return new MetricAvailability(false, null, "PEN_CENTS", CAC_PENDING); }
        if (spendCents == null) { return new MetricAvailability(false, null, "PEN_CENTS", "No disponible: no hay gasto registrado para el mes y canal."); }
        if (activated == 0) { return new MetricAvailability(false, null, "PEN_CENTS", "No disponible: no hay wallets activadas en el mes y canal."); }
        // Redondeo entero mitad hacia arriba, sin aritmetica de punto flotante.
        long cents = Math.floorDiv(2 * spendCents + activated, 2L * activated);
        return new MetricAvailability(true, cents, "PEN_CENTS", null);
    }

    public EventPage events(UUID walletId, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) {
            throw new ApiException(400, "INVALID_PAGINATION", "Usa offset >= 0 y limit entre 1 y 200.");
        }
        String mode = runtime.label();
        return repository.read(state -> {
            List<Event> events = state.events.stream().filter(event -> mode.equals(WalletData.modeOf(event.mode()))
                    && (walletId == null || walletId.equals(event.walletId()))).toList();
            return new EventPage(events.size(), offset, limit, events.stream().skip(offset).limit(limit).toList());
        });
    }

    private Double median(List<Wallet> wallets) {
        double[] durations = wallets.stream().mapToDouble(wallet -> Duration.between(wallet.startedAt(), wallet.completedAt()).toMillis() / 1000.0).sorted().toArray();
        if (durations.length == 0) { return null; }
        int middle = durations.length / 2;
        return durations.length % 2 == 0 ? (durations[middle - 1] + durations[middle]) / 2.0 : durations[middle];
    }

    private boolean inMonth(Instant timestamp, YearMonth month) {
        return YearMonth.from(timestamp.atZone(ZONE)).equals(month);
    }

    private YearMonth parseMonth(String value) {
        try {
            if (value == null || !value.matches("\\d{4}-(0[1-9]|1[0-2])")) { throw new DateTimeParseException("month", "", 0); }
            return YearMonth.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ApiException(400, "INVALID_MONTH", "Indica month con formato YYYY-MM, por ejemplo 2026-10.");
        }
    }
}