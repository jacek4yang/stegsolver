package io.github.jacek4yang.stegsolver.ui;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;

/**
 * Dark / light / system theming.
 *
 * <p>The themes are ordinary JavaFX stylesheets that override the looked up colours of the default
 * (Modena) theme, so every control, including ones created later, follows the theme without extra
 * bookkeeping.</p>
 */
public final class ThemeManager {

    /** The available themes. */
    public enum Theme {
        SYSTEM("System"),
        LIGHT("Light"),
        DARK("Dark");

        private final String label;

        Theme(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final String LIGHT_STYLESHEET = "/io/github/jacek4yang/stegsolver/ui/theme/light.css";
    private static final String DARK_STYLESHEET = "/io/github/jacek4yang/stegsolver/ui/theme/dark.css";

    private static Boolean systemPrefersDark;

    private final Scene scene;
    private Theme theme = Theme.SYSTEM;
    private boolean dark;

    private final java.util.List<java.util.function.Consumer<Boolean>> darkChangeListeners = new java.util.ArrayList<>();

    public ThemeManager(Scene scene) {
        this.scene = scene;
    }

    public Theme theme() {
        return theme;
    }

    /** True when the resolved theme is dark; the viewport uses this for its background. */
    public boolean isDark() {
        return dark;
    }

    public void addDarkChangeListener(java.util.function.Consumer<Boolean> listener) {
        darkChangeListeners.add(listener);
        listener.accept(this.dark);
    }

    /** Applies a theme, resolving {@link Theme#SYSTEM} through the desktop's own preference. */
    public void apply(Theme requested) {
        this.theme = requested;
        boolean useDark = switch (requested) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> {
                String override = System.getProperty("stegsolver.theme");
                if ("dark".equalsIgnoreCase(override)) {
                    yield true;
                } else if ("light".equalsIgnoreCase(override)) {
                    yield false;
                }
                yield systemPrefersDark();
            }
        };
        this.dark = useDark;
        scene.getStylesheets().removeIf(stylesheet -> stylesheet.contains("/theme/light.css")
                || stylesheet.contains("/theme/dark.css"));
        String stylesheet = useDark ? DARK_STYLESHEET : LIGHT_STYLESHEET;
        var resource = ThemeManager.class.getResource(stylesheet);
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        for (var listener : darkChangeListeners) {
            listener.accept(useDark);
        }
    }

    /** Cycles dark -> light -> dark, used by the toolbar button. */
    public Theme toggle() {
        Theme next = isDark() ? Theme.LIGHT : Theme.DARK;
        apply(next);
        return next;
    }

    /** The desktop's own light/dark preference, detected once and cached. */
    public static synchronized boolean systemPrefersDark() {
        if (systemPrefersDark != null) {
            return systemPrefersDark;
        }
        boolean dark = detectSystemDark();
        systemPrefersDark = dark;
        return dark;
    }

    private static boolean detectSystemDark() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                String value = run("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme");
                if (value != null) {
                    return value.contains("0x0");
                }
                return false;
            }
            if (os.contains("linux") || os.contains("unix")) {
                String gtkTheme = System.getenv("GTK_THEME");
                if (gtkTheme != null && gtkTheme.toLowerCase(Locale.ROOT).contains("dark")) {
                    return true;
                }
                String scheme = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
                if (scheme != null && scheme.toLowerCase(Locale.ROOT).contains("dark")) {
                    return true;
                }
                String theme = run("gsettings", "get", "org.cinnamon.desktop.interface", "gtk-theme");
                if (theme != null && theme.toLowerCase(Locale.ROOT).contains("dark")) {
                    return true;
                }
                return false;
            }
        } catch (RuntimeException e) {
            // Detection is a nicety: fall back to light rather than refusing to start.
            return false;
        }
        return false;
    }

    /** Runs a short lived command and returns its output, or {@code null} when it is unavailable. */
    private static String run(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            try (var input = process.getInputStream()) {
                String output = new String(input.readNBytes(16_384), StandardCharsets.UTF_8);
                return process.exitValue() == 0 ? output.trim() : null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
