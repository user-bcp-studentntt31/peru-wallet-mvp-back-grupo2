package pe.wallet.controller;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.wallet.model.WalletData.EventType;
import pe.wallet.repository.JsonWalletRepository;
import pe.wallet.view.ApiModels.ProfileRequest;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static pe.wallet.controller.ApiTestSupport.*;

@QuarkusTest
class OnboardingContractTest {
    @Inject JsonWalletRepository repository;

    @BeforeEach
    void isolateTestData() { reset(repository); }

    @Test
    void openApiDocumentsEveryEndpointTheErrorSchemaAndCentsAmounts() {
        String document = given().queryParam("format", "json").get("/q/openapi").then().statusCode(200).extract().asString();
        JsonPath spec = JsonPath.from(document);
        Set<String> paths = spec.getMap("paths").keySet().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("/api/wallets", "/api/wallets/{walletId}/onboarding", "/api/wallets/{walletId}/onboarding/profile",
                "/api/wallets/{walletId}/onboarding/verification", "/api/wallets/{walletId}/onboarding/consent",
                "/api/wallets/{walletId}/payments/entry", "/api/wallets/{walletId}/activity",
                "/api/analytics/monthly", "/api/analytics/acquisition-costs", "/api/analytics/events"), paths);
        assertTrue(paths.stream().filter(path -> path.contains("payment")).allMatch(path -> path.endsWith("/payments/entry")),
                "El contrato no debe exponer procesamiento de pagos.");
        Map<String, Object> schemas = spec.getMap("components.schemas");
        for (String name : List.of("ApiError", "WalletView", "VerificationView", "PaymentEntry", "MonthlyReport", "MetricAvailability", "CostRequest")) {
            assertTrue(schemas.containsKey(name), "Falta el esquema " + name);
        }
        assertEquals("integer", spec.getString("components.schemas.CostRequest.properties.amountCents.type"));
        assertEquals("int64", spec.getString("components.schemas.CostRequest.properties.amountCents.format"));
        assertEquals("integer", spec.getString("components.schemas.MonthlyReport.properties.acquisitionSpendCents.type"));
        assertFalse(document.contains("amountPen"), "No debe quedar un importe decimal en el contrato.");
        assertEquals("boolean", spec.getString("components.schemas.PaymentEntry.properties.transactional.type"));
        assertNotNull(spec.get("paths.'/api/wallets/{walletId}/onboarding'.get.responses.'404'"));
        assertNotNull(spec.get("paths.'/api/wallets/{walletId}/onboarding/verification'.post.responses.'422'"));
        assertNotNull(spec.get("components.securitySchemes.wallet-token"));
    }

    @Test
    void onboardingRequestsOnlyContainTheApprovedDemoFields() {
        assertEquals(List.of("displayName", "email"),
                Arrays.stream(ProfileRequest.class.getRecordComponents()).map(component -> component.getName()).toList());
    }

    @Test
    void missingOrInvalidProfileDataIsRejectedWithoutAdvancing() {
        String token = token();
        String id = wallet(token);
        List<String> invalidBodies = List.of("{}", "{\"displayName\":\"Ana\"}", "{\"email\":\"ana@wallet.test\"}",
                "{\"displayName\":\"   \",\"email\":\"ana@wallet.test\"}", "{\"displayName\":\"Ana\",\"email\":\"ana@gmail.com\"}",
                "{\"displayName\":\"Ana\",\"email\":\"no-es-correo\"}");
        for (String body : invalidBodies) {
            given().contentType("application/json").header("Authorization", "Bearer " + token).body(body)
                    .put("/api/wallets/" + id + "/onboarding/profile").then().statusCode(400)
                    .body("code", equalTo("VALIDATION_ERROR")).body("fields", not(anEmptyMap()));
            progress(id, token).statusCode(200).body("currentStep", equalTo("PROFILE"))
                    .body("steps.findAll { it.completed }.size()", equalTo(0));
        }
        given().contentType("application/json").header("Authorization", "Bearer " + token).body("{\"email\":\"ana@wallet.test\"}")
                .put("/api/wallets/" + id + "/onboarding/profile").then().body("fields.displayName", notNullValue());
        profile(id, token, "ana@wallet.test").statusCode(200).body("currentStep", equalTo("VERIFICATION"))
                .body("steps.findAll { it.completed }.name", contains("PROFILE"));
    }

    @Test
    void everyWalletEndpointTreatsAForeignWalletLikeAMissingOne() {
        String owner = token();
        String stranger = token();
        String id = wallet(owner);
        completeProfile(id, owner);
        String unknown = UUID.randomUUID().toString();
        for (String target : new String[]{id, unknown}) {
            progress(target, stranger).statusCode(404).body("code", equalTo("WALLET_NOT_FOUND"));
            profile(target, stranger, "otro@wallet.test").statusCode(404).body("code", equalTo("WALLET_NOT_FOUND"));
            verify(target, stranger).statusCode(404);
            consent(target, stranger).statusCode(404);
            paymentEntry(target, stranger).statusCode(404);
            given().header("Authorization", "Bearer " + stranger).header("Idempotency-Key", UUID.randomUUID().toString())
                    .post("/api/wallets/" + target + "/activity").then().statusCode(404);
        }
        given().get("/api/wallets/" + id + "/onboarding").then().statusCode(401).body("code", equalTo("WALLET_TOKEN_REQUIRED"));
        given().header("Authorization", "Bearer corto").get("/api/wallets/" + id + "/onboarding").then().statusCode(401);
        progress(id, owner).statusCode(200).body("currentStep", equalTo("VERIFICATION"));
    }

    @Test
    void eventsDistinguishStartStepAndActivationUseServerTimestampsAndHaveNoExitOrSensitiveFields() {
        Instant before = Instant.now().minusSeconds(1);
        String token = token();
        String id = wallet(token);
        String email = "eventos-" + UUID.randomUUID() + "@wallet.test";
        profile(id, token, email).statusCode(200);
        verify(id, token).statusCode(200);
        consent(id, token).statusCode(200);
        var response = given().header("X-Analytics-Key", ADMIN_KEY).queryParam("walletId", id).get("/api/analytics/events").then().statusCode(200);
        String body = response.extract().asString();
        List<Map<String, Object>> events = response.extract().path("events");
        assertEquals(5, events.size());
        assertEquals(List.of("ONBOARDING_STARTED", "STEP_COMPLETED", "STEP_COMPLETED", "STEP_COMPLETED", "ONBOARDING_COMPLETED"),
                events.stream().map(event -> event.get("type").toString()).toList());
        for (Map<String, Object> event : events) {
            assertEquals(Set.of("id", "walletId", "type", "step", "occurredAt", "mode"), event.keySet());
            Instant occurred = Instant.parse(event.get("occurredAt").toString());
            assertFalse(occurred.isBefore(before));
            assertFalse(occurred.isAfter(Instant.now().plusSeconds(1)));
            assertEquals("DEMO", event.get("mode"));
        }
        assertEquals(List.of("PROFILE", "VERIFICATION", "CONSENT"),
                events.stream().filter(event -> "STEP_COMPLETED".equals(event.get("type"))).map(event -> event.get("step").toString()).toList());
        assertFalse(body.contains(email));
        assertFalse(body.contains("Usuario Demo"));
        assertFalse(body.contains(token));
        assertTrue(Arrays.stream(EventType.values()).noneMatch(type -> type.name().contains("EXIT")),
                "onboarding_exited no debe emitirse sin una salida observable de forma fiable (D5).");
    }

    @Test
    void personalValuesNeverAppearInLogsErrorsOrAnalytics() {
        List<String> logs = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                logs.add(String.valueOf(record.getMessage()) + " " + Arrays.toString(record.getParameters())
                        + (record.getThrown() == null ? "" : record.getThrown().toString()));
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        root.addHandler(handler);
        List<String> bodies = new ArrayList<>();
        String token = token();
        String email = "privado-" + UUID.randomUUID() + "@wallet.test";
        String badEmail = "privado-" + UUID.randomUUID() + "@gmail.com";
        try {
            String id = wallet(token);
            bodies.add(given().contentType("application/json").header("Authorization", "Bearer " + token)
                    .body(Map.of("displayName", "Nombre Privado", "email", badEmail))
                    .put("/api/wallets/" + id + "/onboarding/profile").then().statusCode(400).extract().asString());
            bodies.add(given().contentType("application/json").header("Authorization", "Bearer " + token)
                    .body("{\"displayName\":\"Nombre Privado\",\"email\":\"" + email + "\",")
                    .put("/api/wallets/" + id + "/onboarding/profile").then().statusCode(400).extract().asString());
            bodies.add(profile(id, token, email).statusCode(200).extract().asString());
            bodies.add(profile(id, token, "otro-" + UUID.randomUUID() + "@wallet.test").statusCode(409).extract().asString());
            bodies.add(progress(id, token).extract().asString());
            bodies.add(progress(id, token()).statusCode(404).extract().asString());
            bodies.add(given().header("X-Analytics-Key", ADMIN_KEY).get("/api/analytics/events").then().extract().asString());
            bodies.add(given().header("X-Analytics-Key", ADMIN_KEY).queryParam("month", YearMonth.now(ZoneId.of("America/Lima")).toString())
                    .get("/api/analytics/monthly").then().extract().asString());
        } finally {
            root.removeHandler(handler);
        }
        List<String> secrets = List.of(email, badEmail, "Nombre Privado", token);
        for (String secret : secrets) {
            bodies.forEach(body -> assertFalse(body.contains(secret), "Respuesta con un valor personal: " + secret));
            logs.forEach(log -> assertFalse(log.contains(secret), "Log con un valor personal: " + secret));
        }
    }

    @Test
    void acquisitionCostsAcceptOnlyIntegerCents() {
        String month = YearMonth.now(ZoneId.of("America/Lima")).toString();
        for (String amount : new String[]{"10.5", "10.00", "-1", "1000000001", "null", "\"abc\""}) {
            cost("{\"month\":\"" + month + "\",\"channel\":\"organic\",\"amountCents\":" + amount + "}").statusCode(400);
        }
        cost("{\"month\":\"" + month + "\",\"channel\":\"organic\",\"amountPen\":10.00}").statusCode(400)
                .body("fields.amountCents", notNullValue());
        cost("{\"month\":\"" + month + "\",\"channel\":\"organic\"}").statusCode(400);
        cost("{\"month\":\"" + month + "\",\"channel\":\"organic\",\"amountCents\":0}").statusCode(200).body("amountCents", equalTo(0));
        cost("{\"month\":\"" + month + "\",\"channel\":\"referral\",\"amountCents\":1050}").statusCode(200).body("amountCents", equalTo(1050));
    }

    private io.restassured.response.ValidatableResponse cost(String body) {
        return given().contentType("application/json").header("X-Analytics-Key", ADMIN_KEY)
                .header("Idempotency-Key", UUID.randomUUID().toString()).body(body).post("/api/analytics/acquisition-costs").then();
    }

    @Test
    void adminEndpointsRequireTheAdministrativeKey() {
        String month = YearMonth.now(ZoneId.of("America/Lima")).toString();
        for (String key : new String[]{null, "", "otra-clave"}) {
            var monthly = given().queryParam("month", month);
            var events = given();
            var costs = given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                    .body("{\"month\":\"" + month + "\",\"channel\":\"organic\",\"amountCents\":100}");
            if (key != null) {
                monthly = monthly.header("X-Analytics-Key", key);
                events = events.header("X-Analytics-Key", key);
                costs = costs.header("X-Analytics-Key", key);
            }
            monthly.get("/api/analytics/monthly").then().statusCode(401).body("code", equalTo("ANALYTICS_UNAUTHORIZED"));
            events.get("/api/analytics/events").then().statusCode(401);
            costs.post("/api/analytics/acquisition-costs").then().statusCode(401);
        }
        String walletToken = token();
        given().header("Authorization", "Bearer " + walletToken).get("/api/analytics/events").then().statusCode(401);
        given().header("X-Analytics-Key", ADMIN_KEY).queryParam("month", month).get("/api/analytics/monthly").then().statusCode(200);
    }
}
