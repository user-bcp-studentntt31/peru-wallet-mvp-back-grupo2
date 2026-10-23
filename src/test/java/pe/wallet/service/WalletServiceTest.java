package pe.wallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.wallet.model.WalletData.EventType;
import pe.wallet.model.WalletData.Status;
import pe.wallet.model.WalletData.Step;
import pe.wallet.repository.JsonWalletRepository;
import pe.wallet.view.ApiModels.ProfileRequest;
import pe.wallet.view.ApiModels.StartRequest;
import pe.wallet.view.ApiModels.VerificationStatus;
import pe.wallet.view.ApiModels.WalletView;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WalletServiceTest {
    @TempDir Path directory;
    private WalletService service;
    private String authorization;
    private UUID id;

    @BeforeEach
    void prepare() {
        service = new WalletService();
        service.repository = new JsonWalletRepository(directory.resolve("wallets.json"), new ObjectMapper().findAndRegisterModules());
        service.access = new AccessControl();
        service.runtime = new RuntimeMode("DEMO");
        service.mockKycEnabled = true;
        authorization = "Bearer " + "a".repeat(64);
        id = service.start(new StartRequest("PE", "organic"), authorization, "request-12345678").wallet().walletId();
        service.profile(id, authorization, new ProfileRequest("Demo", "demo@wallet.test"));
    }

    @Test
    void pendingVerificationNeverActivatesTheWallet() {
        service.mockKycResult = "PENDING";
        var pending = service.verify(id, authorization);
        assertEquals(Status.PENDING_VERIFICATION, pending.status());
        assertEquals(VerificationStatus.PENDING, pending.verification().status());
        assertTrue(pending.verification().simulated());
        assertFalse(pending.paymentsAvailable());
        ApiException failure = assertThrows(ApiException.class, () -> service.consent(id, authorization));
        assertEquals(409, failure.status());
        assertEquals(Step.VERIFICATION, service.progress(id, authorization).currentStep());
    }

    @Test
    void rejectedVerificationIsPersistedVisibleAndDoesNotAdvanceOrCreateCompletedEvents() {
        service.mockKycResult = "REJECTED";
        ApiException failure = assertThrows(ApiException.class, () -> service.verify(id, authorization));
        assertEquals(422, failure.status());
        var view = service.progress(id, authorization);
        assertEquals(Step.VERIFICATION, view.currentStep());
        assertEquals(Status.VERIFICATION_REJECTED, view.status());
        assertEquals(VerificationStatus.REJECTED, view.verification().status());
        assertFalse(view.paymentsAvailable());
        assertEquals(409, assertThrows(ApiException.class, () -> service.consent(id, authorization)).status());
        int events = service.repository.read(state -> state.events.size());
        assertEquals(2, events);
        service.mockKycResult = "APPROVED";
        assertEquals(Step.CONSENT, service.verify(id, authorization).currentStep());
    }

    @Test
    void disabledMockProviderFailsClosed() {
        service.mockKycEnabled = false;
        service.mockKycResult = "APPROVED";
        ApiException failure = assertThrows(ApiException.class, () -> service.verify(id, authorization));
        assertEquals(503, failure.status());
        assertFalse(service.progress(id, authorization).paymentsAvailable());
    }

    @Test
    void approvedMockResultCannotActivateAWalletOutsideDemoMode() {
        service.mockKycResult = "APPROVED";
        for (String mode : new String[]{"PRODUCTION", "", null, "prod"}) {
            service.runtime = new RuntimeMode(mode);
            ApiException failure = assertThrows(ApiException.class, () -> service.verify(id, authorization));
            assertEquals("KYC_NOT_CONFIGURED", failure.code());
        }
        var view = service.progress(id, authorization);
        assertEquals(Step.VERIFICATION, view.currentStep());
        assertFalse(view.paymentsAvailable());
        assertEquals(409, assertThrows(ApiException.class, () -> service.paymentEntry(id, authorization)).status());
    }

    @Test
    void unknownProviderResultNeverActivates() {
        for (String result : new String[]{"MAYBE", "", null, "approved"}) {
            service.mockKycResult = result;
            assertEquals("INVALID_KYC_CONFIGURATION", assertThrows(ApiException.class, () -> service.verify(id, authorization)).code());
        }
        assertEquals(Step.VERIFICATION, service.progress(id, authorization).currentStep());
    }

    @Test
    void providerCanResolveAPreviouslyPendingVerification() {
        service.mockKycResult = "PENDING";
        service.verify(id, authorization);
        service.mockKycResult = "APPROVED";
        assertEquals(Step.CONSENT, service.verify(id, authorization).currentStep());
        assertEquals(Status.ACTIVE, service.consent(id, authorization).status());
    }

    @Test
    void anotherPersonAndAMissingWalletGetTheSameAnswer() {
        String other = "Bearer " + "b".repeat(64);
        ApiException foreign = assertThrows(ApiException.class, () -> service.progress(id, other));
        ApiException missing = assertThrows(ApiException.class, () -> service.progress(UUID.randomUUID(), authorization));
        assertEquals(404, foreign.status());
        assertEquals(foreign.status(), missing.status());
        assertEquals(foreign.code(), missing.code());
        assertEquals(foreign.getMessage(), missing.getMessage());
        assertThrows(ApiException.class, () -> service.profile(id, other, new ProfileRequest("Otro", "otro@wallet.test")));
        assertEquals("Demo", service.repository.read(state -> state.wallets.get(id).displayName()));
    }

    @Test
    void resumingKeepsTheOriginalStartAndDoesNotRestartOrDuplicateEvents() {
        var first = service.progress(id, authorization);
        service.mockKycResult = "APPROVED";
        service.verify(id, authorization);
        var resumed = service.progress(id, authorization);
        assertEquals(first.startedAt(), resumed.startedAt());
        assertEquals(Step.CONSENT, resumed.currentStep());
        assertEquals(2, resumed.steps().stream().filter(step -> step.completed()).count());
        var retry = service.start(new StartRequest("PE", "organic"), authorization, "request-12345678");
        assertFalse(retry.created());
        assertEquals(first.startedAt(), retry.wallet().startedAt());
        assertEquals(Step.CONSENT, retry.wallet().currentStep());
        ApiException restart = assertThrows(ApiException.class, () -> service.start(new StartRequest("PE", "organic"), authorization, "request-87654321"));
        assertEquals("TOKEN_ALREADY_USED", restart.code());
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class, () -> service.start(new StartRequest("PE", "organic"),
                "Bearer " + "c".repeat(64), "request-12345678")).code());
    }

    @Test
    void concurrentConsentActivatesOnceAndEmitsASingleCompletionEvent() throws Exception {
        service.mockKycResult = "APPROVED";
        service.verify(id, authorization);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<WalletView>>();
            for (int index = 0; index < 16; index++) { calls.add(executor.submit(() -> service.consent(id, authorization))); }
            for (var call : calls) { assertEquals(Status.ACTIVE, call.get().status()); }
        }
        long completed = service.repository.read(state -> state.events.stream().filter(event -> event.type() == EventType.ONBOARDING_COMPLETED).count());
        long consentSteps = service.repository.read(state -> state.events.stream()
                .filter(event -> event.type() == EventType.STEP_COMPLETED && event.step() == Step.CONSENT).count());
        assertEquals(1, completed);
        assertEquals(1, consentSteps);
    }

    @Test
    void walletsAndEventsRecordTheModeTheyWereCreatedIn() {
        assertEquals("DEMO", service.progress(id, authorization).mode());
        assertTrue(service.progress(id, authorization).mock());
        boolean allDemo = service.repository.read(state -> state.events.stream().allMatch(event -> "DEMO".equals(event.mode())));
        assertTrue(allDemo);
    }
}