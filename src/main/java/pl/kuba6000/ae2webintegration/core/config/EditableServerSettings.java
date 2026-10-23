package pl.kuba6000.ae2webintegration.core.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.http.contract.OptionalInput;
import pl.kuba6000.ae2webintegration.core.json.JsonProjection;

/** Server-settings projection. A response wraps its published, read-only configuration; input owns a fresh instance. */
@Desugar
@JsonProjection(value = ConfigSettings.class, excludeFieldsAnnotatedWith = WebSetting.class)
public record EditableServerSettings(@NotNull ConfigSettings config) {

    /** Omitted values preserve active secrets; empty strings explicitly clear them. Never returned in responses. */
    @Desugar
    public record SecretUpdates(@OptionalInput @Nullable String discordWebhook,
        @OptionalInput @Nullable String ntfyPassword) {}

}
