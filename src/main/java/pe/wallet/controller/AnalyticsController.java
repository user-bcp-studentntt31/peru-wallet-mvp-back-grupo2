package pe.wallet.controller;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeIn;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import pe.wallet.model.WalletData.Expense;
import pe.wallet.service.AccessControl;
import pe.wallet.service.AnalyticsService;
import pe.wallet.view.ApiModels.*;

import java.util.UUID;

@Path("/api/analytics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Analitica administrativa mock")
@SecurityScheme(securitySchemeName = "analytics-key", type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER, apiKeyName = "X-Analytics-Key")
@SecurityRequirement(name = "analytics-key")
public class AnalyticsController {
    @Inject AnalyticsService service;
    @Inject AccessControl access;

    @GET @Path("/monthly")
    @Operation(summary = "Resumen mensual. Importes en centimos de PEN (enteros). MAU y CAC se devuelven como no disponibles "
            + "mientras no existan definiciones y fuentes aprobadas; las activaciones nunca se presentan como MAU.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Resumen mensual.", content = @Content(schema = @Schema(implementation = MonthlyReport.class))),
            @APIResponse(responseCode = "400", description = "INVALID_MONTH o INVALID_CHANNEL.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "401", description = "ANALYTICS_UNAUTHORIZED.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "503", description = "ANALYTICS_NOT_CONFIGURED.", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    public MonthlyReport monthly(@HeaderParam("X-Analytics-Key") String key,
                                 @QueryParam("month") String month, @QueryParam("channel") String channel) {
        access.requireAnalytics(key);
        return service.monthly(month, channel);
    }

    @POST @Path("/acquisition-costs")
    @Operation(summary = "Registrar gasto de adquisicion como entero en centimos (amountCents). Idempotente por Idempotency-Key.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Gasto registrado o reintento idempotente.", content = @Content(schema = @Schema(implementation = Expense.class))),
            @APIResponse(responseCode = "400", description = "VALIDATION_ERROR: amountCents debe ser un entero entre 0 y 1000000000.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "401", description = "ANALYTICS_UNAUTHORIZED.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT.", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    public Expense cost(@HeaderParam("X-Analytics-Key") String key, @HeaderParam("Idempotency-Key") String requestKey,
                        @Valid @NotNull CostRequest request) {
        access.requireAnalytics(key);
        return service.cost(request, requestKey);
    }

    @GET @Path("/events")
    @Operation(summary = "Eventos minimos del onboarding (id, wallet, tipo, paso, marca de tiempo del servidor y modo). "
            + "No incluyen credenciales, documentos, respuestas de verificacion ni valores de formularios. Solo datos del modo vigente.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Pagina de eventos.", content = @Content(schema = @Schema(implementation = EventPage.class))),
            @APIResponse(responseCode = "400", description = "INVALID_PAGINATION.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "401", description = "ANALYTICS_UNAUTHORIZED.", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    public EventPage events(@HeaderParam("X-Analytics-Key") String key, @QueryParam("walletId") UUID walletId,
                            @QueryParam("offset") @DefaultValue("0") int offset,
                            @QueryParam("limit") @DefaultValue("100") int limit) {
        access.requireAnalytics(key);
        return service.events(walletId, offset, limit);
    }
}