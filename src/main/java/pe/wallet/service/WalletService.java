package pe.wallet.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import pe.wallet.model.WalletData;
import pe.wallet.model.WalletData.*;
import pe.wallet.repository.JsonWalletRepository;
import pe.wallet.view.ApiModels.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

@ApplicationScoped
public class WalletService {
    @Inject JsonWalletRepository repository;
    @Inject AccessControl access;
    @Inject RuntimeMode runtime;
    @ConfigProperty(name = "wallet.mock-kyc.enabled") boolean mockKycEnabled;
    @ConfigProperty(name = "wallet.mock-kyc.result") String mockKycResult;

    public RegistrationResult start(StartRequest request, String authorization, String idempotencyKey) {
        String tokenHash = access.tokenHash(authorization);
        String key = access.requestKey(idempotencyKey);
        return repository.update(state -> {
            UUID existingId = state.registrations.get(key);
            if (existingId != null) {
                Wallet existing = state.wallets.get(existingId);
                if (existing == null || !AccessControl.same(existing.accessTokenHash(), tokenHash)
                        || !existing.country().equals(request.country()) || !existing.channel().equals(request.channel())) {
                    throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "La clave ya se uso con otros datos. Reutiliza la solicitud original.");
                }
                return new RegistrationResult(view(existing), false);
            }
            boolean tokenUsed = state.wallets.values().stream().anyMatch(wallet -> AccessControl.same(wallet.accessTokenHash(), tokenHash));
            if (tokenUsed) {
                throw new ApiException(409, "TOKEN_ALREADY_USED", "Ese token ya pertenece a una wallet. Consulta su progreso o reutiliza la clave original.");
            }
            Instant now = Instant.now();
            Wallet wallet = new Wallet(UUID.randomUUID(), tokenHash, request.country(), request.channel(), null, null,
                    Status.ONBOARDING, List.of(), now, null, runtime.label());
            state.wallets.put(wallet.id(), wallet);
            state.registrations.put(key, wallet.id());
            event(state, wallet, EventType.ONBOARDING_STARTED, null, now);
            return new RegistrationResult(view(wallet), true);
        });
    }

    public WalletView progress(UUID id, String authorization) {
        String tokenHash = access.tokenHash(authorization);
        return repository.read(state -> view(requireWallet(state, id, tokenHash)));
    }

    public WalletView profile(UUID id, String authorization, ProfileRequest request) {
        String tokenHash = access.tokenHash(authorization);
        String name = request.displayName().strip();
        String email = request.email().strip().toLowerCase(java.util.Locale.ROOT);
        if (name.isEmpty()) { throw new ApiException(400, "INVALID_PROFILE", "Ingresa un nombre ficticio no vacio."); }
        return repository.update(state -> {
            Wallet wallet = requireWallet(state, id, tokenHash);
            if (wallet.completedSteps().contains(Step.PROFILE)) {
                if (!Objects.equals(wallet.displayName(), name) || !Objects.equals(wallet.email(), email)) {
                    throw new ApiException(409, "STEP_ALREADY_COMPLETED", "El perfil ya fue confirmado. No se puede cambiar en este flujo.");
                }
                return view(wallet);
            }
            requireStep(wallet, Step.PROFILE);
            boolean duplicateEmail = state.wallets.values().stream().anyMatch(other -> !other.id().equals(id) && email.equals(other.email()));
            if (duplicateEmail) { throw new ApiException(409, "PROFILE_CONFLICT", "Ese correo de prueba ya esta registrado. Usa otro correo ficticio."); }
            Wallet updated = wallet.with(name, email, Status.ONBOARDING, List.of(Step.PROFILE), null);
            state.wallets.put(id, updated);
            event(state, updated, EventType.STEP_COMPLETED, Step.PROFILE, Instant.now());
            return view(updated);
        });
    }

    public WalletView verify(UUID id, String authorization) {
        String tokenHash = access.tokenHash(authorization);
        WalletView result = repository.update(state -> {
            Wallet wallet = requireWallet(state, id, tokenHash);
            if (wallet.completedSteps().contains(Step.VERIFICATION)) { return view(wallet); }
            requireStep(wallet, Step.VERIFICATION);
            // Una verificacion simulada solo se acepta en modo DEMO; sin proveedor real aprobado, produccion nunca activa.
            if (!runtime.isDemo() || !mockKycEnabled) {
                throw new ApiException(503, "KYC_NOT_CONFIGURED", "Solo existe un proveedor KYC simulado. La activacion real no esta habilitada.");
            }
            Wallet updated;
            switch (mockKycResult == null ? "" : mockKycResult) {
                case "REJECTED" -> updated = wallet.with(Status.VERIFICATION_REJECTED, wallet.completedSteps(), null);
                case "PENDING" -> updated = wallet.with(Status.PENDING_VERIFICATION, wallet.completedSteps(), null);
                case "APPROVED" -> {
                    updated = wallet.with(Status.ONBOARDING, List.of(Step.PROFILE, Step.VERIFICATION), null);
                    event(state, updated, EventType.STEP_COMPLETED, Step.VERIFICATION, Instant.now());
                }
                default -> throw new ApiException(503, "INVALID_KYC_CONFIGURATION", "El resultado KYC mock debe ser APPROVED, REJECTED o PENDING.");
            }
            state.wallets.put(id, updated);
            return view(updated);
        });
        if (result.status() == Status.VERIFICATION_REJECTED) {
            throw new ApiException(422, "MOCK_VERIFICATION_REJECTED", "La verificacion simulada fue rechazada. No se activo la wallet.");
        }
        return result;
    }

    public WalletView consent(UUID id, String authorization) {
        String tokenHash = access.tokenHash(authorization);
        return repository.update(state -> {
            Wallet wallet = requireWallet(state, id, tokenHash);
            if (wallet.status() == Status.ACTIVE) { return view(wallet); }
            requireStep(wallet, Step.CONSENT);
            Instant now = Instant.now();
            Wallet updated = wallet.with(Status.ACTIVE, List.of(Step.values()), now);
            state.wallets.put(id, updated);
            event(state, updated, EventType.STEP_COMPLETED, Step.CONSENT, now);
            event(state, updated, EventType.ONBOARDING_COMPLETED, null, now);
            return view(updated);
        });
    }

    public PaymentEntry paymentEntry(UUID id, String authorization) {
        String tokenHash = access.tokenHash(authorization);
        return repository.read(state -> {
            Wallet wallet = requireWallet(state, id, tokenHash);
            requireActive(wallet);
            return new PaymentEntry(id, true, "PEN", "MOCK", false,
                    "Punto de entrada disponible. Esta iniciativa no procesa pagos ni transferencias.");
        });
    }

    public ActivityReceipt activity(UUID id, String authorization, String idempotencyKey) {
        String tokenHash = access.tokenHash(authorization);
        String key = id + ":" + access.requestKey(idempotencyKey);
        return repository.update(state -> {
            Wallet wallet = requireWallet(state, id, tokenHash);
            requireActive(wallet);
            ActivityReceipt existing = state.activities.get(key);
            if (existing != null) { return existing; }
            Event activity = event(state, wallet, EventType.WALLET_ACTIVITY, null, Instant.now());
            ActivityReceipt receipt = new ActivityReceipt(activity.id(), activity.occurredAt());
            state.activities.put(key, receipt);
            return receipt;
        });
    }

    private Wallet requireWallet(State state, UUID id, String tokenHash) {
        Wallet wallet = state.wallets.get(id);
        // Misma respuesta para una wallet inexistente y una ajena: no se revela si el recurso existe.
        if (wallet == null || !AccessControl.same(wallet.accessTokenHash(), tokenHash)) {
            throw new ApiException(404, "WALLET_NOT_FOUND", "No se encontro la wallet solicitada para este token.");
        }
        return wallet;
    }

    private void requireActive(Wallet wallet) {
        if (wallet.status() != Status.ACTIVE) { throw new ApiException(409, "WALLET_NOT_ACTIVE", "Completa perfil, verificacion y consentimiento antes de acceder a pagos."); }
    }

    private void requireStep(Wallet wallet, Step expected) {
        if (currentStep(wallet) != expected) { throw new ApiException(409, "STEP_OUT_OF_ORDER", "Completa primero el paso " + currentStep(wallet) + "."); }
    }

    private Step currentStep(Wallet wallet) {
        return Arrays.stream(Step.values()).filter(step -> !wallet.completedSteps().contains(step)).findFirst().orElse(null);
    }

    private WalletView view(Wallet wallet) {
        Step current = currentStep(wallet);
        boolean demo = !"PRODUCTION".equals(WalletData.modeOf(wallet.mode()));
        String nextAction = current == null ? "Abre el punto de entrada de pagos mock."
                : switch (current) {
                    case PROFILE -> "Completa tu nombre ficticio y correo .test.";
                    case VERIFICATION -> switch (wallet.status()) {
                        case PENDING_VERIFICATION -> "Verificacion mock pendiente. Reintenta cuando el proveedor simulado resuelva.";
                        case VERIFICATION_REJECTED -> "La verificacion simulada no fue aprobada. Puedes solicitarla de nuevo; la wallet sigue inactiva.";
                        default -> "Solicita la verificacion simulada del perfil.";
                    };
                    case CONSENT -> "Acepta los terminos de la demo para activar la wallet mock.";
                };
        List<StepView> steps = IntStream.range(0, Step.values().length).mapToObj(position -> {
            Step step = Step.values()[position];
            return new StepView(step, position + 1, wallet.completedSteps().contains(step), step == current);
        }).toList();
        return new WalletView(wallet.id(), wallet.status(), wallet.country(), wallet.channel(), steps, current, nextAction,
                wallet.startedAt(), wallet.completedAt(), wallet.status() == Status.ACTIVE, demo,
                WalletData.modeOf(wallet.mode()), verification(wallet, demo));
    }

    private VerificationView verification(Wallet wallet, boolean demo) {
        String simulated = demo ? "Verificacion simulada de demostracion; no es KYC real. " : "";
        if (wallet.completedSteps().contains(Step.VERIFICATION)) {
            return new VerificationView(VerificationStatus.APPROVED, demo, simulated + "Aprobada.");
        }
        return switch (wallet.status()) {
            case PENDING_VERIFICATION -> new VerificationView(VerificationStatus.PENDING, demo, simulated + "Pendiente: la wallet aun no esta activa.");
            case VERIFICATION_REJECTED -> new VerificationView(VerificationStatus.REJECTED, demo, simulated + "No aprobada: la wallet no esta activa.");
            default -> new VerificationView(VerificationStatus.NOT_STARTED, demo, simulated + "Aun no solicitada.");
        };
    }

    private Event event(State state, Wallet wallet, EventType type, Step step, Instant now) {
        Event event = new Event(UUID.randomUUID(), wallet.id(), type, step, now, WalletData.modeOf(wallet.mode()));
        state.events.add(event);
        return event;
    }
}