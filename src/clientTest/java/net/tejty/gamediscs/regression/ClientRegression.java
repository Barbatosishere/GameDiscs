package net.tejty.gamediscs.regression;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec2;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.tejty.gamediscs.client.screen.GamingConsoleScreen;
import net.tejty.gamediscs.games.controls.Button;
import net.tejty.gamediscs.games.gamediscs.*;
import net.tejty.gamediscs.games.graphics.AnimatedImage;
import net.tejty.gamediscs.games.graphics.Image;
import net.tejty.gamediscs.games.graphics.Renderer;
import net.tejty.gamediscs.games.util.Game;
import net.tejty.gamediscs.games.util.GameStage;
import net.tejty.gamediscs.games.util.Particle;
import net.tejty.gamediscs.games.util.ParticleLevel;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs against a real client, without joining or creating a world. */
@Mod("gamediscs_regression")
public final class ClientRegression {
    private int ticks;
    private boolean ran;
    private int passed;
    private int failed;
    private PrintWriter report;

    public ClientRegression(IEventBus bus) {
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (ran || ++ticks < 80 || mc.font == null || mc.screen == null || mc.getOverlay() != null) return;
        ran = true;
        boolean active = mc.isWindowActive();
        try (PrintWriter output = new PrintWriter(Files.newBufferedWriter(Path.of(System.getProperty("gamediscs.regression.output"))))) {
            report = output;
            mc.setWindowActive(true);
            check("animation duration and wraparound", () -> {
                ResourceLocation texture = ResourceLocation.fromNamespaceAndPath("gamediscs", "test.png");
                for (int duration : new int[]{1, 2, 5, 20}) {
                    AnimatedImage[] images = {
                            new AnimatedImage(List.of(new Image(texture, 1, 1), new Image(texture, 1, 1), new Image(texture, 1, 1)), duration),
                            new AnimatedImage(texture, 1, 3, List.of(new Rect2i(0, 0, 1, 1), new Rect2i(0, 1, 1, 1), new Rect2i(0, 2, 1, 1)), duration),
                            new AnimatedImage(texture, 1, 3, 3, duration)
                    };
                    for (AnimatedImage image : images) {
                        for (int tick = 1; tick <= duration * 30; tick++) {
                            image.tick();
                            require(image.current() == (tick / duration) % 3, "frame advanced at tick " + tick + ", duration " + duration);
                        }
                    }
                }
            });
            check("buttons emit callbacks only on transitions", () -> {
                RecordingGame game = new RecordingGame();
                for (Button button : Button.values()) {
                    game.controls.setButton(button, false);
                    game.controls.setButton(button, true);
                    game.controls.setButton(button, true);
                    require(!game.controls.wasButtonDown(button), "first repeat must be suppressed");
                    require(game.controls.wasButtonDown(button), "subsequent repeat must be permitted");
                    game.controls.setButton(button, false);
                    game.controls.setButton(button, false);
                    require(!game.controls.isButtonDown(button), "release did not clear button");
                }
                require(game.downs == 6 && game.ups == 6, "duplicate callbacks: " + game.downs + "/" + game.ups);
            });
            check("prepare clears held buttons and pending repeat flags", () -> {
                RecordingGame game = new RecordingGame();
                for (Button button : Button.values()) game.controls.setButton(button, true);
                game.prepare();
                for (Button button : Button.values()) {
                    require(!game.controls.isButtonDown(button), "held after prepare: " + button);
                    require(game.controls.wasButtonDown(button), "pending repeat after prepare: " + button);
                }
                require(game.ups == 0, "reset must not emit release callbacks");
            });
            check("menu ignores operating system repeats", () -> {
                Fixture screen = screen();
                screen.keyPressed(83, 0, 0);
                for (int i = 0; i < 20; i++) {
                    screen.keyPressed(83, 0, 0);
                    require((int) get(screen, "selected") == 1, "selection repeated at event " + i);
                }
                require((int) get(screen, "selected") == 1, "selection repeated");
                screen.keyReleased(83, 0, 0);
                screen.keyPressed(83, 0, 0);
                require((int) get(screen, "selected") == 0, "fresh press was ignored");
                screen.removed();
            });
            check("empty menu keeps selection valid", () -> {
                GamingConsoleScreen screen = new EmptyFixture();
                screen.init(mc, 640, 480);
                screen.keyPressed(87, 0, 0);
                require((int) get(screen, "selected") == 0, "negative selection in empty menu");
                screen.removed();
            });
            check("key releases use the parent release handler", () -> {
                Fixture screen = screen();
                RecordingListener listener = new RecordingListener();
                screen.setFocused(listener);
                require(screen.keyReleased(90, 0, 0), "parent release not consumed");
                require(listener.releases == 1 && listener.presses == 0, "release dispatched as press");
                screen.keyPressed(87, 0, 0);
                require(listener.presses == 0, "handled game key leaked to parent");
                screen.removed();
            });
            check("selecting a game quarantines the selection key", () -> {
                Fixture screen = screen();
                Game menu = (Game) get(screen, "game");
                screen.keyPressed(32, 0, 0);
                RecordingGame game = (RecordingGame) get(screen, "game");
                for (int i = 0; i < 20; i++) screen.keyPressed(32, 0, 0);
                require(!menu.controls.isButtonDown(Button.BUTTON1), "old menu retained selection key");
                require(game.downs == 0, "selection repeat reached game");
                screen.keyReleased(32, 0, 0);
                require(game.ups == 0, "unmatched release reached game");
                screen.keyPressed(32, 0, 0);
                require(game.downs == 1, "fresh game press was ignored");
                screen.removed();
            });
            check("restart clears input and consumes repeated restart", () -> {
                Fixture screen = playingScreen();
                RecordingGame game = (RecordingGame) get(screen, "game");
                screen.keyPressed(65, 0, 0);
                screen.keyPressed(82, 0, 0);
                for (int i = 0; i < 20; i++) {
                    screen.keyPressed(82, 0, 0);
                    screen.keyPressed(65, 0, 0);
                }
                require(game.prepares == 2, "restart repeated " + game.prepares + " times");
                require(!game.controls.isButtonDown(Button.LEFT), "left held through restart");
                screen.keyReleased(65, 0, 0);
                screen.keyReleased(82, 0, 0);
                screen.keyPressed(65, 0, 0);
                require(game.controls.isButtonDown(Button.LEFT), "fresh press after restart ignored");
                screen.removed();
            });
            check("return to menu clears old game and consumes repeated quit", () -> {
                Fixture screen = playingScreen();
                var original = mc.screen;
                mc.setScreen(screen);
                try {
                    RecordingGame old = (RecordingGame) get(screen, "game");
                    screen.keyPressed(68, 0, 0);
                    screen.keyPressed(81, 0, 0);
                    Game menu = (Game) get(screen, "game");
                    require(menu.isEmpty() && !old.controls.isButtonDown(Button.RIGHT), "game retained input on exit");
                    for (int i = 0; i < 20; i++) screen.keyPressed(81, 0, 0);
                    require(get(screen, "game") == menu, "quit repeat changed menu");
                    require(mc.screen == screen, "quit repeat closed the menu");
                } finally {
                    mc.setScreen(original);
                }
            });
            check("removing screen silently releases controls", () -> {
                Fixture screen = playingScreen();
                RecordingGame game = (RecordingGame) get(screen, "game");
                screen.keyPressed(87, 0, 0);
                screen.removed();
                require(!game.controls.isButtonDown(Button.UP) && game.ups == 0, "removed screen retained input");
            });
            check("focus loss pauses console and recovers a missed release", () -> {
                Fixture screen = playingScreen();
                RecordingGame game = (RecordingGame) get(screen, "game");
                screen.keyPressed(87, 0, 0);
                int before = game.ticks;
                mc.setWindowActive(false);
                screen.tick();
                screen.keyPressed(87, 0, 0);
                require(game.ticks == before && !game.controls.isButtonDown(Button.UP), "input or game continued while unfocused");
                mc.setWindowActive(true);
                screen.tick(); // No physical W key is held: recover its missing release.
                screen.keyPressed(87, 0, 0);
                require(game.controls.isButtonDown(Button.UP) && game.ticks == before + 1, "focus recovery failed");
                screen.removed();
            });
            check("high Blocktris scores keep a positive update interval", () -> {
                BlocktrisGame game = new BlocktrisGame();
                for (int score : new int[]{0, 50, 400, 450, 451, 500, 1000, Integer.MAX_VALUE}) {
                    game.prepare();
                    game.score = score;
                    int expected = Math.max(1, (int) (10f / ((float) score / 50f + 1f)));
                    require(game.gameTickDuration() == expected, "invalid interval at score " + score);
                    game.start();
                    game.tick();
                }
            });
            check("particle expiry is stable and ticks each survivor exactly once", () -> {
                RecordingGame game = new RecordingGame();
                List<CountingParticle> initial = new ArrayList<>();
                for (int i = 0; i < 10000; i++) {
                    CountingParticle particle = new CountingParticle(i % 3 == 0 ? 1 : 3);
                    initial.add(particle);
                    game.addParticle(particle);
                }
                game.tick();
                List<?> survivors = (List<?>) get(game, "particles");
                require(survivors.equals(initial.stream().filter(p -> !p.isDead()).toList()), "survivors reordered or lost");
                require(initial.stream().allMatch(p -> p.updates == 1), "particles skipped or ticked twice");
                game.tick();
                game.tick();
                require(survivors.isEmpty(), "expired particles retained");
                require(initial.stream().allMatch(p -> p.updates == (p.initialLifetime == 1 ? 1 : 3)), "dead particles ticked again");
            });
            check("all seven games prepare, tick and reset their input", () -> {
                List<Game> games = List.of(new FlappyBirdGame(), new SlimeGame(), new BlocktrisGame(), new TntSweeperGame(), new PongGame(), new FroggieGame(), new RabbitGame());
                for (Game game : games) {
                    game.prepare();
                    for (int i = 0; i < 20; i++) game.tick();
                    game.controls.setButton(Button.UP, true);
                    game.prepare();
                    require(game.stage == GameStage.START && !game.controls.isButtonDown(Button.UP), "reset failed: " + game.getClass().getSimpleName());
                }
            });
            for (int size : new int[]{20000, 40000, 80000}) {
                long[] samples = new long[5];
                for (int sample = -2; sample < samples.length; sample++) {
                    RecordingGame game = new RecordingGame();
                    for (int i = 0; i < size; i++) game.addParticle(new Particle(Vec2.ZERO, new Renderer(), 1, ParticleLevel.GAME));
                    long begin = System.nanoTime();
                    game.tick();
                    long elapsed = System.nanoTime() - begin;
                    if (sample >= 0) samples[sample] = elapsed;
                }
                java.util.Arrays.sort(samples);
                report.println("PARTICLE_EXPIRY count=" + size + " median_ns=" + samples[2]);
            }
            report.println("CLIENT_REGRESSIONS passed=" + passed + " failures=" + failed);
        } catch (Exception failure) {
            failure.printStackTrace();
        } finally {
            mc.setWindowActive(active);
            mc.stop();
        }
    }

    private void check(String name, Checked test) {
        try {
            test.run();
            report.println("PASS " + name);
            passed++;
        } catch (Throwable failure) {
            report.println("FAIL " + name);
            failure.printStackTrace(report);
            failed++;
        } finally {
            Minecraft.getInstance().setWindowActive(true);
            report.flush();
        }
    }

    private static Fixture screen() {
        Fixture screen = new Fixture();
        screen.init(Minecraft.getInstance(), 640, 480);
        return screen;
    }

    private static Fixture playingScreen() {
        Fixture screen = screen();
        screen.keyPressed(32, 0, 0);
        screen.keyReleased(32, 0, 0);
        return screen;
    }

    private static Object get(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Checked { void run() throws Exception; }

    private static class Fixture extends GamingConsoleScreen {
        Fixture() { super(Component.literal("Regression fixture")); }
        @Override public List<Game> scanForGames() { return List.of(new RecordingGame(), new RecordingGame()); }
    }

    private static final class EmptyFixture extends GamingConsoleScreen {
        EmptyFixture() { super(Component.literal("Empty regression fixture")); }
        @Override public List<Game> scanForGames() { return List.of(); }
    }

    private static final class RecordingGame extends Game {
        int downs;
        int ups;
        int prepares;
        @Override public void buttonDown(Button button) { downs++; }
        @Override public void buttonUp(Button button) { ups++; }
        @Override public void prepare() { super.prepare(); prepares++; }
    }

    private static final class RecordingListener implements GuiEventListener {
        int presses;
        int releases;
        boolean focused;
        @Override public boolean keyPressed(int key, int scan, int modifiers) { presses++; return true; }
        @Override public boolean keyReleased(int key, int scan, int modifiers) { releases++; return true; }
        @Override public void setFocused(boolean value) { focused = value; }
        @Override public boolean isFocused() { return focused; }
    }

    private static final class CountingParticle extends Particle {
        final int initialLifetime;
        int updates;
        CountingParticle(int lifetime) {
            super(Vec2.ZERO, new Renderer(), lifetime, ParticleLevel.GAME);
            initialLifetime = lifetime;
        }
        @Override public void tick() { updates++; super.tick(); }
    }
}
