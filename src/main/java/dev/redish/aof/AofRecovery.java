package dev.redish.aof;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import dev.redish.command.CommandRegistry;
import dev.redish.resp.RespParser;
import dev.redish.store.Store;

public class AofRecovery {

    public static void recover(Path aofPath, Store store, CommandRegistry registry) throws IOException {
        try (InputStream in = new FileInputStream(aofPath.toFile())) {
            while (true) {
                Object parsed;
                try {
                    parsed = RespParser.parse(in);
                } catch (Exception e) {
                    break;
                }
                if (parsed == null) break;
                if (parsed instanceof List<?> args && !args.isEmpty()) {
                    String cmdName = ((String) args.get(0)).toUpperCase();
                    registry.get(cmdName).execute((List<Object>) args);
                }
            }
        } catch (EOFException e) {
            // truncated file — stop recovery
        }
    }
}
