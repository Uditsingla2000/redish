package dev.redish;

import dev.redish.store.EvictionPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class Config {

    private final Properties props = new Properties();

    public Config(String[] args) throws IOException {
        Path configPath = Paths.get("redish.conf");
        if (Files.exists(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(in);
            }
        }
        parseFlags(args);
    }

    private void parseFlags(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--aof" -> props.setProperty("aof.enabled", "true");
                case "--aof-fsync" -> {
                    if (i + 1 < args.length) props.setProperty("aof.fsync", args[++i]);
                }
                case "--aof-no-recover" -> props.setProperty("aof.recover-on-startup", "false");
                case "--aof-dir" -> {
                    if (i + 1 < args.length) props.setProperty("aof.dir", args[++i]);
                }
                case "--maxkeys" -> {
                    if (i + 1 < args.length) props.setProperty("maxkeys", args[++i]);
                }
                case "--eviction-policy" -> {
                    if (i + 1 < args.length) props.setProperty("eviction-policy", args[++i]);
                }
            }
        }
    }

    public boolean isAofEnabled() {
        return "true".equals(props.getProperty("aof.enabled", "false"));
    }

    public String getAofFsync() {
        return props.getProperty("aof.fsync", "everysec");
    }

    public boolean isAofRecoverOnStartup() {
        return "true".equals(props.getProperty("aof.recover-on-startup", "true"));
    }

    public Path getAofPath() {
        String dir = props.getProperty("aof.dir", "data");
        return Paths.get(dir, "appendonly.aof");
    }

    public long getRewritePercentage() {
        String val = props.getProperty("aof.rewrite.percentage", "100");
        return Long.parseLong(val);
    }

    public long getRewriteMinSize() {
        String val = props.getProperty("aof.rewrite.min-size", "67108864");
        return Long.parseLong(val);
    }

    public int getMaxKeys() {
        String val = props.getProperty("maxkeys", "0");
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return 0; }
    }

    public EvictionPolicy getEvictionPolicy() {
        return EvictionPolicy.fromString(props.getProperty("eviction-policy", "noeviction"));
    }
}
