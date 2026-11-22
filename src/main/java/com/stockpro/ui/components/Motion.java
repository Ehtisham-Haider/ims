package com.stockpro.ui.components;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.Transition;
import javafx.animation.TranslateTransition;
import javafx.collections.ListChangeListener;
import javafx.collections.SetChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Smooth color-transition engine for the StockPro UI.
 *
 * <p>JavaFX 21 has no CSS {@code transition} support, so state changes (hover, active,
 * selected) would otherwise snap instantly. This engine intercepts those changes, records the
 * colors currently on screen, lets CSS resolve the new state, and then animates background,
 * border, text and icon colors between the two using temporary inline styles. Once a transition
 * finishes, the inline styles are removed so the stylesheet is again the single source of truth.</p>
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public final class Motion {
    /** Standard duration for color transitions. */
    public static final Duration COLOR_DURATION = Duration.millis(200);
    /** Shared ease-out curve used by every StockPro animation. */
    public static final Interpolator EASE = Interpolator.SPLINE(0.25, 0.1, 0.25, 1.0);

    private static final String KEY_ANIM = "motion.anim";
    private static final String KEY_STYLE = "motion.baseStyle";
    private static final String KEY_WATCHED = "motion.watched";
    private static final String KEY_SCENE = "motion.scene";
    private static final String KEY_LAST = "motion.last";
    /** Nodes whose drawn colors are recorded each pulse (weakly held so views can be collected). */
    private static final List<WeakReference<Node>> TRACKED = new ArrayList<>();
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    /** Style classes whose hover / state changes are animated. */
    private static final Set<String> ANIMATED_CLASSES = Set.of(
            "nav-button", "filter-chip", "btn-primary", "btn-secondary", "btn-success", "btn-danger-outline",
            "icon-btn", "icon-btn-danger", "top-icon-btn", "top-pill", "avatar", "link-btn", "list-row-clickable",
            "toast-action", "btn-on-inverse", "btn-ghost-inverse", "table-row-cell", "list-cell", "tab",
            "theme-switch-btn", "button");

    private Motion() {
    }

    /**
     * Installs scene-wide hover transitions. Safe to call repeatedly for the same scene.
     *
     * @param scene scene to enhance
     */
    public static void install(Scene scene) {
        if (scene == null || scene.getProperties().containsKey(KEY_SCENE)) {
            return;
        }
        scene.getProperties().put(KEY_SCENE, Boolean.TRUE);
        // After every CSS + layout pass, record what each tracked node is about to draw.
        scene.addPostLayoutPulseListener(() -> recordDrawnColors(scene));
        scene.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, e -> onHoverChange(e.getTarget()));
        scene.addEventFilter(MouseEvent.MOUSE_EXITED_TARGET, e -> onHoverChange(e.getTarget()));
    }

    private static void onHoverChange(Object target) {
        if (target instanceof Node node && isAnimated(node)) {
            transition(node);
        }
    }

    private static boolean isAnimated(Node node) {
        for (String c : node.getStyleClass()) {
            if (ANIMATED_CLASSES.contains(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Enhances a freshly built view: animates active/selected style changes on chips, navigation
     * buttons and tab headers, and fades in tab content when the selected tab changes.
     *
     * @param root view root
     */
    public static void enhance(Parent root) {
        if (root == null) {
            return;
        }
        if (root.getScene() == null) {
            // Skins (and so tab headers / scroll content) only exist once the view is in a scene.
            root.sceneProperty().addListener(new javafx.beans.value.ChangeListener<>() {
                @Override
                public void changed(javafx.beans.value.ObservableValue<? extends Scene> obs, Scene old, Scene now) {
                    if (now != null) {
                        root.sceneProperty().removeListener(this);
                        // Run after the caller finishes attaching stylesheets to the new scene.
                        javafx.application.Platform.runLater(() -> enhance(root));
                    }
                }
            });
            return;
        }
        root.applyCss();
        for (Node n : root.lookupAll(".filter-chip")) {
            watchState(n);
        }
        for (Node n : root.lookupAll(".nav-button")) {
            watchState(n);
        }
        for (Node n : root.lookupAll(".tab-pane")) {
            if (n instanceof TabPane tp) {
                enhanceTabPane(tp);
            }
        }
    }

    /**
     * Animates a node whenever its style classes change (e.g. {@code filter-chip-active}) or its
     * {@code :selected} pseudo-class toggles.
     *
     * @param node node to watch
     */
    public static void watchState(Node node) {
        if (node == null || node.getProperties().containsKey(KEY_WATCHED)) {
            return;
        }
        node.getProperties().put(KEY_WATCHED, Boolean.TRUE);
        for (Node n : collect(node)) {
            track(n);
        }
        node.getStyleClass().addListener((ListChangeListener<String>) c -> transition(node));
        node.getPseudoClassStates().addListener((SetChangeListener<PseudoClass>) c -> {
            if (SELECTED.equals(c.getElementAdded()) || SELECTED.equals(c.getElementRemoved())) {
                transition(node);
            }
        });
    }

    /**
     * Registers a node whose drawn colors are recorded after every layout pulse.
     *
     * <p>When a node's style classes change, JavaFX immediately resets its background, border
     * and text fill to defaults (before any listener can read them). Transitions triggered by a
     * class change therefore start from the colors recorded at the last drawn frame instead.</p>
     *
     * @param n node to track
     */
    private static void track(Node n) {
        if (n.getProperties().containsKey(KEY_LAST)) {
            return;
        }
        n.getProperties().put(KEY_LAST, Snapshot.of(n));
        TRACKED.add(new WeakReference<>(n));
    }

    private static void recordDrawnColors(Scene scene) {
        Iterator<WeakReference<Node>> it = TRACKED.iterator();
        while (it.hasNext()) {
            Node n = it.next().get();
            if (n == null) {
                it.remove();
            } else if (n.getScene() == scene) {
                n.getProperties().put(KEY_LAST, Snapshot.of(n));
            }
        }
    }

    private static void enhanceTabPane(TabPane tabPane) {
        if (tabPane.getProperties().containsKey(KEY_WATCHED)) {
            return;
        }
        tabPane.getProperties().put(KEY_WATCHED, Boolean.TRUE);
        for (Node header : tabPane.lookupAll(".tab")) {
            watchState(header);
        }
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            for (Node header : tabPane.lookupAll(".tab")) {
                watchState(header);
            }
            revealTabContent(newTab);
        });
    }

    private static void revealTabContent(Tab tab) {
        if (tab == null || tab.getContent() == null) {
            return;
        }
        reveal(tab.getContent(), 8);
    }

    /**
     * Fades a node in while sliding it up by a few pixels.
     *
     * @param node node to reveal
     * @param offsetY starting vertical offset in pixels
     */
    public static void reveal(Node node, double offsetY) {
        node.setOpacity(0);
        node.setTranslateY(offsetY);
        FadeTransition fade = new FadeTransition(Duration.millis(260), node);
        fade.setToValue(1);
        fade.setInterpolator(EASE);
        TranslateTransition slide = new TranslateTransition(Duration.millis(300), node);
        slide.setToY(0);
        slide.setInterpolator(EASE);
        new ParallelTransition(fade, slide).play();
    }

    // ---------------------------------------------------------------------------------------------
    // Color transition core
    // ---------------------------------------------------------------------------------------------

    /**
     * Smoothly transitions a node (and any descendants whose colors change) from the colors
     * currently on screen to the colors its pending CSS state resolves to.
     *
     * @param node node whose state has just changed (before the next CSS pass)
     */
    public static void transition(Node node) {
        if (node.getScene() == null) {
            return;
        }
        List<Node> nodes = collect(node);
        List<Snapshot> before = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            // Prefer the colors recorded at the last drawn frame: JavaFX may already have reset
            // the live values if this change came from a style-class switch.
            before.add(n.getProperties().get(KEY_LAST) instanceof Snapshot drawn ? drawn : Snapshot.of(n));
        }
        // Drop any in-flight overrides so CSS resolves the true target state.
        for (Node n : nodes) {
            stopAndRestore(n);
        }
        node.applyCss();

        List<Node> animated = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            Snapshot from = before.get(i);
            Snapshot to = Snapshot.of(n);
            if (from.equals(to)) {
                continue;
            }
            n.getProperties().put(KEY_STYLE, n.getStyle() == null ? "" : n.getStyle());
            n.getProperties().put(KEY_ANIM, new Pair(from, to));
            animated.add(n);
        }
        if (animated.isEmpty()) {
            return;
        }

        Transition anim = new Transition() {
            {
                setCycleDuration(COLOR_DURATION);
                setInterpolator(EASE);
            }

            @Override
            protected void interpolate(double frac) {
                for (Node n : animated) {
                    Object o = n.getProperties().get(KEY_ANIM);
                    if (o instanceof Pair p) {
                        applyInline(n, p.from().blend(p.to(), frac));
                    }
                }
            }
        };
        anim.setOnFinished(e -> {
            for (Node n : animated) {
                if (n.getProperties().get(KEY_ANIM) instanceof Pair) {
                    restore(n);
                }
            }
        });
        for (Node n : animated) {
            n.getProperties().put(KEY_ANIM + ".t", anim);
        }
        // Paint the starting colors before the first pulse so nothing flickers.
        for (Node n : animated) {
            Pair p = (Pair) n.getProperties().get(KEY_ANIM);
            applyInline(n, p.from());
        }
        anim.play();
    }

    private static List<Node> collect(Node node) {
        List<Node> out = new ArrayList<>();
        out.add(node);
        if (node instanceof Parent p) {
            for (Node d : p.lookupAll("*")) {
                if (d != node && (d instanceof Region || d instanceof Labeled || d instanceof FontIcon)) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    private static void stopAndRestore(Node n) {
        Object t = n.getProperties().get(KEY_ANIM + ".t");
        if (t instanceof Transition running) {
            running.stop();
        }
        if (n.getProperties().containsKey(KEY_STYLE)) {
            restore(n);
        }
    }

    private static void restore(Node n) {
        Object base = n.getProperties().remove(KEY_STYLE);
        n.getProperties().remove(KEY_ANIM);
        n.getProperties().remove(KEY_ANIM + ".t");
        n.setStyle(base instanceof String s ? s : "");
    }

    private static void applyInline(Node n, Snapshot s) {
        String base = n.getProperties().get(KEY_STYLE) instanceof String b ? b : "";
        StringBuilder sb = new StringBuilder(base);
        if (!base.isBlank() && !base.trim().endsWith(";")) {
            sb.append(';');
        }
        if (s.bg() != null) {
            sb.append("-fx-background-color:").append(css(s.bg())).append(';');
        }
        if (s.border() != null) {
            sb.append("-fx-border-color:").append(css(s.border())).append(';');
        }
        if (s.text() != null) {
            sb.append("-fx-text-fill:").append(css(s.text())).append(';');
        }
        if (s.icon() != null) {
            sb.append("-fx-icon-color:").append(css(s.icon())).append(';');
        }
        n.setStyle(sb.toString());
    }

    private static String css(Color c) {
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255), c.getOpacity());
    }

    /** Pair of start/end color snapshots for one node. */
    private record Pair(Snapshot from, Snapshot to) {
    }

    /**
     * Colors of one node at a point in time; {@code null} components are not animated.
     *
     * @param bg single background fill color
     * @param border uniform border color
     * @param text label text fill
     * @param icon font icon color
     */
    private record Snapshot(Color bg, Color border, Color text, Color icon) {
        static Snapshot of(Node n) {
            Color bg = null;
            Color border = null;
            Color text = null;
            Color icon = null;
            if (n instanceof Region r) {
                bg = solidFill(r.getBackground());
                border = uniformStroke(r.getBorder());
            }
            if (n instanceof Labeled l && l.getTextFill() instanceof Color c) {
                text = c;
            }
            if (n instanceof FontIcon fi) {
                Paint p = fi.getIconColor();
                if (p instanceof Color c) {
                    icon = c;
                }
            }
            return new Snapshot(bg, border, text, icon);
        }

        static Color solidFill(Background b) {
            if (b != null && b.getFills().size() == 1 && b.getFills().get(0).getFill() instanceof Color c) {
                return c;
            }
            return null;
        }

        static Color uniformStroke(Border bd) {
            if (bd != null && bd.getStrokes().size() == 1) {
                BorderStroke s = bd.getStrokes().get(0);
                if (s.getTopStroke() instanceof Color c && c.equals(s.getBottomStroke())
                        && c.equals(s.getLeftStroke()) && c.equals(s.getRightStroke())) {
                    return c;
                }
            }
            return null;
        }

        /**
         * Interpolates every component toward {@code to}; components missing on either side are
         * dropped so CSS keeps control of them.
         */
        Snapshot blend(Snapshot to, double t) {
            return new Snapshot(mix(bg, to.bg, t), mix(border, to.border, t), mix(text, to.text, t), mix(icon, to.icon, t));
        }

        private static Color mix(Color a, Color b, double t) {
            if (a == null || b == null) {
                return null;
            }
            // Fade transparent ends through the other color's hue instead of through grey/black.
            Color from = a.getOpacity() == 0 ? Color.color(b.getRed(), b.getGreen(), b.getBlue(), 0) : a;
            Color to = b.getOpacity() == 0 ? Color.color(a.getRed(), a.getGreen(), a.getBlue(), 0) : b;
            return from.interpolate(to, t);
        }
    }
}
