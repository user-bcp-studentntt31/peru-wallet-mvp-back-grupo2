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

/** HU-02-BE-01: aun con un resultado simulado APPROVED, el modo productivo nunca activa una wallet. */
@QuarkusTest
@TestProfile(ProductionModeApiTest.Production.class)
class ProductionModeApiTest {
    public static class Production implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("wallet.mode", "PRODUCTION", "wallet.mock-kyc.enabled", "true", "wallet.mock-kyc.result", "APPROVED");
        }
    }

    @Inject JsonWalletRepository repository;

    @BeforeEach
    void isolateTestData() { reset(repository); }

    @Test
    void simulatedApprovalCannotAuthorizeAProductionWallet() {
        String token = token();
        String id = start(token, java.util.UUID.randomUUID().toString()).statusCode(201)
                .body("mode", equalTo("PRODUCTION")).body("mock", equalTo(false)).extract().path("walletId");
        completeProfile(id, token);
        verify(id, token).statusCode(503).body("code", equalTo("KYC_NOT_CONFIGURED"));
        consent(id, token).statusCode(409).body("code", equalTo("STEP_OUT_OF_ORDER"));
        paymentEntry(id, token).statusCode(409);
        progress(id, token).body("status", equalTo("ONBOARDING")).body("paymentsAvailable", equalTo(false))
                .body("verification.status", equalTo("NOT_STARTED")).body("verification.simulated", equalTo(false));
    }

    @Test
    void productionReportsNeverIncludeDemoData() {
        repository.update(state -> {
            java.util.UUID demo = java.util.UUID.randomUUID();
            state.wallets.put(demo, new pe.wallet.model.WalletData.Wallet(demo, "hash", "PE", "organic", "Demo", "demo@wallet.test",
                    pe.wallet.model.WalletData.Status.ACTIVE, java.util.List.of(pe.wallet.model.WalletData.Step.values()),
                    java.time.Instant.now().minusSeconds(60), java.time.Instant.now(), "DEMO"));
            state.events.add(new pe.wallet.model.WalletData.Event(java.util.UUID.randomUUID(), demo,
                    pe.wallet.model.WalletData.EventType.ONBOARDING_COMPLETED, null, java.time.Instant.now(), "DEMO"));
            return null;
        });
        String month = java.time.YearMonth.now(java.time.ZoneId.of("America/Lima")).toString();
        given().header("X-Analytics-Key", ADMIN_KEY).queryParam("month", month).get("/api/analytics/monthly").then().statusCode(200)
                .body("dataMode", equalTo("PRODUCTION")).body("activatedWallets", equalTo(0))
                .body("medianOnboardingSeconds", nullValue());
        given().header("X-Analytics-Key", ADMIN_KEY).get("/api/analytics/events").then().statusCode(200).body("total", equalTo(0));
    }
}
