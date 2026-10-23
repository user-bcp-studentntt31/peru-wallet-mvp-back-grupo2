package pe.wallet.controller;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.wallet.repository.JsonWalletRepository;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static pe.wallet.controller.ApiTestSupport.*;

/** HU-02-INT-01: verificacion simulada pendiente, con configuracion aislada y datos ficticios. */
@QuarkusTest
@TestProfile(PendingVerificationApiTest.Pending.class)
class PendingVerificationApiTest {
    public static class Pending implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() { return Map.of("wallet.mock-kyc.result", "PENDING"); }
    }

    @Inject JsonWalletRepository repository;

    @BeforeEach
    void isolateTestData() { reset(repository); }

    @Test
    void pendingVerificationShowsTheRealStateAndNeverActivates() {
        String token = token();
        String id = wallet(token);
        completeProfile(id, token);
        verify(id, token).statusCode(200).body("status", equalTo("PENDING_VERIFICATION"))
                .body("verification.status", equalTo("PENDING")).body("verification.simulated", equalTo(true))
                .body("paymentsAvailable", equalTo(false)).body("currentStep", equalTo("VERIFICATION"));
        consent(id, token).statusCode(409).body("code", equalTo("STEP_OUT_OF_ORDER"));
        paymentEntry(id, token).statusCode(409).body("code", equalTo("WALLET_NOT_ACTIVE"));
        progress(id, token).body("status", equalTo("PENDING_VERIFICATION")).body("nextAction", containsString("pendiente"));
    }
}
