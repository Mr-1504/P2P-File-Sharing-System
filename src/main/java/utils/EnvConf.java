package main.java.utils;

import io.github.cdimascio.dotenv.Dotenv;

public class EnvConf {
    public static String strLang = "en";
    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

    public static String getEnv(String key, String defaultValue) {
        String value = dotenv.get(key);
        return value != null ? value : defaultValue;
    }

    public static int getEnvInt(String key, int defaultValue) {
        String value = dotenv.get(key);
        try {
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            System.err.println("[ERROR] Invalid " + key + " format: " + value + ". Using default: " + defaultValue);
            return defaultValue;
        }
    }
}
