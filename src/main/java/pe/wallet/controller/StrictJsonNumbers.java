package pe.wallet.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

/** Un importe en centimos debe ser entero: 10.5 se rechaza en lugar de truncarse silenciosamente a 10. */
@Singleton
public class StrictJsonNumbers implements ObjectMapperCustomizer {
    @Override
    public void customize(ObjectMapper mapper) {
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
