package main.java.utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static main.java.utils.Log.logError;

public class LanguageLoader {
    private static final Properties internalProps = new Properties();
    private static final Properties userProps = new Properties();
    private static final String USER_LANG_FILE = AppPaths.getAppDataDirectory() + "/lang.properties";
    public static ResourceBundle msgBundle;

    public static void intialize() {
        try (InputStream in = LanguageLoader.class.getClassLoader()
                .getResourceAsStream("lan/lang.properties")) {
            if (in != null) {
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                internalProps.load(reader);
            } else {
                logError("Not found: lan/lang.properties", null);
            }
        } catch (Exception e) {
            logError("Lỗi khi load lan/lang.properties", e);
        }

        try {
            File file = new File(USER_LANG_FILE);
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                file.createNewFile();
                try (OutputStream out = new FileOutputStream(file)) {
                    userProps.setProperty("lang", "vi");
                    userProps.store(out, "Default language file");
                }
            } else {
                try (InputStream in = new FileInputStream(file)) {
                    Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                    userProps.load(reader);
                }
            }
            EnvConf.strLang = LanguageLoader.getCurrentLangCode();
            LanguageLoader.msgBundle = ResourceBundle.getBundle("lan.messages.messages", new Locale(EnvConf.strLang));
        } catch (Exception e) {
            logError("Error loading user language file", e);
        }
    }

    public static Map<String, String> getAllLanguages() {
        Map<String, String> langs = new LinkedHashMap<>();
        for (String key : internalProps.stringPropertyNames()) {
            if (key.startsWith("lan.")) {
                String code = key.substring(4);
                langs.put(code, internalProps.getProperty(key));
            }
        }
        return langs;
    }

    public static String getCurrentLangCode() {
        return userProps.getProperty("lang", "vi");
    }

    public static String getCurrentLangDisplay() {
        String code = getCurrentLangCode();
        return internalProps.getProperty("lan." + code, code);
    }

    public static void setCurrentLang(String langCode) {
        userProps.setProperty("lang", langCode);
        try (OutputStream out = new FileOutputStream(USER_LANG_FILE)) {
            userProps.store(out, "Updated language");
            EnvConf.strLang = langCode;
        } catch (IOException e) {
            logError("Cannot update language.", e);
        }
    }
}
