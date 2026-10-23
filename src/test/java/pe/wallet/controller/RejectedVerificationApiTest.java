package pe.wallet.controller;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.wallet.repository.JsonWalletRepository;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static pe.wallet.controller.ApiTestSupport.*;

/** HU-02-INT-01: verificacion simulada no aprobada, con configuracion aislada y datos ficticios. */
@QuarkusTest
@TestProfile(RejectedVerificationApiTest.Rejected.class)
class RejectedVerificationApiTest {
    public static class Rejected implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() { return Map.of("wallet.mock-kyc.result", "REJECTED"); }
    }

    @Inject JsonWalletRepository repository;

    @BeforeEach
    void isolateTestData() { reset(repository); }

    @Test
    void rejectedVerificationIsReportedAndLeavesTheWalletInactive() {
        String token = token();
        String id = wallet(token);
        completeProfile(id, token);
        verify(id, token).statusCode(422).body("code", equalTo("MOCK_VERIFICATION_REJECTED"));
        progress(id, token).statusCode(200).body("status", equalTo("VERIFICATION_REJECTED"))
                .body("verification.status", equalTo("REJECTED")).body("verification.simulated", equalTo(true))
                .body("paymentsAvailable", equalTo(false)).body("currentStep", equalTo("VERIFICATION"))
                .body("nextAction", containsString("no fue aprobada"));
        consent(id, token).statusCode(409);
        paymentEntry(id, token).statusCode(409).body("code", equalTo("WALLET_NOT_ACTIVE"));
        given().header("X-Analytics-Key", ADMIN_KEY).queryParam("walletId", id).get("/api/analytics/events").then()
                .body("events.findAll { it.type == 'ONBOARDING_COMPLETED' }.size()", equalTo(0))
                .body("events.findAll { it.type == 'STEP_COMPLETED' }.step", contains("PROFILE"));
    }
}
