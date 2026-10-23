package pe.wallet.controller;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import pe.wallet.model.WalletData.ActivityReceipt;
import pe.wallet.service.WalletService;
import pe.wallet.view.ApiModels.*;

import java.net.URI;
import java.util.UUID;

@Path("/api/wallets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Wallet onboarding mock", description = "Contrato REST de onboarding, verificacion y acceso al punto de entrada. "
        + "Autenticacion: Authorization Bearer con el token de posesion de la wallet (43 a 128 caracteres). "
        + "Una wallet ajena o inexistente responde igual (404 WALLET_NOT_FOUND) para no revelar su existencia. "
        + "Todos los errores usan el esquema ApiError.")
@SecurityScheme(securitySchemeName = "wallet-token", type = SecuritySchemeType.HTTP, scheme = "bearer")
@SecurityRequirement(name = "wallet-token")
@APIResponse(responseCode = "401", description = "WALLET_TOKEN_REQUIRED: falta o es invalido el token Bearer.",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
public class WalletController {
    @Inject WalletService service;

    @POST
    @Operation(summary = "Iniciar onboarding PE. El cliente genera un token aleatorio y conserva la clave de idempotencia.")
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Onboarding creado.", content = @Content(schema = @Schema(implementation = WalletView.class))),
            @APIResponse(responseCode = "200", description = "Reintento idempotente: devuelve la misma wallet.", content = @Content(schema = @Schema(implementation = WalletView.class))),
            @APIResponse(responseCode = "400", description = "VALIDATION_ERROR o IDEMPOTENCY_KEY_REQUIRED.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT o TOKEN_ALREADY_USED.", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    public Response start(@HeaderParam("Authorization") String authorization,
                          @HeaderParam("Idempotency-Key") String key, @Valid @NotNull StartRequest request) {
        RegistrationResult result = service.start(request, authorization, key);
        return Response.status(result.created() ? 201 : 200)
                .location(URI.create("/api/wallets/" + result.wallet().walletId() + "/onboarding"))
                .entity(result.wallet()).build();
    }

    @GET @Path("/{walletId}/onboarding")
    @Operation(summary = "Consultar pasos, progreso y siguiente accion; sirve tambien para reanudar.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Progreso y estado de verificacion.", content = @Content(schema = @Schema(implementation = WalletView.class))),
            @APIResponse(responseCode = "404", description = "WALLET_NOT_FOUND: inexistente o de otra persona.", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    public WalletView progress(@PathParam("walletId") UUID id, @HeaderParam("Authorization") String authorization) {
        return service.progress(id, authorization);
    }

    @PUT @Path("/{walletId}/onboarding/profile")
    @Operation(summary = "Guardar el perfil. Solo avanza si todos los campos cumplen las reglas; si no, no cambia el estado.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Paso PROFILE completado.", content = @Content(schema = @Schema(implementation = WalletView.class))),
            @APIResponse(responseCode = "400", description = "VALIDATION_ERROR con el detalle por campo, sin eco de los valores enviados.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "404", description = "WALLET_NOT_FOUND.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "409", description = "STEP_ALREADY_COMPLETED o PROFILE_CONFLICT.", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    public WalletView profile(@PathParam("walletId") UUID id, @HeaderParam("Authorization") String authorization,
                              @Valid @NotNull ProfileRequest request) {
        return service.profile(id, authorization, request);
    }

    @POST @Path("/{walletId}/onboarding/verification")
    @Consumes(MediaType.WILDCARD)
    @Operation(summary = "Verificacion KYC simulada, nunca un alta financiera real. Solo en modo DEMO; el resultado lo decide el servidor.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "APPROVED avanza a CONSENT; PENDING deja la wallet inactiva y reintentable.", content = @Content(schema = @Schema(implementation = WalletView.class))),
            @APIResponse(responseCode = "404", description = "WALLET_NOT_FOUND.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "409", description = "STEP_OUT_OF_ORDER.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "422", description = "MOCK_VERIFICATION_REJECTED: el estado REJECTED queda visible en GET onboarding y la wallet no se activa.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "503", description = "KYC_NOT_CONFIGURED: sin proveedor aprobado (p. ej. modo PRODUCTION) o INVALID_KYC_CONFIGURATION.", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    public WalletView verify(@PathParam("walletId") UUID id, @HeaderParam("Authorization") String authorization) {
        return service.verify(id, authorization);
    }

    @PUT @Path("/{walletId}/onboarding/consent")
    @Operation(summary = "Aceptar terminos y activar la wallet de forma atomica e idempotente.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Wallet ACTIVE.", content = @Content(schema = @Schema(implementation = WalletView.class))),
            @APIResponse(responseCode = "400", description = "VALIDATION_ERROR si accepted no es true.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "404", description = "WALLET_NOT_FOUND.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "409", description = "STEP_OUT_OF_ORDER: faltan pasos o verificacion.", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    public WalletView consent(@PathParam("walletId") UUID id, @HeaderParam("Authorization") String authorization,
                              @Valid @NotNull ConsentRequest request) {
        return service.consent(id, authorization);
    }

    @GET @Path("/{walletId}/payments/entry")
    @Operation(summary = "Disponibilidad del punto de entrada a pagos. No transaccional: no procesa pagos ni transferencias.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Disponible solo con la wallet ACTIVE y su propietario.", content = @Content(schema = @Schema(implementation = PaymentEntry.class))),
            @APIResponse(responseCode = "404", description = "WALLET_NOT_FOUND.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "409", description = "WALLET_NOT_ACTIVE: no se presenta como habilitado.", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    public PaymentEntry entry(@PathParam("walletId") UUID id, @HeaderParam("Authorization") String authorization) {
        return service.paymentEntry(id, authorization);
    }

    @POST @Path("/{walletId}/activity")
    @Consumes(MediaType.WILDCARD)
    @Operation(summary = "Registrar PAYMENT_ENTRY_OPENED mock una vez por Idempotency-Key para medir actividad, no pagos reales.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Recibo del evento, igual en reintentos.", content = @Content(schema = @Schema(implementation = ActivityReceipt.class))),
            @APIResponse(responseCode = "404", description = "WALLET_NOT_FOUND.", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "409", description = "WALLET_NOT_ACTIVE.", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    public ActivityReceipt activity(@PathParam("walletId") UUID id, @HeaderParam("Authorization") String authorization,
                                    @HeaderParam("Idempotency-Key") String key) {
        return service.activity(id, authorization, key);
    }
}