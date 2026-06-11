package cn.like.rag.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class JsonFileStore {

    private final ObjectMapper objectMapper;

    public JsonFileStore() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public <T> T read(Path path, Class<T> type) {
        if (Files.notExists(path)) {
            return null;
        }
        try {
            return objectMapper.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read json file: " + path, e);
        }
    }

    public void write(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            objectMapper.writeValue(temp.toFile(), value);
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write json file: " + path, e);
        }
    }
}
