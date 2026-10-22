package pl.kuba6000.ae2webintegration.core.notification;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.AWTError;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.notification.destination.discord.DiscordDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.ntfy.NtfyDestination;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.StatusMessage;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

class DiscordNotificationTest {

    private final BlockingQueue<Upload> uploads = new LinkedBlockingQueue<>();
    private HttpServer server;
    private ConfigTestFixture config;

    @BeforeEach
    void startLoopbackWebhook() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = exchange.getRequestBody()
                .read(buffer)) != -1) bytes.write(buffer, 0, count);
            assertTrue(
                uploads.offer(
                    new Upload(
                        exchange.getRequestHeaders()
                            .getFirst("Content-Type"),
                        exchange.getRequestHeaders()
                            .getFirst("Authorization"),
                        bytes.toByteArray())));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        config = new ConfigTestFixture();
        Config.INSTANCE.discord.webhook = "http://127.0.0.1:" + server.getAddress()
            .getPort() + "/webhook";
    }

    @AfterEach
    void stopLoopbackWebhook() {
        config.close();
        server.stop(0);
    }

    @Test
    void craftingNotificationUploadsADecodablePngWithoutAnEmbedByDefault() throws Exception {
        Config.INSTANCE.discord.roleId = "123456789";
        new DiscordDestination().sendNotification(crafting("Powered Rail", false));
        Upload upload = upload();
        assertTrue(upload.contentType.startsWith("multipart/form-data; boundary="), upload.contentType);
        String boundary = upload.contentType.substring(upload.contentType.indexOf("boundary=") + 9);
        String body = new String(upload.body, StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("name=\"payload_json\""));
        assertTrue(body.contains("name=\"files[0]\"; filename=\"crafting.png\""));
        int jsonStart = body.indexOf("\r\n\r\n") + 4;
        String jsonText = body.substring(jsonStart, body.indexOf("\r\n--" + boundary, jsonStart));
        JsonObject payload = parse(jsonText.getBytes(StandardCharsets.ISO_8859_1));
        assertFalse(payload.has("embeds"));
        assertEquals(
            "<@&123456789>",
            payload.get("content")
                .getAsString());
        JsonObject attachment = payload.getAsJsonArray("attachments")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            0,
            attachment.get("id")
                .getAsInt());
        assertEquals(
            "crafting.png",
            attachment.get("filename")
                .getAsString());
        int imageStart = body.indexOf("\r\n\r\n", body.indexOf("name=\"files[0]\"")) + 4;
        int imageEnd = body.indexOf("\r\n--" + boundary, imageStart);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(upload.body, imageStart, imageEnd - imageStart));
        assertNotNull(image);
        assertTrue(image.getWidth() >= 600 && image.getWidth() <= 1200);
        assertTrue(image.getHeight() >= 200 && image.getHeight() <= 500);
    }

    @ParameterizedTest
    @CsvSource({ "true, true, true", "true, true, false", "true, false, true", "true, false, false",
        "false, true, true", "false, true, false", "false, false, true", "false, false, false" })
    void craftingImageOptionsPreserveMentionsAndOnlyImageModeOmitsTheEmbed(boolean generateImage, boolean imageInEmbed,
        boolean mentionRole) throws Exception {
        String webhook = Config.INSTANCE.discord.webhook;
        config.set("discord.generate_image", generateImage);
        config.set("discord.image_in_embed", imageInEmbed);
        config.set("discord.role_id", mentionRole ? "123456789" : "");
        Config.INSTANCE.discord.webhook = webhook;
        new DiscordDestination().sendNotification(crafting("Powered Rail", false));
        Upload upload = upload();
        JsonObject payload;
        if (generateImage) {
            assertTrue(upload.contentType.startsWith("multipart/form-data; boundary="));
            String boundary = upload.contentType.substring(upload.contentType.indexOf("boundary=") + 9);
            String body = new String(upload.body, StandardCharsets.ISO_8859_1);
            int jsonStart = body.indexOf("\r\n\r\n") + 4;
            payload = parse(
                body.substring(jsonStart, body.indexOf("\r\n--" + boundary, jsonStart))
                    .getBytes(StandardCharsets.ISO_8859_1));
            assertEquals(
                1,
                payload.getAsJsonArray("attachments")
                    .size());
            int imageStart = body.indexOf("\r\n\r\n", body.indexOf("name=\"files[0]\"")) + 4;
            int imageEnd = body.indexOf("\r\n--" + boundary, imageStart);
            assertNotNull(ImageIO.read(new ByteArrayInputStream(upload.body, imageStart, imageEnd - imageStart)));
        } else {
            assertEquals("application/json", upload.contentType);
            payload = parse(upload.body);
            assertEquals(
                0,
                payload.getAsJsonArray("attachments")
                    .size());
        }
        assertEquals(
            mentionRole ? "<@&123456789>" : "",
            payload.get("content")
                .getAsString());
        if (generateImage && !imageInEmbed) {
            assertFalse(payload.has("embeds"), "Image-only notifications must not repeat their details in an embed");
        } else {
            JsonObject embed = payload.getAsJsonArray("embeds")
                .get(0)
                .getAsJsonObject();
            assertEquals(generateImage, embed.has("image"));
            if (generateImage) assertEquals(
                "attachment://crafting.png",
                embed.getAsJsonObject("image")
                    .get("url")
                    .getAsString());
            assertTrue(
                embed.get("description")
                    .getAsString()
                    .contains("Powered Rail"));
        }
    }

    @Test
    void disablingImagesPersistsAndKeepsCancelledCraftingAsOrdinaryTextJson() throws Exception {
        String webhook = Config.INSTANCE.discord.webhook;
        config.set("discord.generate_image", false);
        Config.INSTANCE.discord.webhook = webhook;
        assertFalse(Config.INSTANCE.discord.generateImage);
        new DiscordDestination().sendNotification(crafting("Powered Rail", true));
        Upload upload = upload();
        assertEquals("application/json", upload.contentType);
        JsonObject embed = parse(upload.body).getAsJsonArray("embeds")
            .get(0)
            .getAsJsonObject();
        assertFalse(embed.has("image"));
        assertTrue(
            embed.get("description")
                .getAsString()
                .contains("cancelled"));
        assertTrue(
            embed.get("description")
                .getAsString()
                .contains("Assembly CPU"));
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void statusNotificationsRemainJsonWithoutImageAttachments(boolean imageInEmbed) throws Exception {
        Config.INSTANCE.discord.imageInEmbed = imageInEmbed;
        new DiscordDestination()
            .sendNotification(new StatusMessage("Server status", "Connection warning", StatusMessage.Severity.WARNING));
        Upload upload = upload();
        assertEquals("application/json", upload.contentType);
        assertFalse(
            parse(upload.body).getAsJsonArray("embeds")
                .get(0)
                .getAsJsonObject()
                .has("image"));
    }

    @ParameterizedTest
    @CsvSource({ "font, true", "font, false", "java2d, true", "java2d, false", "awt, true", "awt, false" })
    void unavailableGraphicsStillDeliversTextAndFurtherNotifications(String failureMode, boolean imageInEmbed)
        throws Exception {
        Config.INSTANCE.discord.imageInEmbed = imageInEmbed;
        URL classes = DiscordDestination.class.getProtectionDomain()
            .getCodeSource()
            .getLocation();
        // Exercise missing packaged resources and native desktop linkage in an isolated runtime.
        try (URLClassLoader loader = new URLClassLoader(new URL[] { classes }, getClass().getClassLoader()) {

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (failureMode.equals("java2d") && name.startsWith("java.awt."))
                    throw new NoClassDefFoundError("Java2D unavailable");
                if (!name.startsWith("pl.kuba6000.ae2webintegration.core.notification.destination.discord."))
                    return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                if (failureMode.equals("awt")) throw new AWTError("Cannot connect to graphics environment");
                return name.endsWith(".ttf") ? null : super.getResourceAsStream(name);
            }
        }) {
            Class<?> destination = loader.loadClass(DiscordDestination.class.getName());
            Object sender = destination.getConstructor()
                .newInstance();
            destination.getMethod("sendNotification", IMessage.class)
                .invoke(sender, crafting("Powered Rail", false));
            Upload upload = upload();
            assertEquals("application/json", upload.contentType);
            JsonObject embed = parse(upload.body).getAsJsonArray("embeds")
                .get(0)
                .getAsJsonObject();
            assertFalse(embed.has("image"));
            assertTrue(
                embed.get("description")
                    .getAsString()
                    .contains("Powered Rail"));
            destination.getMethod("sendNotification", IMessage.class)
                .invoke(sender, new StatusMessage("Still running", "Second notification", StatusMessage.Severity.NONE));
            assertEquals(
                "Still running",
                parse(upload().body).getAsJsonArray("embeds")
                    .get(0)
                    .getAsJsonObject()
                    .get("title")
                    .getAsString());
        }
    }

    @ParameterizedTest
    @CsvSource({ "true, 0", "true, 1", "true, 2", "false, 0", "false, 1", "false, 2" })
    void bothDestinationsPresentTheKnownRequestersWithoutGuessingUnknownOnes(boolean discord, int count)
        throws Exception {
        Config.INSTANCE.discord.generateImage = false;
        Config.INSTANCE.ntfy.host = Config.INSTANCE.discord.webhook;
        Config.INSTANCE.ntfy.topic = "crafting-test";
        List<PlayerIdentity> requesters = new ArrayList<>();
        if (count >= 1) requesters.add(new PlayerIdentity(new UUID(0, 1), "Alice"));
        if (count >= 2) requesters.add(new PlayerIdentity(new UUID(0, 2), "Bob"));
        CraftingMessage base = crafting("Powered Rail", false);
        CraftingMessage message = new CraftingMessage(
            base.gridName(),
            base.cpuName(),
            base.output(),
            base.durationString(),
            base.wasCancelled(),
            requesters);
        if (discord) new DiscordDestination().sendNotification(message);
        else new NtfyDestination().sendNotification(message);
        JsonObject payload = parse(upload().body);
        String text = discord ? payload.getAsJsonArray("embeds")
            .get(0)
            .getAsJsonObject()
            .get("description")
            .getAsString()
            : payload.get("message")
                .getAsString();
        if (count == 0) assertTrue(text.contains("Unknown"), text);
        else {
            assertTrue(text.contains("Alice"), text);
            assertFalse(text.contains("Unknown"), text);
            if (count >= 2) assertTrue(text.contains("Bob"), text);
        }
        assertFalse(text.contains("Admin"), text);
        assertFalse(text.contains("Automation"), text);
    }

    @Test
    void longNamesStayWithinDiscordEmbedLimitsAndRetainTheNetworkName() throws Exception {
        Config.INSTANCE.discord.generateImage = false;
        new DiscordDestination()
            .sendNotification(crafting(String.join("", Collections.nCopies(5000, "Long name")), false));
        JsonObject embed = parse(upload().body).getAsJsonArray("embeds")
            .get(0)
            .getAsJsonObject();
        assertTrue(
            embed.get("title")
                .getAsString()
                .length() <= 256);
        assertTrue(
            embed.get("description")
                .getAsString()
                .length() <= 4096);
        assertTrue(
            embed.get("title")
                .getAsString()
                .contains("Łódź"));
    }

    @Test
    void ntfyKeepsTopicAuthenticationAndResourceTextWithoutDiscordAttachments() throws Exception {
        Config.INSTANCE.ntfy.host = Config.INSTANCE.discord.webhook;
        Config.INSTANCE.ntfy.topic = "crafting-test";
        Config.INSTANCE.ntfy.user = "user";
        Config.INSTANCE.ntfy.password = "test-password";
        new NtfyDestination().sendNotification(crafting("Powered Rail", false));
        Upload upload = upload();
        assertEquals("application/json", upload.contentType);
        assertEquals("Basic dXNlcjp0ZXN0LXBhc3N3b3Jk", upload.authorization);
        JsonObject payload = parse(upload.body);
        assertEquals(
            "crafting-test",
            payload.get("topic")
                .getAsString());
        assertTrue(
            payload.get("message")
                .getAsString()
                .contains("Powered Rail x4096"));
        assertFalse(payload.has("attachments"));
    }

    private Upload upload() throws InterruptedException {
        Upload upload = uploads.poll(5, TimeUnit.SECONDS);
        assertNotNull(upload, "A notification must reach the loopback HTTP destination");
        return upload;
    }

    private static CraftingMessage crafting(String name, boolean cancelled) {
        return new CraftingMessage(
            "Factory \"A\" – Łódź",
            "Assembly CPU",
            new ResourceSnapshot(
                new ResourceDescriptor("minecraft", "rail", 0, 0, name, ResourceType.ITEM, null, null),
                4096),
            "16m 42s",
            cancelled,
            java.util.Collections.emptyList());
    }

    private static JsonObject parse(byte[] bytes) {
        return new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8))
            .getAsJsonObject();
    }

    private static final class Upload {

        final String contentType;
        final String authorization;
        final byte[] body;

        Upload(String contentType, String authorization, byte[] body) {
            this.contentType = contentType;
            this.authorization = authorization;
            this.body = body;
        }
    }
}
