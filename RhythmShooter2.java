import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Synthesizer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class RhythmShooter2 extends Application {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final double BASE_PLAYER_SPEED = 200.0; // Base pixels per second
    private static final double SPEED_PER_STREAK = 15.0; // Speed increase per streak level
    private static final int NUM_STARS = 50; // Number of background stars

    private Pane root;
    private Pane backgroundPane;
    private Player player;
    private List<Bullet> bullets = new ArrayList<>();
    private List<Enemy> enemies = new ArrayList<>();
    private List<NoteOffTask> noteOffTasks = new ArrayList<>();
    private List<Star> stars = new ArrayList<>();
    private Rectangle metronomeIndicator;

    private static final Random rand = new Random();

    private long lastBeatTime = 0;
    private long beatIntervalMs = 1000; // Start with 60 BPM (1000ms per beat)
    private double toleranceMs = 100; // Tolerance for "in beat" (100ms before/after beat)

    private int score = 0;
    private int streak = 0;
    private int health = 10; // Start with 10 lives
    private int highScore = 0;
    private double enemySpeed = 1.0;
    private double spawnProbability = 0.02; // Chance to spawn enemy per frame

    private Text scoreText;
    private Text streakText;
    private Text healthText;
    private Text gameOverText;
    private Text highScoreText;
    private Text retryText;

    private Synthesizer synth;
    private MidiChannel[] channels;

    // Key state tracking for smooth movement
    private boolean leftPressed = false;
    private boolean rightPressed = false;

    private boolean gameOver = false;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        root = new Pane();
        backgroundPane = new Pane();
        root.getChildren().add(backgroundPane); // Add background pane first

        // Set gradient background
        LinearGradient gradient = new LinearGradient(
                0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.DARKBLUE),
                new Stop(1, Color.BLACK)
        );
        Scene scene = new Scene(root, WIDTH, HEIGHT, gradient);
        primaryStage.setTitle("Rhythm Shooter");
        primaryStage.setScene(scene);
        primaryStage.show();

        // Initialize background stars
        initializeStars();

        initializeMidi();

        player = new Player(WIDTH / 2 - 25, HEIGHT - 50);
        root.getChildren().add(player.shape);

        // Visual metronome indicator
        metronomeIndicator = new Rectangle(WIDTH / 2 - 20, 50, 40, 20);
        metronomeIndicator.setFill(Color.CYAN);
        root.getChildren().add(metronomeIndicator);

        // Background for text readability
        Rectangle textBackground = new Rectangle(WIDTH - 160, 10, 150, 90);
        textBackground.setFill(Color.BLACK);
        textBackground.setOpacity(0.5);
        root.getChildren().add(textBackground);

        // Score, streak, and health text
        scoreText = new Text(WIDTH - 150, 30, "Score: 0");
        scoreText.setFill(Color.YELLOW);
        root.getChildren().add(scoreText);

        streakText = new Text(WIDTH - 150, 50, "Streak: 0");
        streakText.setFill(Color.YELLOW);
        root.getChildren().add(streakText);

        healthText = new Text(WIDTH - 150, 70, "Health: 10");
        healthText.setFill(Color.YELLOW);
        root.getChildren().add(healthText);

        // Game Over text (bigger and centered)
        gameOverText = new Text(WIDTH / 2 - 150, HEIGHT / 2, "Game Over!");
        gameOverText.setFill(Color.RED);
        gameOverText.setFont(new Font(60));
        gameOverText.setVisible(false);
        root.getChildren().add(gameOverText);

        // High Score text
        highScoreText = new Text(WIDTH / 2 - 100, HEIGHT / 2 + 60, "High Score: 0");
        highScoreText.setFill(Color.RED);
        highScoreText.setFont(new Font(30));
        highScoreText.setVisible(false);
        root.getChildren().add(highScoreText);

        // Retry text
        retryText = new Text(WIDTH / 2 - 120, HEIGHT / 2 + 100, "Press R to Retry");
        retryText.setFill(Color.RED);
        retryText.setFont(new Font(20));
        retryText.setVisible(false);
        root.getChildren().add(retryText);

        // Key press and release handlers
        scene.setOnKeyPressed(event -> {
            if (gameOver) {
                if (event.getCode() == KeyCode.R) {
                    resetGame();
                }
                return;
            }
            if (event.getCode() == KeyCode.LEFT) {
                leftPressed = true;
            } else if (event.getCode() == KeyCode.RIGHT) {
                rightPressed = true;
            } else if (event.getCode() == KeyCode.SPACE) {
                Bullet bullet = new Bullet(player.x + 22.5, player.y);
                bullets.add(bullet);
                root.getChildren().add(bullet.shape);
            }
        });

        scene.setOnKeyReleased(event -> {
            if (event.getCode() == KeyCode.LEFT) {
                leftPressed = false;
            } else if (event.getCode() == KeyCode.RIGHT) {
                rightPressed = false;
            }
        });

        new AnimationTimer() {
            private long lastNanoTime = System.nanoTime();

            @Override
            public void handle(long now) {
                if (gameOver) {
                    return; // Stop updating if game over
                }

                long currentMs = now / 1_000_000;
                double deltaSeconds = (now - lastNanoTime) / 1_000_000_000.0;
                lastNanoTime = now;

                // Update player position for smooth movement
                updatePlayerPosition(deltaSeconds);

                // Update visual metronome and sound
                if (currentMs - lastBeatTime >= beatIntervalMs) {
                    updateMetronomeVisual();
                    playMetronomeTick();
                    lastBeatTime = currentMs;
                } else {
                    // Fade out metronome indicator
                    double phase = (currentMs - lastBeatTime) / (double) beatIntervalMs;
                    metronomeIndicator.setOpacity(1.0 - phase);
                }

                // Update stars
                updateStars(deltaSeconds);

                // Update game elements
                updateBullets();
                updateEnemies();
                spawnEnemies();
                checkCollisions(currentMs);
                handleNoteOffs(now);
                updateDifficulty();

                // Update UI
                scoreText.setText("Score: " + score);
                streakText.setText("Streak: " + streak);
                healthText.setText("Health: " + health);

                // Check game over
                if (health <= 0) {
                    gameOver = true;
                    if (score > highScore) {
                        highScore = score;
                    }
                    gameOverText.setVisible(true);
                    highScoreText.setText("High Score: " + highScore);
                    highScoreText.setVisible(true);
                    retryText.setVisible(true);
                }
            }
        }.start();
    }

    private void initializeStars() {
        for (int i = 0; i < NUM_STARS; i++) {
            double x = rand.nextDouble() * WIDTH;
            double y = rand.nextDouble() * HEIGHT;
            double size = 2 + rand.nextDouble() * 3; // Random size between 2 and 5
            Star star = new Star(x, y, size);
            stars.add(star);
            backgroundPane.getChildren().add(star.shape);
        }
    }

    private void updateStars(double deltaSeconds) {
        for (Star star : stars) {
            star.phase += deltaSeconds * star.speed;
            if (star.phase >= 2 * Math.PI) {
                star.phase -= 2 * Math.PI;
            }
            star.shape.setOpacity(0.5 + 0.5 * Math.sin(star.phase)); // Twinkle effect
        }
    }

    private void resetGame() {
        score = 0;
        streak = 0;
        health = 10;
        enemySpeed = 1.0;
        spawnProbability = 0.02;
        beatIntervalMs = 1000;
        toleranceMs = 100;
        lastBeatTime = 0;

        // Clear bullets and their shapes
        for (Bullet bullet : bullets) {
            root.getChildren().remove(bullet.shape);
        }
        bullets.clear();

        // Clear enemies and their shapes
        for (Enemy enemy : enemies) {
            root.getChildren().remove(enemy.shape);
        }
        enemies.clear();

        noteOffTasks.clear();

        player.x = WIDTH / 2 - 25;
        player.shape.setX(player.x);

        gameOver = false;
        gameOverText.setVisible(false);
        highScoreText.setVisible(false);
        retryText.setVisible(false);
    }

    private void initializeMidi() {
        try {
            synth = MidiSystem.getSynthesizer();
            synth.open();
            channels = synth.getChannels();

            // Set instruments for channels 0-3
            channels[0].programChange(0); // Acoustic Grand Piano
            channels[1].programChange(24); // Acoustic Guitar (nylon)
            channels[2].programChange(32); // Acoustic Bass
            channels[3].programChange(114); // Steel Drums

            // Channel 9 for percussion (metronome)
        } catch (MidiUnavailableException e) {
            System.err.println("MIDI unavailable: " + e.getMessage());
        }
    }

    private void playMetronomeTick() {
        if (channels != null) {
            channels[9].noteOn(37, 60); // Side Stick for metronome tick, quieter velocity 60
        }
    }

    private void updateMetronomeVisual() {
        metronomeIndicator.setOpacity(1.0); // Flash on beat
    }

    private void updatePlayerPosition(double deltaSeconds) {
        double effectiveSpeed = BASE_PLAYER_SPEED + (streak * SPEED_PER_STREAK); // Increase by 10 per streak level

        double moveDistance = effectiveSpeed * deltaSeconds;
        if (leftPressed && player.x > 0) {
            player.x -= moveDistance;
            if (player.x < 0) player.x = 0;
            player.shape.setX(player.x);
        }
        if (rightPressed && player.x < WIDTH - 50) {
            player.x += moveDistance;
            if (player.x > WIDTH - 50) player.x = WIDTH - 50;
            player.shape.setX(player.x);
        }
    }

    private void playEnemySound(int type, long now) {
        if (channels == null) return;

        int note = 60 + rand.nextInt(12); // Random note around middle C for variety
        channels[type].noteOn(note, 100);

        // Schedule note off after 200ms
        noteOffTasks.add(new NoteOffTask(now + 200_000_000, type, note));
    }

    private void updateBullets() {
        Iterator<Bullet> iterator = bullets.iterator();
        while (iterator.hasNext()) {
            Bullet bullet = iterator.next();
            bullet.y += bullet.dy;
            bullet.shape.setY(bullet.y);
            if (bullet.y < 0) {
                root.getChildren().remove(bullet.shape);
                iterator.remove();
            }
        }
    }

    private void updateEnemies() {
        Iterator<Enemy> iterator = enemies.iterator();
        while (iterator.hasNext()) {
            Enemy enemy = iterator.next();
            enemy.y += enemy.dy * enemySpeed;
            enemy.shape.setY(enemy.y);
            if (enemy.y > HEIGHT) {
                root.getChildren().remove(enemy.shape);
                iterator.remove();
                health--; // Decrease health on miss
                streak = 0; // Miss resets streak
            }
        }
    }

    private void spawnEnemies() {
        if (rand.nextDouble() < spawnProbability) {
            int type = rand.nextInt(4);
            Enemy enemy = new Enemy(rand.nextDouble() * (WIDTH - 30), 0, type);
            enemies.add(enemy);
            root.getChildren().add(enemy.shape);
        }
    }

    private void checkCollisions(long currentMs) {
        Iterator<Bullet> bulletIterator = bullets.iterator();
        while (bulletIterator.hasNext()) {
            Bullet bullet = bulletIterator.next();
            Iterator<Enemy> enemyIterator = enemies.iterator();
            while (enemyIterator.hasNext()) {
                Enemy enemy = enemyIterator.next();
                if (bullet.shape.getBoundsInParent().intersects(enemy.shape.getBoundsInParent())) {
                    // Hit detected
                    root.getChildren().remove(bullet.shape);
                    root.getChildren().remove(enemy.shape);
                    bulletIterator.remove();
                    enemyIterator.remove();

                    // Play sound
                    long now = System.nanoTime();
                    playEnemySound(enemy.type, now);

                    // Check if in beat
                    double phase = (currentMs - lastBeatTime) % beatIntervalMs;
                    boolean inBeat = phase <= toleranceMs || phase >= (beatIntervalMs - toleranceMs);
                    if (inBeat) {
                        streak++;
                        score += streak * 10;
                        health += 5; // Gain 5 lives per streak increment
                    } else {
                        streak = 0;
                        score += 5; // Partial score for off-beat hit
                    }

                    break; // Bullet can only hit one enemy
                }
            }
        }
    }

    private void handleNoteOffs(long now) {
        Iterator<NoteOffTask> iterator = noteOffTasks.iterator();
        while (iterator.hasNext()) {
            NoteOffTask task = iterator.next();
            if (now >= task.offTime) {
                channels[task.channel].noteOff(task.note);
                iterator.remove();
            }
        }
    }

    private void updateDifficulty() {
        // Increase difficulty every 100 score
        int level = score / 100;
        enemySpeed = 1.0 + level * 0.2;
        beatIntervalMs = Math.max(500, 1000 - level * 50); // Min 120 BPM (500ms)
        spawnProbability = 0.02 + level * 0.005;
        toleranceMs = Math.max(50, 100 - level * 5); // Tighten tolerance
    }

    static class Player {
        double x, y;
        Rectangle shape;

        Player(double x, double y) {
            this.x = x;
            this.y = y;
            shape = new Rectangle(50, 20, Color.GREEN);
            shape.setX(x);
            shape.setY(y);
        }
    }

    static class Bullet {
        double x, y;
        double dy = -5;
        Rectangle shape;

        Bullet(double x, double y) {
            this.x = x;
            this.y = y;
            shape = new Rectangle(5, 10, Color.YELLOW);
            shape.setX(x);
            shape.setY(y);
        }
    }

    static class Enemy {
        double x, y;
        double dy = 2; // Base speed
        int type;
        Rectangle shape;

        Enemy(double x, double y, int type) {
            this.x = x;
            this.y = y;
            this.type = type;
            shape = new Rectangle(30, 30, getColorForType(type));
            shape.setX(x);
            shape.setY(y);
        }

        private static Color getColorForType(int type) {
            switch (type) {
                case 0: return Color.RED; // Piano
                case 1: return Color.BLUE; // Guitar
                case 2: return Color.ORANGE; // Bass
                case 3: return Color.PURPLE; // Steel Drums
                default: return Color.GRAY;
            }
        }
    }

    static class NoteOffTask {
        long offTime;
        int channel;
        int note;

        NoteOffTask(long offTime, int channel, int note) {
            this.offTime = offTime;
            this.channel = channel;
            this.note = note;
        }
    }

    static class Star {
        double x, y;
        Rectangle shape;
        double phase;
        double speed;

        Star(double x, double y, double size) {
            this.x = x;
            this.y = y;
            this.shape = new Rectangle(x, y, size, size);
            this.shape.setFill(Color.WHITE);
            this.phase = rand.nextDouble() * 2 * Math.PI;
            this.speed = 0.5 + rand.nextDouble(); // Random twinkle speed between 0.5 and 1.5
        }
    }
}