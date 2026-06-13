package dev.redish;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Log {

    private static final Path LOG_DIR = Paths.get("logs");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static Path currentFile;
    private static String currentDate;

    public static void info(String msg) {
        String line = "[" + LocalDateTime.now().format(TIMESTAMP) + "] " + msg;
        System.out.println(line);
        writeFile(line);
    }

    private static void writeFile(String line) {
        try {
            Path file = logFile();
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[LOG] Failed to write: " + e.getMessage());
        }
    }

    private static Path logFile() {
        String today = LocalDate.now().format(FILE_DATE);
        if (!today.equals(currentDate)) {
            currentDate = today;
            currentFile = LOG_DIR.resolve(today + ".log");
        }
        return currentFile;
    }
}
