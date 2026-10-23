package pe.wallet.controller;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.wallet.repository.JsonWalletRepository;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static pe.wallet.controller.ApiTestSupport.*;

/** HU-01-INT-01, HU-02-INT-01 (camino aprobado) y HU-03-INT-01 sobre el backend real, con datos ficticios. */
@QuarkusTest
class FlowIntegrationTest {
    @Inject JsonWalletRepository repository;

    @BeforeEach
    void isolateTestData() { reset(repository); }

    @Test
    void hu01SignUpGuidedStepsValidationBlockAndResumeAfterInterruption() {
        given().contentType("application/json").body("{\"country\":\"PE\",\"channel\":\"organic\"}")
                .post("/api/wallets").then().statusCode(401);
        String token = token();
        String id = start(token, UUID.randomUUID().toString()).statusCode(201)
                .body("steps.name", contains("PROFILE", "VERIFICATION", "CONSENT")).body("currentStep", equalTo("PROFILE"))
                .body("nextAction", not(emptyOrNullString())).extract().path("walletId");
        String startedAt = progress(id, token).extract().path("startedAt");

        profile(id, token, "invalido@gmail.com").statusCode(400).body("code", equalTo("VALIDATION_ERROR"));
        progress(id, token).body("currentStep", equalTo("PROFILE"));
        completeProfile(id, token);

        // El usuario abandona y vuelve: el progreso y el inicio original se conservan.
        progress(id, token).statusCode(200).body("currentStep", equalTo("VERIFICATION")).body("startedAt", equalTo(startedAt))
                .body("steps.findAll { it.completed }.name", contains("PROFILE")).body("paymentsAvailable", equalTo(false));
        verify(id, token).statusCode(200).body("currentStep", equalTo("CONSENT"));
        progress(id, token).body("startedAt", equalTo(startedAt)).body("steps.findAll { it.completed }.size()", equalTo(2));
        consent(id, token).statusCode(200).body("status", equalTo("ACTIVE")).body("startedAt", equalTo(startedAt));
    }

    @Test
    void hu02ApprovedSimulatedVerificationReachesTheNonTransactionalEntryOnlyWhenActive() {
        String token = token();
        String id = wallet(token);
        paymentEntry(id, token).statusCode(409).body("code", equalTo("WALLET_NOT_ACTIVE"));
        completeProfile(id, token);
        progress(id, token).body("status", equalTo("ONBOARDING")).body("verification.status", equalTo("NOT_STARTED"))
                .body("paymentsAvailable", equalTo(false));
        verify(id, token).statusCode(200).body("verification.status", equalTo("APPROVED"))
                .body("verification.simulated", equalTo(true)).body("status", equalTo("ONBOARDING"));
        paymentEntry(id, token).statusCode(409);
        consent(id, token).statusCode(200).body("status", equalTo("ACTIVE")).body("paymentsAvailable", equalTo(true))
                .body("mode", equalTo("DEMO")).body("verification.simulated", equalTo(true));
        paymentEntry(id, token).statusCode(200).body("available", equalTo(true)).body("transactional", equalTo(false))
                .body("mode", equalTo("MOCK"));
        paymentEntry(id, token()).statusCode(404);
    }

    @Test
    void hu03FunnelDurationAndMonthlySummaryAreConsistentAndDoNotPassActivationsOffAsMauOrCac() {
        String resumed = token();
        String resumedId = wallet(resumed);
        completeProfile(resumedId, resumed);
        verify(resumedId, resumed).statusCode(200);
        consent(resumedId, resumed).statusCode(200);
        String abandoned = token();
        String abandonedId = wallet(abandoned);
        completeProfile(abandonedId, abandoned);

        given().header("Authorization", "Bearer " + resumed).header("Idempotency-Key", UUID.randomUUID().toString())
                .post("/api/wallets/" + resumedId + "/activity").then().statusCode(200);
        String month = YearMonth.now(ZoneId.of("America/Lima")).toString();
        given().contentType("application/json").header("X-Analytics-Key", ADMIN_KEY).header("Idempotency-Key", UUID.randomUUID().toString())
                .body("{\"month\":\"" + month + "\",\"channel\":\"organic\",\"amountCents\":2500}")
                .post("/api/analytics/acquisition-costs").then().statusCode(200);

        given().header("X-Analytics-Key", ADMIN_KEY).queryParam("walletId", resumedId).get("/api/analytics/events").then()
                .body("events.findAll { it.type == 'ONBOARDING_STARTED' }.size()", equalTo(1))
                .body("events.findAll { it.type == 'STEP_COMPLETED' }.size()", equalTo(3))
                .body("events.findAll { it.type == 'ONBOARDING_COMPLETED' }.size()", equalTo(1))
                .body("events.findAll { it.type == 'WALLET_ACTIVITY' }.size()", equalTo(1));
        given().header("X-Analytics-Key", ADMIN_KEY).queryParam("walletId", abandonedId).get("/api/analytics/events").then()
                .body("events.findAll { it.type == 'ONBOARDING_COMPLETED' }.size()", equalTo(0))
                .body("events.type", not(hasItem("ONBOARDING_EXITED")));

        given().header("X-Analytics-Key", ADMIN_KEY).queryParam("month", month).get("/api/analytics/monthly").then().statusCode(200)
                .body("activatedWallets", equalTo(1)).body("dataMode", equalTo("DEMO"))
                .body("medianOnboardingSeconds", notNullValue()).body("medianOnboardingSeconds", greaterThanOrEqualTo(0.0f))
                .body("acquisitionSpendCents", equalTo(2500))
                .body("monthlyActiveUsers.available", equalTo(false)).body("monthlyActiveUsers.value", nullValue())
                .body("monthlyActiveUsers.reason", containsString("D7"))
                .body("acquisitionCost.available", equalTo(false)).body("acquisitionCost.value", nullValue())
                .body("acquisitionCost.reason", containsString("D8"))
                .body("targetEvaluation", startsWith("NOT_EVALUATED")).body("targetMedianSeconds", equalTo(180))
                .body("$", not(hasKey("newActivatedUsers")));
    }
}
