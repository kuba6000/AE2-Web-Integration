package pl.kuba6000.ae2webintegration.core.config;

import java.io.IOException;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import pl.kuba6000.ae2webintegration.core.http.contract.InputShape;
import pl.kuba6000.ae2webintegration.core.http.contract.OptionalInput;

/** Server-settings projection. A response wraps its published, read-only configuration; input owns a fresh instance. */
@Desugar
@InputShape(value = ConfigSettings.class, excludeFieldsAnnotatedWith = WebSetting.class)
public record EditableServerSettings(@NotNull ConfigSettings config) {

    /** Omitted values preserve active secrets; empty strings explicitly clear them. Never returned in responses. */
    @Desugar
    public record SecretUpdates(@OptionalInput @Nullable String discordWebhook,
        @OptionalInput @Nullable String ntfyPassword) {}

    /** Registered only for this wrapper, so ordinary configuration serialization remains unaffected. */
    public static final class Adapter extends TypeAdapter<EditableServerSettings> {

        private static final TypeAdapter<ConfigSettings> CONFIG = new GsonBuilder()
            .setExclusionStrategies(new ExclusionStrategy() {

                @Override
                public boolean shouldSkipField(FieldAttributes field) {
                    return field.getAnnotation(WebSetting.class) != null;
                }

                @Override
                public boolean shouldSkipClass(Class<?> type) {
                    return false;
                }
            })
            .serializeNulls()
            .create()
            .getAdapter(ConfigSettings.class);

        @Override
        public void write(JsonWriter output, EditableServerSettings settings) throws IOException {
            CONFIG.write(output, settings.config());
        }

        @Override
        public EditableServerSettings read(JsonReader input) throws IOException {
            return new EditableServerSettings(CONFIG.read(input));
        }
    }
}
