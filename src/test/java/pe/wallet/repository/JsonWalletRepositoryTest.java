package pe.wallet.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JsonWalletRepositoryTest {
    @TempDir Path directory;

    private JsonWalletRepository repository() {
        return new JsonWalletRepository(directory.resolve("wallets.json"), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void writesJsonAndReadsItAfterRestart() {
        UUID walletId = UUID.randomUUID();
        repository().update(state -> state.registrations.put("request-hash", walletId));
        UUID persisted = repository().read(state -> state.registrations.get("request-hash"));
        assertEquals(walletId, persisted);
    }

    @Test
    void corruptFileIsNotReplacedWithEmptyData() throws Exception {
        Path file = directory.resolve("wallets.json");
        Files.writeString(file, "not valid JSON");
        assertThrows(JsonWalletRepository.StorageException.class, () -> repository().update(state -> true));
        assertEquals("not valid JSON", Files.readString(file));
    }

    @Test
    void exceptionAbortsTheWholeUpdate() {
        UUID previous = UUID.randomUUID();
        repository().update(state -> state.registrations.put("previous", previous));
        assertThrows(IllegalStateException.class, () -> repository().update(state -> {
            state.registrations.put("request-hash", UUID.randomUUID());
            throw new IllegalStateException("abort");
        }));
        assertEquals(previous, repository().read(state -> state.registrations.get("previous")));
        int count = repository().read(state -> state.registrations.size());
        assertEquals(1, count);
    }

    @Test
    void incompleteJsonSchemaIsRejectedWithoutDataLoss() throws Exception {
        Path file = directory.resolve("wallets.json");
        Files.writeString(file, "{}");
        assertThrows(JsonWalletRepository.StorageException.class, () -> repository().update(state -> true));
        assertEquals("{}", Files.readString(file));
    }

    @Test
    void migratesVersion1ExpensesFromDecimalPenToIntegerCents() throws Exception {
        Path file = directory.resolve("wallets.json");
        Files.writeString(file, """
                {"schemaVersion":1,"wallets":{},"registrations":{},"events":[],"activities":{},
                 "expenses":{"k":{"id":"%s","month":"2026-10","channel":"organic","amountPen":10.55,"recordedAt":"2026-10-01T00:00:00Z"}}}
                """.formatted(UUID.randomUUID()));
        long cents = repository().update(state -> state.expenses.get("k").amountCents());
        assertEquals(1055L, cents);
        String saved = Files.readString(file);
        assertTrue(saved.contains("\"schemaVersion\" : 2"));
        assertFalse(saved.contains("amountPen"));
        assertEquals(1055L, (long) repository().read(state -> state.expenses.get("k").amountCents()));
    }

    @Test
    void unsupportedSchemaVersionIsRejectedWithoutDataLoss() throws Exception {
        Path file = directory.resolve("wallets.json");
        String content = "{\"schemaVersion\":99,\"wallets\":{},\"registrations\":{},\"events\":[],\"expenses\":{},\"activities\":{}}";
        Files.writeString(file, content);
        assertThrows(JsonWalletRepository.StorageException.class, () -> repository().update(state -> true));
        assertEquals(content, Files.readString(file));
    }

    @Test
    void serializesConcurrentUpdatesInTheSameInstance() throws Exception {
        JsonWalletRepository repository = repository();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var operations = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 12; index++) {
                String key = "request-" + index;
                operations.add(executor.submit(() -> repository.update(state -> state.registrations.put(key, UUID.randomUUID()))));
            }
            for (var operation : operations) { operation.get(); }
        }
        int count = repository.read(state -> state.registrations.size());
        assertEquals(12, count);
    }
}