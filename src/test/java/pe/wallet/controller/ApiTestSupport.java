package pe.wallet.controller;

import io.restassured.response.ValidatableResponse;
import pe.wallet.repository.JsonWalletRepository;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Utilidades compartidas por las pruebas REST: todo el trafico usa datos ficticios. */
final class ApiTestSupport {
    static final String ADMIN_KEY = "analytics-key-only-for-automated-tests";

    private ApiTestSupport() {}

    static void reset(JsonWalletRepository repository) {
        repository.update(state -> {
            state.wallets.clear();
            state.registrations.clear();
            state.events.clear();
            state.expenses.clear();
            state.activities.clear();
            return null;
        });
    }

    static String token() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    static ValidatableResponse start(String token, String key) {
        return given().contentType("application/json").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key).body(Map.of("country", "PE", "channel", "organic"))
                .when().post("/api/wallets").then();
    }

    static String wallet(String token) {
        return start(token, UUID.randomUUID().toString()).statusCode(201).extract().path("walletId");
    }

    static ValidatableResponse profile(String id, String token, String email) {
        return given().contentType("application/json").header("Authorization", "Bearer " + token)
                .body(Map.of("displayName", "Usuario Demo", "email", email))
                .put("/api/wallets/" + id + "/onboarding/profile").then();
    }

    static void completeProfile(String id, String token) {
        profile(id, token, UUID.randomUUID() + "@wallet.test").statusCode(200).body("currentStep", equalTo("VERIFICATION"));
    }

    static ValidatableResponse verify(String id, String token) {
        return given().header("Authorization", "Bearer " + token).post("/api/wallets/" + id + "/onboarding/verification").then();
    }

    static ValidatableResponse consent(String id, String token) {
        return given().contentType("application/json").header("Authorization", "Bearer " + token).body("{\"accepted\":true}")
                .put("/api/wallets/" + id + "/onboarding/consent").then();
    }

    static ValidatableResponse progress(String id, String token) {
        return given().header("Authorization", "Bearer " + token).get("/api/wallets/" + id + "/onboarding").then();
    }

    static ValidatableResponse paymentEntry(String id, String token) {
        return given().header("Authorization", "Bearer " + token).get("/api/wallets/" + id + "/payments/entry").then();
    }

    static void activate(String id, String token) {
        completeProfile(id, token);
        verify(id, token).statusCode(200).body("currentStep", equalTo("CONSENT"));
        consent(id, token).statusCode(200).body("status", equalTo("ACTIVE"));
    }
}
