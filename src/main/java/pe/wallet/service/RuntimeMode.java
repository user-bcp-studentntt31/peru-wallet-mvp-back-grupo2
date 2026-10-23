package pe.wallet.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import pe.wallet.model.WalletData;

/**
 * Modo explicito de ejecucion. Solo "DEMO" habilita la verificacion simulada; cualquier otro valor
 * (incluido uno ausente o mal escrito) se trata como produccion y falla cerrado.
 */
@ApplicationScoped
public class RuntimeMode {
    @ConfigProperty(name = "wallet.mode", defaultValue = WalletData.DEMO)
    String mode;

    public RuntimeMode() {}

    public RuntimeMode(String mode) { this.mode = mode; }

    public boolean isDemo() { return WalletData.DEMO.equalsIgnoreCase(mode == null ? "" : mode.strip()); }

    public String label() { return isDemo() ? WalletData.DEMO : WalletData.PRODUCTION; }
}
