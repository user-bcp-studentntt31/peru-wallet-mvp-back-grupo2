package pe.wallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.wallet.model.WalletData.*;
import pe.wallet.repository.JsonWalletRepository;

import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AnalyticsServiceTest {
    @TempDir Path directory;
    private AnalyticsService service;

    @BeforeEach
    void prepare() {
        service = new AnalyticsService();
        service.repository = new JsonWalletRepository(directory.resolve("wallets.json"), new ObjectMapper().findAndRegisterModules());
        service.access = new AccessControl();
        service.runtime = new RuntimeMode("DEMO");
    }

    private UUID activatedWallet(State state, String channel, String mode, Instant started, Instant completed) {
        UUID id = UUID.randomUUID();
        state.wallets.put(id, new Wallet(id, "test-hash", "PE", channel, "Demo", id + "@wallet.test", Status.ACTIVE,
                List.of(Step.values()), started, completed, mode));
        return id;
    }

    @Test
    void computesExactMedianAndCacInCentsWithTheLimaMonthBoundaryWhenSourcesAreApproved() {
        service.mauSourceApproved = true;
        service.cacSourceApproved = true;
        Instant completed = Instant.parse("2026-10-15T18:00:00Z");
        service.repository.update(state -> {
            for (int duration : new int[]{60, 180, 300}) {
                UUID id = activatedWallet(state, "organic", null, completed.minusSeconds(duration), completed);
                if (duration != 300) {
                    state.events.add(new Event(UUID.randomUUID(), id, EventType.WALLET_ACTIVITY, null, completed));
                    state.events.add(new Event(UUID.randomUUID(), id, EventType.WALLET_ACTIVITY, null, Instant.parse("2026-11-01T03:00:00Z")));
                }
                if (duration == 300) {
                    state.events.add(new Event(UUID.randomUUID(), id, EventType.WALLET_ACTIVITY, null, Instant.parse("2026-11-01T06:00:00Z")));
                }
            }
            state.expenses.put("expense", new Expense(UUID.randomUUID(), YearMonth.of(2026, 10), "organic", 3000, completed));
            return null;
        });
        var report = service.monthly("2026-10", "organic");
        assertEquals(3, report.activatedWallets());
        assertEquals(2, report.monthlyActiveUsers().value());
        assertEquals(180.0, report.medianOnboardingSeconds());
        assertEquals(1000L, report.acquisitionCost().value());
        assertEquals("PEN_CENTS", report.acquisitionCost().unit());
        assertEquals(1, service.monthly("2026-11", "organic").monthlyActiveUsers().value());
    }

    @Test
    void mauAndCacAreUnavailableWithoutApprovedSourcesEvenWithActivationsActivityAndSpend() {
        Instant completed = Instant.parse("2026-10-15T18:00:00Z");
        service.repository.update(state -> {
            UUID id = activatedWallet(state, "organic", null, completed.minusSeconds(120), completed);
            state.events.add(new Event(UUID.randomUUID(), id, EventType.WALLET_ACTIVITY, null, completed));
            state.expenses.put("expense", new Expense(UUID.randomUUID(), YearMonth.of(2026, 10), "organic", 1000, completed));
            return null;
        });
        var report = service.monthly("2026-10", "organic");
        assertEquals(1, report.activatedWallets());
        assertFalse(report.monthlyActiveUsers().available());
        assertNull(report.monthlyActiveUsers().value());
        assertTrue(report.monthlyActiveUsers().reason().contains("D7"));
        assertFalse(report.acquisitionCost().available());
        assertNull(report.acquisitionCost().value());
        assertTrue(report.acquisitionCost().reason().contains("D8"));
        assertTrue(report.targetEvaluation().startsWith("NOT_EVALUATED"));
    }

    @Test
    void approvedSourcesWithoutDataStayUnavailableInsteadOfZero() {
        service.mauSourceApproved = true;
        service.cacSourceApproved = true;
        Instant completed = Instant.parse("2026-10-15T18:00:00Z");
        service.repository.update(state -> {
            activatedWallet(state, "organic", null, completed.minusSeconds(120), completed);
            state.expenses.put("referral", new Expense(UUID.randomUUID(), YearMonth.of(2026, 10), "referral", 2000, completed));
            return null;
        });
        assertFalse(service.monthly("2026-10", "organic").acquisitionCost().available());
        assertFalse(service.monthly("2026-10", "referral").acquisitionCost().available());
        assertEquals(0, service.monthly("2026-10", "organic").monthlyActiveUsers().value());
    }

    @Test
    void roundsCacToTheNearestCentWithoutFloatingPoint() {
        service.cacSourceApproved = true;
        Instant completed = Instant.parse("2026-10-15T18:00:00Z");
        service.repository.update(state -> {
            for (int index = 0; index < 3; index++) { activatedWallet(state, "organic", null, completed.minusSeconds(60), completed); }
            state.expenses.put("expense", new Expense(UUID.randomUUID(), YearMonth.of(2026, 10), "organic", 1001, completed));
            return null;
        });
        assertEquals(334L, service.monthly("2026-10", "organic").acquisitionCost().value());
    }

    @Test
    void medianCoversResumedNotActivatedAndWindowBoundaryCases() {
        service.repository.update(state -> {
            // Reanudado: el inicio original (hace 2 dias) cuenta aunque la activacion ocurra despues de una pausa larga.
            activatedWallet(state, "organic", null, Instant.parse("2026-10-13T15:00:00Z"), Instant.parse("2026-10-15T15:00:00Z"));
            activatedWallet(state, "organic", null, Instant.parse("2026-10-15T14:59:00Z"), Instant.parse("2026-10-15T15:00:00Z"));
            // No activado: no entra en la mediana ni en el conteo.
            UUID pending = UUID.randomUUID();
            state.wallets.put(pending, new Wallet(pending, "h", "PE", "organic", null, null, Status.ONBOARDING, List.of(),
                    Instant.parse("2026-10-10T15:00:00Z"), null));
            // Limites del mes de Lima: 2026-11-01T04:59:59Z sigue siendo octubre; 05:00:00Z ya es noviembre.
            activatedWallet(state, "organic", null, Instant.parse("2026-11-01T04:58:59Z"), Instant.parse("2026-11-01T04:59:59Z"));
            activatedWallet(state, "organic", null, Instant.parse("2026-11-01T04:59:00Z"), Instant.parse("2026-11-01T05:00:00Z"));
            return null;
        });
        var october = service.monthly("2026-10", "organic");
        assertEquals(3, october.activatedWallets());
        assertEquals(60.0, october.medianOnboardingSeconds());
        var november = service.monthly("2026-11", "organic");
        assertEquals(1, november.activatedWallets());
        assertEquals(60.0, november.medianOnboardingSeconds());
    }

    @Test
    void missingDataIsNotReportedAsZeroCostOrAReachedTarget() {
        var report = service.monthly("2026-10", null);
        assertEquals(0, report.activatedWallets());
        assertNull(report.medianOnboardingSeconds());
        assertNull(report.acquisitionSpendCents());
        assertFalse(report.acquisitionSpendRecorded());
        assertFalse(report.acquisitionCost().available());
    }

    @Test
    void demoDataNeverFeedsAProductionReportAndViceVersa() {
        Instant completed = Instant.parse("2026-10-15T18:00:00Z");
        service.repository.update(state -> {
            UUID demo = activatedWallet(state, "organic", "DEMO", completed.minusSeconds(60), completed);
            activatedWallet(state, "organic", null, completed.minusSeconds(60), completed);
            state.events.add(new Event(UUID.randomUUID(), demo, EventType.WALLET_ACTIVITY, null, completed, "DEMO"));
            state.expenses.put("expense", new Expense(UUID.randomUUID(), YearMonth.of(2026, 10), "organic", 500, completed, "DEMO"));
            return null;
        });
        assertEquals(2, service.monthly("2026-10", "organic").activatedWallets());
        service.runtime = new RuntimeMode("PRODUCTION");
        var production = service.monthly("2026-10", "organic");
        assertEquals("PRODUCTION", production.dataMode());
        assertEquals(0, production.activatedWallets());
        assertNull(production.acquisitionSpendCents());
        assertEquals(0, service.events(null, 0, 100).total());
    }

    @Test
    void filtersAttributionByChannelAndKeepsCacUnavailableWhenThereAreNoAcquisitions() {
        service.cacSourceApproved = true;
        service.repository.update(state -> {
            state.expenses.put("expense", new Expense(UUID.randomUUID(), YearMonth.of(2026, 10), "referral", 2000, Instant.now()));
            return null;
        });
        assertNull(service.monthly("2026-10", "organic").acquisitionSpendCents());
        var referral = service.monthly("2026-10", "referral");
        assertTrue(referral.acquisitionSpendRecorded());
        assertEquals(2000L, referral.acquisitionSpendCents());
        assertFalse(referral.acquisitionCost().available());
    }
}