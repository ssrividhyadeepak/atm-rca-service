package com.srividhya.bankrca.rca;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes each day's input as daily-rca-input-&lt;day&gt;.json, and the most recent one also as
 * daily-rca-input.json. A later run on the same day replaces the day's file.
 */
@Component
public class RcaInputFiles {

    private static final Logger log = LoggerFactory.getLogger(RcaInputFiles.class);

    private final Path dir;
    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    public RcaInputFiles(@Value("${rca.input-dir}") Path dir) {
        this.dir = dir;
    }

    /** The input exactly as it is written and hashed. */
    public String toJson(RcaInput input) {
        return json.writeValueAsString(input);
    }

    public RcaInput fromJson(String text) {
        return json.readValue(text, RcaInput.class);
    }

    /** @return the day's file */
    public Path write(RcaInput input, String jsonText) {
        try {
            Files.createDirectories(dir);
            Path dayFile = dir.resolve("daily-rca-input-" + input.id() + ".json");
            Files.writeString(dayFile, jsonText);
            Files.writeString(dir.resolve("daily-rca-input.json"), jsonText);
            log.debug("Wrote {}", dayFile);
            return dayFile.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** SHA-256 of the input text: recorded on the report, so the report can be matched to its input. */
    public static String hash(String jsonText) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(jsonText.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
