package pe.wallet.controller;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.wallet.repository.JsonWalletRepository;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class OnboardingApiTest {
    private static final String ADMIN_KEY = "analytics-key-only-for-automated-tests";
        @Inject JsonWalletRepository repository;

        @BeforeEach
        void isolateTestData() {
                repository.update(state -> {
                        state.wallets.clear();
                        state.registrations.clear();
                        state.events.clear();
                        state.expenses.clear();
                        state.activities.clear();
                        return null;
                });
        }

    private String token() { return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""); }

    private ValidatableResponse start(String token, String key) {
        return given().contentType("application/json").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key).body(Map.of("country", "PE", "channel", "organic"))
                .when().post("/api/wallets").then();
    }

    private String wallet(String token) {
        return start(token, UUID.randomUUID().toString()).statusCode(201).extract().path("walletId");
    }

    private void profile(String id, String token) {
        given().contentType("application/json").header("Authorization", "Bearer " + token)
                .body(Map.of("displayName", "Usuario Demo", "email", UUID.randomUUID() + "@wallet.test"))
                .put("/api/wallets/" + id + "/onboarding/profile").then().statusCode(200).body("currentStep", equalTo("VERIFICATION"));
    }

    private void activate(String id, String token) {
        profile(id, token);
        given().header("Authorization", "Bearer " + token).post("/api/wallets/" + id + "/onboarding/verification")
                .then().statusCode(200).body("currentStep", equalTo("CONSENT"));
        given().contentType("application/json").header("Authorization", "Bearer " + token).body("{\"accepted\":true}")
                .put("/api/wallets/" + id + "/onboarding/consent").then().statusCode(200).body("status", equalTo("ACTIVE"));
    }

    @Test
    void completesAndRetriesWithoutDuplicatingWalletOrEvents() {
        String token = token();
        String key = UUID.randomUUID().toString();
        String id = start(token, key).statusCode(201).body("currentStep", equalTo("PROFILE"))
                .body("steps.size()", equalTo(3)).body("mock", equalTo(true)).extract().path("walletId");
        start(token, key).statusCode(200).body("walletId", equalTo(id));
        activate(id, token);
        given().contentType("application/json").header("Authorization", "Bearer " + token).body("{\"accepted\":true}")
                .put("/api/wallets/" + id + "/onboarding/consent").then().statusCode(200);
        given().header("Authorization", "Bearer " + token).get("/api/wallets/" + id + "/onboarding")
                .then().statusCode(200).body("paymentsAvailable", equalTo(true)).body("currentStep", nullValue())
                .body("steps.findAll { it.completed }.size()", equalTo(3));
        given().header("Authorization", "Bearer " + token).get("/api/wallets/" + id + "/payments/entry")
                .then().statusCode(200).body("mode", equalTo("MOCK"));
        given().header("X-Analytics-Key", ADMIN_KEY).queryParam("walletId", id).get("/api/analytics/events")
                .then().statusCode(200).body("total", equalTo(5))
                .body("events.findAll { it.type == 'STEP_COMPLETED' }.size()", equalTo(3));
    }

    @Test
    void rejectsInvalidInputWithoutAdvancingAndRejectsOtherWalletToken() {
        String token = token();
        String id = wallet(token);
        given().contentType("application/json").header("Authorization", "Bearer " + token)
                .body(Map.of("displayName", "", "email", "not-an-email"))
                .put("/api/wallets/" + id + "/onboarding/profile").then().statusCode(400).body("code", equalTo("VALIDATION_ERROR"));
        given().header("Authorization", "Bearer " + token).get("/api/wallets/" + id + "/onboarding")
                .then().statusCode(200).body("currentStep", equalTo("PROFILE"));
        given().header("Authorization", "Bearer " + token()).get("/api/wallets/" + id + "/onboarding")
                .then().statusCode(404).body("code", equalTo("WALLET_NOT_FOUND"));
    }

    @Test
    void blocksSkippedStepsPaymentsAndMissingConsent() {
        String token = token();
        String id = wallet(token);
        given().header("Authorization", "Bearer " + token).get("/api/wallets/" + id + "/payments/entry")
                .then().statusCode(409);
        given().header("Authorization", "Bearer " + token).post("/api/wallets/" + id + "/onboarding/verification")
                .then().statusCode(409).body("code", equalTo("STEP_OUT_OF_ORDER"));
        profile(id, token);
        given().header("Authorization", "Bearer " + token).post("/api/wallets/" + id + "/onboarding/verification").then().statusCode(200);
        given().contentType("application/json").header("Authorization", "Bearer " + token).body("{\"accepted\":false}")
                .put("/api/wallets/" + id + "/onboarding/consent").then().statusCode(400);
        given().header("Authorization", "Bearer " + token).get("/api/wallets/" + id + "/onboarding")
                .then().statusCode(200).body("currentStep", equalTo("CONSENT")).body("paymentsAvailable", equalTo(false));
    }

    @Test
    void recordsActivityAndCostsIdempotentlyAndProtectsAnalytics() {
        String token = token();
        String id = wallet(token);
        activate(id, token);
        String activityKey = UUID.randomUUID().toString();
        String eventId = given().header("Authorization", "Bearer " + token).header("Idempotency-Key", activityKey)
                .post("/api/wallets/" + id + "/activity").then().statusCode(200).extract().path("eventId");
        given().header("Authorization", "Bearer " + token).header("Idempotency-Key", activityKey)
                .post("/api/wallets/" + id + "/activity").then().statusCode(200).body("eventId", equalTo(eventId));
        String month = YearMonth.now(ZoneId.of("America/Lima")).toString();
        String costKey = UUID.randomUUID().toString();
        var cost = Map.of("month", month, "channel", "organic", "amountCents", 1000);
        String expenseId = given().contentType("application/json").header("X-Analytics-Key", ADMIN_KEY)
                .header("Idempotency-Key", costKey).body(cost).post("/api/analytics/acquisition-costs")
                .then().statusCode(200).extract().path("id");
        given().contentType("application/json").header("X-Analytics-Key", ADMIN_KEY)
                .header("Idempotency-Key", costKey).body(cost).post("/api/analytics/acquisition-costs")
                .then().statusCode(200).body("id", equalTo(expenseId));
        given().queryParam("month", month).get("/api/analytics/monthly").then().statusCode(401);
        given().header("X-Analytics-Key", ADMIN_KEY).queryParam("month", month)
                .get("/api/analytics/monthly").then().statusCode(200).body("activatedWallets", greaterThanOrEqualTo(1))
                .body("acquisitionSpendCents", equalTo(1000))
                .body("monthlyActiveUsers.available", equalTo(false)).body("monthlyActiveUsers.value", nullValue())
                .body("acquisitionCost.available", equalTo(false)).body("acquisitionCost.value", nullValue());
    }

    @Test
    void rejectsMissingCredentialsWrongCountryAndConflictingRetry() {
        given().contentType("application/json").body("{\"country\":\"PE\",\"channel\":\"organic\"}")
                .post("/api/wallets").then().statusCode(401);
        given().contentType("application/json").header("Authorization", "Bearer " + token())
                .header("Idempotency-Key", UUID.randomUUID().toString()).body("{\"country\":\"CL\",\"channel\":\"organic\"}")
                .post("/api/wallets").then().statusCode(400);
        String token = token();
        String key = UUID.randomUUID().toString();
        start(token, key).statusCode(201);
        given().contentType("application/json").header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .body("{\"country\":\"PE\",\"channel\":\"referral\"}").post("/api/wallets")
                .then().statusCode(409).body("code", equalTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void rejectsEmptyBodyInvalidIdentifiersAndMissingIdempotencyKey() {
        String token = token();
        given().contentType("application/json").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).body("null")
                .post("/api/wallets").then().statusCode(400);
        given().contentType("application/json").header("Authorization", "Bearer " + token)
                .body("{\"country\":\"PE\",\"channel\":\"organic\"}")
                .post("/api/wallets").then().statusCode(400).body("code", equalTo("IDEMPOTENCY_KEY_REQUIRED"));
        given().header("Authorization", "Bearer " + token).get("/api/wallets/not-a-uuid/onboarding")
                .then().statusCode(404);
        given().header("X-Analytics-Key", ADMIN_KEY).queryParam("month", "2026-13")
                .get("/api/analytics/monthly").then().statusCode(400).body("code", equalTo("INVALID_MONTH"));
    }
}