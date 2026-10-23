package pe.wallet.view;

import jakarta.validation.constraints.*;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import pe.wallet.model.WalletData.Event;
import pe.wallet.model.WalletData.Status;
import pe.wallet.model.WalletData.Step;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApiModels {
    private ApiModels() {}

    public enum VerificationStatus { NOT_STARTED, PENDING, REJECTED, APPROVED }

    public record StartRequest(
            @NotBlank @Pattern(regexp = "PE", message = "La cohorte mock admite solo PE.") String country,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{2,39}", message = "Usa un canal de 3 a 40 caracteres: organic, referral o campaign_demo.") String channel) {}

    public record ProfileRequest(
            @NotBlank @Size(max = 80) String displayName,
            @NotBlank @Email @Size(max = 120)
            @Pattern(regexp = "(?i).+@[^@]+\\.test", message = "Usa un correo ficticio terminado en .test.") String email) {}

    public record ConsentRequest(
            @NotNull @AssertTrue(message = "Debes aceptar los terminos de la demo para continuar.") Boolean accepted) {}

    @Schema(description = "Gasto de adquisicion. El importe es un entero en centimos de PEN; no se aceptan decimales.")
    public record CostRequest(
            @NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "Usa YYYY-MM.") String month,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{2,39}") String channel,
            @Schema(type = SchemaType.INTEGER, format = "int64",
                    description = "Importe en centimos de PEN (1000 = PEN 10.00).", examples = "1000")
            @NotNull @Min(0) @Max(1_000_000_000L) Long amountCents) {}

    public record StepView(Step name, int position, boolean completed, boolean current) {}

    @Schema(description = "Estado de la verificacion. simulated=true indica una verificacion de demostracion, no KYC real.")
    public record VerificationView(VerificationStatus status, boolean simulated, String message) {}

    public record WalletView(UUID walletId, Status status, String country, String channel,
                             List<StepView> steps, Step currentStep, String nextAction,
                             Instant startedAt, Instant completedAt, boolean paymentsAvailable, boolean mock,
                             String mode, VerificationView verification) {}

    public record RegistrationResult(WalletView wallet, boolean created) {}

    @Schema(description = "Disponibilidad del punto de entrada. Es no transaccional: nunca procesa pagos ni transferencias.")
    public record PaymentEntry(UUID walletId, boolean available, String currency, String mode, boolean transactional, String message) {}

    public record EventPage(int total, int offset, int limit, List<Event> events) {}

    @Schema(description = "Metrica con disponibilidad explicita. Si available=false, value es null y reason explica que falta.")
    public record MetricAvailability(boolean available, Long value, String unit, String reason) {}

    @Schema(description = "Resumen mensual. Los importes son enteros en centimos de PEN. MAU y CAC se informan como "
            + "no disponibles hasta que existan definiciones y fuentes aprobadas.")
    public record MonthlyReport(String month, String timezone, String channel, String dataMode,
                                long activatedWallets, Double medianOnboardingSeconds,
                                Long acquisitionSpendCents, boolean acquisitionSpendRecorded,
                                MetricAvailability monthlyActiveUsers, MetricAvailability acquisitionCost,
                                int targetMedianSeconds, int targetMauMinimum, int targetMauMaximum, long targetCacCents,
                                String targetEvaluation, List<String> definitions) {}

    public record ApiError(String code, String message, Map<String, String> fields, Instant timestamp) {}
}