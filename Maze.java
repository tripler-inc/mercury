import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.*;

public class Maze {
    // Multi-floor state (3 floors: 0,1,2)
    static char[][][] floors;
    static int currentFloor = 0;

    static int posI = 9; // Starting I position
    static int posJ = 1; // Starting J position
    static int directions[][] = {{-1, 0}, {0, 1}, {1, 0}, {0, -1}}; // Up, Right, Down, Left
    static int facing = 0; // 0: Up, 1: Right, 2: Down, 3: Left

    // Track path as list of "i,j" strings
    static List<String> path = new ArrayList<>();

    // UI components
    static View3DPanel view3d;
    static MazePanel topdown;
    static char[][] currentMaze;
    static JFrame frame;
    static boolean use3D = true; // default to 3D view
    static boolean showMiniMap = false; // default to showing minimap
    static boolean showStartText = true; // show "Press Start" overlay
    static boolean debug = false; // debug mode for console output
    static volatile boolean acceptingInput = false;

    // Track keys currently pressed to debounce auto-repeat
    static final java.util.Set<Integer> keysDown = new java.util.HashSet<>();

    // Animation state for smooth movement between tiles
    static volatile boolean animating = false;
    static int animFromI, animFromJ, animToI, animToJ;
    static long animStartTime = 0L;
    static int animDurationMs = 160; // animation duration in ms
    static javax.swing.Timer animTimer = null;
    
    // Key mechanics
    static int keyI = -1, keyJ = -1; // key position on current keyFloor (not stored in maze grid)
    static int keyFloor = -1;        // which floor the key is on
    static boolean hasKey = false;   // whether player is holding the key
    
    // Ladder linkage positions
    // ladderA: F0 -> F1 (up on F0, down on F1)
    // ladderB: F1 -> F2 (up on F1, down on F2)
    static int ladderA_I = -1, ladderA_J = -1;
    static int ladderB_I = -1, ladderB_J = -1;

    static double getAnimationProgress() {
        if (!animating) return 0.0;
        double t = (double)(System.currentTimeMillis() - animStartTime) / (double)animDurationMs;
        if (t < 0.0) return 0.0;
        return Math.min(1.0, t);
    }

    // Draw "Press Start" overlay on any panel
    static void drawStartOverlay(Graphics2D g, int width, int height) {
        if (!showStartText) return;
        g.setColor(new Color(0, 0, 0, 180)); // semi-transparent black
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        Font font = new Font("SansSerif", Font.BOLD, 48);
        g.setFont(font);
        String text = "Press Start";
        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int textHeight = fm.getHeight();
        g.drawString(text, (width - textWidth) / 2, (height - textHeight) / 2 + fm.getAscent());
    }

    // Maze generation options
    static int mazeRowsDefault = 21;
    static int mazeColsDefault = 21;
    static boolean allowLoops = false; // whether to allow loops in generated maze
    static double loopChance = 0.02; // chance to remove a wall and create a loop

    public static void main(String[] args) {

        // generate three floors and initialize state
        floors = generateFloors(mazeRowsDefault, mazeColsDefault, allowLoops, loopChance);
        currentFloor = 0;
        currentMaze = floors[currentFloor];
        // record initial position with floor
        path.add(currentFloor + "," + posI + "," + posJ);

        if (debug) {
            System.out.println("Generated maze: " + currentMaze.length + "x" + currentMaze[0].length + " start=" + posI + "," + posJ + " cell=" + currentMaze[posI][posJ]);
            System.out.println("Start is exit? " + (solveMaze(currentMaze, posI, posJ)));
        }

        SwingUtilities.invokeLater(() -> {
            frame = new JFrame("Maze");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLayout(new BorderLayout());

            view3d = new View3DPanel(currentMaze);
            topdown = new MazePanel(currentMaze);
            frame.add(use3D ? view3d : topdown, BorderLayout.CENTER);

            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));

            JButton startBtn = new JButton("Start");
            startBtn.addActionListener(e -> {
                acceptingInput = true;
                showStartText = false;
                startBtn.setEnabled(false);
                frame.requestFocusInWindow();
                view3d.repaint();
                topdown.repaint();
            });
            bottom.add(startBtn);

            JCheckBox view3DBox = new JCheckBox("3D View");
            view3DBox.setSelected(use3D);
            view3DBox.addActionListener(e -> {
                use3D = view3DBox.isSelected();
                frame.getContentPane().remove(use3D ? topdown : view3d);
                frame.getContentPane().add(use3D ? view3d : topdown, BorderLayout.CENTER);
                frame.revalidate();
                frame.repaint();
                frame.requestFocusInWindow();
            });
            bottom.add(view3DBox);

            JCheckBox loopsBox = new JCheckBox("Allow loops");
            loopsBox.setSelected(allowLoops);
            bottom.add(loopsBox);

            JButton regenBtn = new JButton("Regenerate");
            regenBtn.addActionListener(e -> {
                allowLoops = loopsBox.isSelected();
                floors = generateFloors(mazeRowsDefault, mazeColsDefault, allowLoops, loopChance);
                currentFloor = 0;
                currentMaze = floors[currentFloor];
                view3d.setMaze(currentMaze);
                topdown.setMaze(currentMaze);
                view3d.repaint();
                topdown.repaint();
                path.clear();
                path.add(currentFloor + "," + posI + "," + posJ);
                frame.requestFocusInWindow();
            });
            bottom.add(regenBtn);

            JLabel help = new JLabel("Use Arrow keys or WASD to move. Press Q to quit (saves path). Press M to toggle map. Toggle 3D view with the checkbox.");
            bottom.add(help);

            frame.add(bottom, BorderLayout.SOUTH);

            frame.pack();

            // initialize window to fit top-down view completely
            int mazeWidth = currentMaze[0].length * 28; // cellSize from MazePanel
            int mazeHeight = currentMaze.length * 28;
            Insets insets = frame.getInsets();
            int frameWidth = (int)((mazeWidth * 2 + insets.left + insets.right) * 1.2);
            int frameHeight = (int)((mazeHeight + bottom.getPreferredSize().height + insets.top + insets.bottom) * 1.3);
            frame.setSize(frameWidth, frameHeight);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            // Ensure frame receives key events (debounced to avoid auto-repeat)
            frame.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    int k = e.getKeyCode();
                    if (keysDown.contains(k)) return; // ignore auto-repeat
                    keysDown.add(k);
                    handleKey(e, currentMaze);
                }
                @Override public void keyReleased(KeyEvent e) {
                    keysDown.remove(e.getKeyCode());
                }
            });

            // Give focus to the frame so key events are captured
            frame.requestFocusInWindow();
        });
    }

    static void handleKey(KeyEvent e, char[][] maze) {
        int k = e.getKeyCode();

        // ignore input until user clicks Start (or presses Enter)
        if (!acceptingInput) {
            if (k == KeyEvent.VK_ENTER) {
                acceptingInput = true;
                System.out.println("Accepting input now (Enter pressed)");
            } else {
                return;
            }
        }
        boolean moved = false;
        if (k == KeyEvent.VK_LEFT || k == KeyEvent.VK_A) {

            // rotate counter-clockwise
            if (animating) {
                if (debug) System.out.println("Rotation ignored during movement");
            } else {
                facing = (facing + 3) % 4;
                view3d.repaint(); topdown.repaint();
            }
        } else if (k == KeyEvent.VK_RIGHT || k == KeyEvent.VK_D) {

            // rotate clockwise
            if (animating) {
                if (debug) System.out.println("Rotation ignored during movement");
            } else {
                facing = (facing + 1) % 4;
                view3d.repaint(); topdown.repaint();
            }
        } else if (k == KeyEvent.VK_UP || k == KeyEvent.VK_W) {
            if (use3D && showMiniMap) {
                if (debug) System.out.println("Forward blocked: mini map visible");
            } else {
                moved = moveForward(maze);
            }
        } else if (k == KeyEvent.VK_DOWN || k == KeyEvent.VK_S) {
            if (use3D && showMiniMap) {
                if (debug) System.out.println("Backward blocked: mini map visible");
            } else {
                moved = moveBackward(maze);
            }
        } else if (k == KeyEvent.VK_V) {

            // toggle view with 'V'
            use3D = !use3D;
            frame.getContentPane().remove(use3D ? topdown : view3d);
            frame.getContentPane().add(use3D ? view3d : topdown, BorderLayout.CENTER);
            frame.revalidate(); frame.repaint(); frame.requestFocusInWindow();
        } else if (k == KeyEvent.VK_M) {

            // toggle minimap with 'M'
            showMiniMap = !showMiniMap;
            view3d.repaint();
        } else if (k == KeyEvent.VK_Q) {            // confirm quit to avoid accidental exit on startup

            // run later so the originating key event isn't forwarded into the dialog
            SwingUtilities.invokeLater(() -> {
                int r = JOptionPane.showConfirmDialog(frame, "Save path and quit?", "Quit", JOptionPane.YES_NO_OPTION);
                if (r == JOptionPane.YES_OPTION) savePathAndExit();
            });
        } else if (k == KeyEvent.VK_U) {
            // Go up only if standing on an up-ladder
            if (!animating && currentMaze[posI][posJ] == 'U') {
                if (currentFloor < 2) {
                    currentFloor += 1;
                    currentMaze = floors[currentFloor];
                    view3d.setMaze(currentMaze);
                    topdown.setMaze(currentMaze);
                    view3d.repaint(); topdown.repaint();
                    path.add(currentFloor + "," + posI + "," + posJ);
                    frame.requestFocusInWindow();
                    playLadderUpSound();
                }
            }
        } else if (k == KeyEvent.VK_N) {
            // Go down only if standing on a down-ladder
            if (!animating && currentMaze[posI][posJ] == 'D') {
                if (currentFloor > 0) {
                    currentFloor -= 1;
                    currentMaze = floors[currentFloor];
                    view3d.setMaze(currentMaze);
                    topdown.setMaze(currentMaze);
                    view3d.repaint(); topdown.repaint();
                    path.add(currentFloor + "," + posI + "," + posJ);
                    frame.requestFocusInWindow();
                    playLadderDownSound();
                }
            }
        }

        // If an animation was started, finalization happens in the timer; otherwise, record and check immediately
        if (moved && !animating) {
            view3d.repaint(); topdown.repaint();

            // record step
            path.add(currentFloor + "," + posI + "," + posJ);
            if (solveMaze(maze, posI, posJ)) {
                view3d.repaint(); topdown.repaint();
                JOptionPane.showMessageDialog(frame, "Exit found!");
                savePathAndExit();
            }
        } else if (moved && animating) {
            if (debug) System.out.println("Movement started: animating...");
        }
    }

    static void savePathAndExit() {
        if (debug) {
            System.out.println("savePathAndExit invoked on thread: " + Thread.currentThread());
            new Exception("Stack trace for savePathAndExit").printStackTrace(System.out);
        }
        try {
            Path out = Path.of("maze_path.txt");
            Files.write(out, path, StandardCharsets.UTF_8);
            if (debug) System.out.println("Path saved to " + out.toAbsolutePath());
        } catch (IOException ex) {
            if (debug) System.err.println("Failed to save path: " + ex.getMessage());
        }
        System.exit(0);
    }

    static boolean solveMaze(char[][] maze, int i, int j) {
        // Player can only exit when standing on 'E' and holding the key
        return maze[i][j] == 'E' && hasKey;
    }

    // Generate a maze (single-cell passages); can optionally add loops
    // rows and cols should be odd; if even they will be adjusted up by one
    static char[][] generateMaze(int rows, int cols, boolean allowLoops, double loopChance) {
        if (rows < 3) rows = 3;
        if (cols < 3) cols = 3;
        if (rows % 2 == 0) rows++;
        if (cols % 2 == 0) cols++;

        char[][] grid = new char[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) grid[r][c] = '#';
        }

        java.util.Random rand = new java.util.Random();
        java.util.Stack<int[]> stack = new java.util.Stack<>();

        int sr = 1, sc = 1;
        grid[sr][sc] = ' ';
        stack.push(new int[]{sr, sc});

        int[][] dirs = {{-2, 0}, {2, 0}, {0, -2}, {0, 2}};

        boolean[][] visited = new boolean[rows][cols];
        visited[sr][sc] = true;

        while (!stack.isEmpty()) {
            int[] cur = stack.peek();
            int r = cur[0], c = cur[1];
            java.util.List<int[]> neighbors = new java.util.ArrayList<>();
            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1];
                if (nr > 0 && nr < rows - 1 && nc > 0 && nc < cols - 1 && !visited[nr][nc]) {
                    neighbors.add(new int[]{nr, nc});
                }
            }
            if (!neighbors.isEmpty()) {
                int[] nb = neighbors.get(rand.nextInt(neighbors.size()));
                int wr = (r + nb[0]) / 2;
                int wc = (c + nb[1]) / 2;
                grid[wr][wc] = ' ';
                grid[nb[0]][nb[1]] = ' ';
                visited[nb[0]][nb[1]] = true;
                stack.push(nb);
            } else {
                stack.pop();
            }
        }

        // optionally add loops by removing some random walls between passages
        if (allowLoops && loopChance > 0.0) {
            java.util.List<int[]> candidates = new java.util.ArrayList<>();
            for (int r = 1; r < rows - 1; r++) {
                for (int c = 1; c < cols - 1; c++) {
                    if (grid[r][c] == '#') {
                        // removable wall between two passage cells (horizontal or vertical)
                        if ((grid[r-1][c] == ' ' && grid[r+1][c] == ' ') || (grid[r][c-1] == ' ' && grid[r][c+1] == ' ')) {
                            candidates.add(new int[]{r,c});
                        }
                    }
                }
            }
            for (int[] w : candidates) {
                if (rand.nextDouble() < loopChance) {
                    grid[w[0]][w[1]] = ' ';
                }
            }
        }

        // place entrance (start) and exit on opposite borders, chosen randomly
        // find candidates on top/bottom or left/right
        boolean chooseVertical = rand.nextBoolean(); // vertical => start bottom, exit top; horizontal => start right, exit left
        java.util.List<int[]> startCandidates = new java.util.ArrayList<>();
        java.util.List<int[]> exitCandidates = new java.util.ArrayList<>();
        if (chooseVertical) {
            int rStart = rows - 2; int rExit = 1;
            for (int c = 1; c < cols - 1; c += 2) {
                if (grid[rStart][c] == ' ') startCandidates.add(new int[]{rStart, c});
                if (grid[rExit][c] == ' ') exitCandidates.add(new int[]{rExit, c});
            }
        } else {
            int cStart = cols - 2; int cExit = 1;
            for (int r = 1; r < rows - 1; r += 2) {
                if (grid[r][cStart] == ' ') startCandidates.add(new int[]{r, cStart});
                if (grid[r][cExit] == ' ') exitCandidates.add(new int[]{r, cExit});
            }
        }

        // fallback: if no candidate (rare), scan entire border for passage
        if (startCandidates.isEmpty()) {
            for (int r = 1; r < rows - 1; r += 2) for (int c = 1; c < cols - 1; c += 2) if (grid[r][c] == ' ') startCandidates.add(new int[]{r,c});
        }
        if (exitCandidates.isEmpty()) {
            for (int r = 1; r < rows - 1; r += 2) for (int c = 1; c < cols - 1; c += 2) if (grid[r][c] == ' ') exitCandidates.add(new int[]{r,c});
        }

        int[] start = startCandidates.get(rand.nextInt(startCandidates.size()));
        int[] exit = exitCandidates.get(rand.nextInt(exitCandidates.size()));

        // ensure start != exit
        if (start[0] == exit[0] && start[1] == exit[1]) {

            // pick a different exit if possible
            if (exitCandidates.size() > 1) {
                int idx;
                do { idx = rand.nextInt(exitCandidates.size()); } while (exitCandidates.get(idx)[0] == start[0] && exitCandidates.get(idx)[1] == start[1]);
                exit = exitCandidates.get(idx);
            } else {
                // find any other passage
                outer: for (int r = 1; r < rows - 1; r += 2) for (int c = 1; c < cols - 1; c += 2) if (grid[r][c] == ' ' && (r != start[0] || c != start[1])) { exit = new int[]{r,c}; break outer; }
            }
        }

        // Mark start (interior) and place exit on the outer border cell adjacent to the chosen exit passage
        grid[start[0]][start[1]] = 'O';

        // ensure the interior exit cell is a passage, then mark the outermost border cell as 'E'
        grid[exit[0]][exit[1]] = ' ';

        if (exit[0] == 1) {
            // exit at top border
            grid[0][exit[1]] = 'E';
        } else if (exit[0] == rows - 2) {
            // exit at bottom border
            grid[rows - 1][exit[1]] = 'E';
        } else if (exit[1] == 1) {
            // exit at left border
            grid[exit[0]][0] = 'E';
        } else if (exit[1] == cols - 2) {
            // exit at right border
            grid[exit[0]][cols - 1] = 'E';
        } else {
            // fallback: place exit where selected
            grid[exit[0]][exit[1]] = 'E';
        }
        posI = start[0]; posJ = start[1];
        
        // Place a key randomly on a passage cell (not start, not an exit border)
        placeKey(grid);
        
        return grid;
    }

    // Generate three floors and wire ladders/exit rules
    static char[][][] generateFloors(int rows, int cols, boolean allowLoops, double loopChance) {
        // Base mazes
        char[][] f1 = generateMaze(rows, cols, allowLoops, loopChance);
        int startI = posI, startJ = posJ; // capture start from floor1
        char[][] f2 = generateMaze(rows, cols, allowLoops, loopChance);
        char[][] f3 = generateMaze(rows, cols, allowLoops, loopChance);

        // Remove starts from floors 2/3 and any exits from floors 1/2
        for (int i = 0; i < f1.length; i++) {
            for (int j = 0; j < f1[0].length; j++) {
                if (f1[i][j] == 'E') f1[i][j] = '#';
            }
        }
        for (int i = 0; i < f2.length; i++) {
            for (int j = 0; j < f2[0].length; j++) {
                if (f2[i][j] == 'O') f2[i][j] = ' ';
                if (f2[i][j] == 'E') f2[i][j] = '#';
            }
        }
        for (int i = 0; i < f3.length; i++) {
            for (int j = 0; j < f3[0].length; j++) {
                if (f3[i][j] == 'O') f3[i][j] = ' ';
            }
        }

        // Pick ladderA on floor1 (up to floor2), ensure passable on both floors
        int[] a = pickRandomPassage(f1, startI, startJ);
        ladderA_I = a[0]; ladderA_J = a[1];
        f1[ladderA_I][ladderA_J] = 'U';
        if (f2[ladderA_I][ladderA_J] == '#') f2[ladderA_I][ladderA_J] = ' ';
        f2[ladderA_I][ladderA_J] = 'D';

        // Pick ladderB on floor2 (up to floor3), distinct from ladderA, ensure passable
        int[] b;
        do { b = pickRandomPassage(f2, -1, -1); } while (b[0] == ladderA_I && b[1] == ladderA_J);
        ladderB_I = b[0]; ladderB_J = b[1];
        f2[ladderB_I][ladderB_J] = 'U';
        if (f3[ladderB_I][ladderB_J] == '#') f3[ladderB_I][ladderB_J] = ' ';
        f3[ladderB_I][ladderB_J] = 'D';

        // Place a single key on any floor (avoid start and ladder tiles)
        placeKeyMulti(new char[][][]{f1, f2, f3}, startI, startJ);

        // Restore player start to floor1
        posI = startI; posJ = startJ;
        hasKey = false;
        return new char[][][]{f1, f2, f3};
    }

    // Helper: pick random passage cell (not equal to exclude if provided)
    static int[] pickRandomPassage(char[][] grid, int excludeI, int excludeJ) {
        java.util.Random rand = new java.util.Random();
        java.util.List<int[]> candidates = new java.util.ArrayList<>();
        for (int r = 1; r < grid.length - 1; r++) {
            for (int c = 1; c < grid[0].length - 1; c++) {
                if (grid[r][c] == ' ' && !(r == excludeI && c == excludeJ)) {
                    candidates.add(new int[]{r, c});
                }
            }
        }
        if (candidates.isEmpty()) return new int[]{1,1};
        return candidates.get(rand.nextInt(candidates.size()));
    }

    // Place key across floors; avoid start and ladder tiles
    static void placeKeyMulti(char[][][] fs, int startI, int startJ) {
        java.util.Random rand = new java.util.Random();
        keyFloor = rand.nextInt(3);
        char[][] grid = fs[keyFloor];
        java.util.List<int[]> candidates = new java.util.ArrayList<>();
        for (int r = 1; r < grid.length - 1; r++) {
            for (int c = 1; c < grid[0].length - 1; c++) {
                char ch = grid[r][c];
                boolean isLadder = (ch == 'U' || ch == 'D');
                if (ch == ' ' && !(keyFloor == 0 && r == startI && c == startJ) && !isLadder) {
                    candidates.add(new int[]{r, c});
                }
            }
        }
        if (!candidates.isEmpty()) {
            int[] chosen = candidates.get(rand.nextInt(candidates.size()));
            keyI = chosen[0]; keyJ = chosen[1];
        } else { keyI = -1; keyJ = -1; }
        hasKey = false;
    }

    // Choose a random passage cell for the key, avoiding the start position and borders
    static void placeKey(char[][] grid) {
        java.util.Random rand = new java.util.Random();
        java.util.List<int[]> candidates = new java.util.ArrayList<>();
        int rows = grid.length, cols = grid[0].length;
        for (int r = 1; r < rows - 1; r++) {
            for (int c = 1; c < cols - 1; c++) {
                if (grid[r][c] == ' ' && !(r == posI && c == posJ)) {
                    candidates.add(new int[]{r, c});
                }
            }
        }
        if (!candidates.isEmpty()) {
            int[] chosen = candidates.get(rand.nextInt(candidates.size()));
            keyI = chosen[0];
            keyJ = chosen[1];
        } else {
            keyI = -1; keyJ = -1;
        }
        hasKey = false;
    }

    // Play a short key pickup sound using Java Sound (no external files)
    static void playPickupSound() {
        new Thread(() -> {
            try {
                float sampleRate = 44100f;
                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();

                int[] freqs = {900, 1500};
                int toneMs = 8; // duration per tone
                int gapMs = 5;  // small gap between tones

                for (int f : freqs) {
                    int samples = (int) (toneMs * sampleRate / 1000);
                    byte[] buf = new byte[samples * 2]; // 16-bit PCM mono
                    for (int i = 0; i < samples; i++) {
                        double t = i / sampleRate;

                        // quick attack + decay envelope for percussive feel
                        double envAttack = Math.min(1.0, i / (samples * 0.15));
                        double envDecay = Math.pow(1.0 - (double) i / samples, 0.7);
                        double env = envAttack * envDecay;
                        double v = Math.sin(2.0 * Math.PI * f * t) * env * 0.6; // amplitude
                        short s = (short) (v * 32767);
                        buf[i * 2] = (byte) (s & 0xFF);         // little-endian
                        buf[i * 2 + 1] = (byte) ((s >>> 8) & 0xFF);
                    }
                    line.write(buf, 0, buf.length);

                    // small silence gap
                    int gapSamples = (int) (gapMs * sampleRate / 1000);
                    byte[] gap = new byte[gapSamples * 2];
                    line.write(gap, 0, gap.length);
                }

                line.drain();
                line.stop();
                line.close();
            } catch (Exception ex) {
                if (debug) System.err.println("Pickup sound failed: " + ex.getMessage());
            }
        }, "pickup-sound").start();
    }

    // Play a short exit chime when reaching the exit (no external files)
    static void playExitSound() {
        new Thread(() -> {
            try {
                float sampleRate = 44100f;
                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();

                // Play a triad chord (three notes at once) for victory
                int[] freqs = {700, 950, 1200};
                int chordMs = 300; // chord duration

                int samples = (int) (chordMs * sampleRate / 1000);
                byte[] buf = new byte[samples * 2];
                for (int i = 0; i < samples; i++) {
                    double t = i / sampleRate;

                    // Smooth envelope to avoid clicks
                    double envAttack = Math.min(1.0, i / (samples * 0.08));
                    double envDecay = Math.pow(1.0 - (double) i / samples, 0.85);
                    double env = envAttack * envDecay;

                    // Sum the three sines and normalize
                    double v = 0.0;
                    for (int f : freqs) v += Math.sin(2.0 * Math.PI * f * t);
                    v = (v / freqs.length) * 0.8 * env; // scale to avoid clipping
                    short s = (short) (v * 32767);
                    buf[i * 2] = (byte) (s & 0xFF);
                    buf[i * 2 + 1] = (byte) ((s >>> 8) & 0xFF);
                }
                line.write(buf, 0, buf.length);

                line.drain();
                line.stop();
                line.close();
            } catch (Exception ex) {
                if (debug) System.err.println("Exit sound failed: " + ex.getMessage());
            }
        }, "exit-sound").start();
    }

    // Play short ladder-up sound: low C then C# in quick succession
    static void playLadderUpSound() {
        new Thread(() -> {
            try {
                float sampleRate = 44100f;
                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();

                double[] freqs = {130.81, 138.59}; // C3 and C#3
                int toneMs = 60; // short duration per tone
                int gapMs = 6;   // small gap between tones

                for (double f : freqs) {
                    int samples = (int) (toneMs * sampleRate / 1000);
                    byte[] buf = new byte[samples * 2];
                    for (int i = 0; i < samples; i++) {
                        double t = i / sampleRate;
                        double envAttack = Math.min(1.0, i / (samples * 0.12));
                        double envDecay = Math.pow(1.0 - (double) i / samples, 0.8);
                        double env = envAttack * envDecay;
                        double v = Math.sin(2.0 * Math.PI * f * t) * env * 0.6;
                        short s = (short) (v * 32767);
                        buf[i * 2] = (byte) (s & 0xFF);
                        buf[i * 2 + 1] = (byte) ((s >>> 8) & 0xFF);
                    }
                    line.write(buf, 0, buf.length);

                    int gapSamples = (int) (gapMs * sampleRate / 1000);
                    byte[] gap = new byte[gapSamples * 2];
                    line.write(gap, 0, gap.length);
                }

                line.drain();
                line.stop();
                line.close();
            } catch (Exception ex) {
                if (debug) System.err.println("Ladder-up sound failed: " + ex.getMessage());
            }
        }, "ladder-up-sound").start();
    }

    // Play short ladder-down sound: C# then C in quick succession
    static void playLadderDownSound() {
        new Thread(() -> {
            try {
                float sampleRate = 44100f;
                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();

                double[] freqs = {98.00, 92.50}; // G2 then Gb2 (F#2)
                int toneMs = 60; // short duration per tone
                int gapMs = 6;   // small gap between tones

                for (double f : freqs) {
                    int samples = (int) (toneMs * sampleRate / 1000);
                    byte[] buf = new byte[samples * 2];
                    for (int i = 0; i < samples; i++) {
                        double t = i / sampleRate;
                        double envAttack = Math.min(1.0, i / (samples * 0.12));
                        double envDecay = Math.pow(1.0 - (double) i / samples, 0.8);
                        double env = envAttack * envDecay;
                        double v = Math.sin(2.0 * Math.PI * f * t) * env * 0.6;
                        short s = (short) (v * 32767);
                        buf[i * 2] = (byte) (s & 0xFF);
                        buf[i * 2 + 1] = (byte) ((s >>> 8) & 0xFF);
                    }
                    line.write(buf, 0, buf.length);

                    int gapSamples = (int) (gapMs * sampleRate / 1000);
                    byte[] gap = new byte[gapSamples * 2];
                    line.write(gap, 0, gap.length);
                }

                line.drain();
                line.stop();
                line.close();
            } catch (Exception ex) {
                if (debug) System.err.println("Ladder-down sound failed: " + ex.getMessage());
            }
        }, "ladder-down-sound").start();
    }

    // Play a quiet footstep thump for each movement
    static void playFootstepSound() {
        new Thread(() -> {
            try {
                float sampleRate = 44100f;
                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();

                int ms = 40; // short thump
                int samples = (int)(ms * sampleRate / 1000);
                byte[] buf = new byte[samples * 2];
                java.util.Random rnd = new java.util.Random();
                double baseFreq = 130.0; // low tone
                for (int i = 0; i < samples; i++) {
                    double t = i / sampleRate;

                    // Fast attack, exponential decay envelope
                    double envAttack = Math.min(1.0, i / (samples * 0.08));
                    double envDecay = Math.pow(1.0 - (double)i / samples, 1.4);
                    double env = envAttack * envDecay;

                    // Low sine + a touch of noise
                    double sine = Math.sin(2.0 * Math.PI * baseFreq * t);
                    double noise = (rnd.nextDouble() * 2.0 - 1.0) * 0.15;

                    // low volume
                    double v = (sine * 0.35 + noise) * env * 0.25;
                    short s = (short)(Math.max(-1.0, Math.min(1.0, v)) * 32767);
                    buf[i*2] = (byte)(s & 0xFF);
                    buf[i*2 + 1] = (byte)((s >>> 8) & 0xFF);
                }
                line.write(buf, 0, buf.length);
                line.drain();
                line.stop();
                line.close();
            } catch (Exception ex) {
                if (debug) System.err.println("Footstep sound failed: " + ex.getMessage());
            }
        }, "footstep-sound").start();
    }

    // 3D first-person view panel
    static class View3DPanel extends JPanel {
        private char[][] maze;
        private final int miniMapCell = 8;
        View3DPanel(char[][] maze) {
            this.maze = maze;
            setBackground(new Color(40, 40, 40));
            setFocusable(false);
        }

        void setMaze(char[][] newMaze) {
            this.maze = newMaze;
            revalidate();
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            int w = getWidth();
            int h = getHeight();

            // sky / ceiling (extended to top)
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h/2);

            // floor
            g.setColor(new Color(150, 120, 80));
            g.fillRect(0, h/2, w, h/2);

            // simple slice-based corridor rendering
            int viewDepth = 10;
            int centerX = w/2;
            
            // Check for immediate walls (at player position)
            boolean immediateLeftWall = isWallAt(maze, 0, 1);
            boolean immediateRightWall = isWallAt(maze, 0, -1);
            
            // Draw immediate doorways if they exist
            if (!immediateLeftWall) {

                // immediate left doorway - fill from screen edge to first slice
                int firstLeft = (int) (centerX - (w/2) * 1.0 * 0.7);
                int firstTop = (int) (h * 0.15);
                int firstBottom = h - firstTop;
                g.setColor(new Color(40, 40, 40));
                g.fillPolygon(new Polygon(new int[]{0, firstLeft, firstLeft, 0}, new int[]{0, firstTop, firstBottom, h}, 4));

                // Fill top triangle with white (ceiling color)
                g.setColor(Color.WHITE);
                g.fillPolygon(new Polygon(new int[]{0, firstLeft, 0}, new int[]{0, firstTop, firstTop}, 3));

                // Fill bottom triangle with floor color
                g.setColor(new Color(150, 120, 80));
                g.fillPolygon(new Polygon(new int[]{0, firstLeft, 0}, new int[]{h, firstBottom, firstBottom}, 3));

                // Draw lines showing the side hallway
                g.setColor(Color.BLACK);
                g.drawLine(firstLeft, firstTop, 0, firstTop);
                g.drawLine(firstLeft, firstBottom, 0, firstBottom);
            }

            if (!immediateRightWall) {

                // immediate right doorway - fill from first slice to screen edge
                int firstRight = (int) (centerX + (w/2) * 1.0 * 0.7);
                int firstTop = (int) (h * 0.15);
                int firstBottom = h - firstTop;
                g.setColor(new Color(40, 40, 40));
                g.fillPolygon(new Polygon(new int[]{firstRight, w, w, firstRight}, new int[]{firstTop, 0, h, firstBottom}, 4));

                // Fill top triangle with white (ceiling color)
                g.setColor(Color.WHITE);
                g.fillPolygon(new Polygon(new int[]{firstRight, w, w}, new int[]{firstTop, 0, firstTop}, 3));

                // Fill bottom triangle with floor color
                g.setColor(new Color(150, 120, 80));
                g.fillPolygon(new Polygon(new int[]{firstRight, w, w}, new int[]{firstBottom, h, firstBottom}, 3));

                // Draw lines showing the side hallway
                g.setColor(Color.BLACK);
                g.drawLine(firstRight, firstTop, w, firstTop);
                g.drawLine(firstRight, firstBottom, w, firstBottom);
            }
            
            // Defer key and ladder rendering until after walls to keep them on top
            boolean keyShouldDraw = false;
            int pendingKeyX = 0, pendingKeyY = 0, pendingKeySize = 0;
            boolean upLadderShouldDraw = false;
            int upRailLeft = 0, upRailRight = 0, upTop = 0, upBottom = 0, upRungCount = 0;
            double upLadderScale = 0.0; // capture slice scale to vary rail thickness by distance
            boolean downLadderShouldDraw = false;
            int downNearLeft = 0, downNearRight = 0, downFarLeft = 0, downFarRight = 0, downNearY = 0, downFarY = 0;

            for (int dist = 1; dist <= viewDepth; dist++) {
                double scale = 1.0 - (double)(dist-1) / viewDepth;
                double nextScale = 1.0 - (double)dist / viewDepth;

                int left = (int) (centerX - (w/2) * scale * 0.7);
                int right = (int) (centerX + (w/2) * scale * 0.7);
                int top = (int) (h * 0.15 + (h*0.35) * ((double)(dist-1) / viewDepth));
                int bottom = h - top;

                int leftNext = (int) (centerX - (w/2) * nextScale * 0.7);
                int rightNext = (int) (centerX + (w/2) * nextScale * 0.7);
                int topNext = (int) (h * 0.15 + (h*0.35) * ((double)dist / viewDepth));
                int bottomNext = h - topNext;

                // check walls: at current distance, check immediate left/right
                boolean frontBlock = isWallAt(maze, dist, 0);
                boolean leftWall = isWallAt(maze, dist, 1);
                boolean rightWall = isWallAt(maze, dist, -1);
                
                // Check if exit is at this position in front
                double baseR = animating ? (animFromI + (animToI - animFromI) * getAnimationProgress()) : posI;
                double baseC = animating ? (animFromJ + (animToJ - animFromJ) * getAnimationProgress()) : posJ;
                int[] dir = directions[facing];
                int checkR = (int)Math.round(baseR + dir[0] * dist);
                int checkC = (int)Math.round(baseC + dir[1] * dist);
                boolean frontIsExit = (checkR >= 0 && checkR < maze.length && checkC >= 0 && checkC < maze[0].length && maze[checkR][checkC] == 'E');
                boolean frontIsUpLadder = (checkR >= 0 && checkR < maze.length && checkC >= 0 && checkC < maze[0].length && maze[checkR][checkC] == 'U');
                boolean frontIsDownLadder = (checkR >= 0 && checkR < maze.length && checkC >= 0 && checkC < maze[0].length && maze[checkR][checkC] == 'D');

                // Draw key when it is directly ahead in the corridor and not blocked
                boolean keyAhead = (!hasKey && currentFloor == keyFloor && checkR == keyI && checkC == keyJ && !frontBlock);
                if (keyAhead && !keyShouldDraw) {
                    int sliceWidth = Math.max(1, right - left);
                    double scaleFactor = nextScale * nextScale; // stronger shrink with distance
                    pendingKeySize = Math.max(3, (int)Math.round(sliceWidth * 0.05 * scaleFactor));
                    pendingKeyX = (left + right) / 2;
                    pendingKeyY = bottom - Math.max(6, h / 50);
                    keyShouldDraw = true;
                }

                // draw opening slice - split into ceiling (white) and floor (dark grey)
                int mid = (top + bottom) / 2;
                int midNext = (topNext + bottomNext) / 2;
                
                // ceiling (top half)
                g.setColor(Color.WHITE);
                Polygon ceiling = new Polygon(new int[] {left, right, rightNext, leftNext}, new int[] {top, top, topNext, topNext}, 4);
                g.fillPolygon(ceiling);
                
                // floor (bottom half)
                g.setColor(new Color(40, 40, 40));
                Polygon floor = new Polygon(new int[] {left, right, rightNext, leftNext}, new int[] {mid, mid, midNext, midNext}, 4);
                g.fillPolygon(floor);

                // Capture ladder geometry to draw later (after back wall), only if not blocked
                if (!frontBlock && frontIsUpLadder && !upLadderShouldDraw) {
                    int sliceWidth = Math.max(1, right - left);
                    int railOffset = Math.max(2, (int)Math.round(sliceWidth * 0.06));
                    int cx = (left + right) / 2;
                    upRailLeft = cx - railOffset;
                    upRailRight = cx + railOffset;
                    upTop = topNext; upBottom = bottomNext;
                    // Keep rung count constant; spacing naturally tightens as slice height shrinks with distance
                    upRungCount = 6;
                    // Capture scale for thickness modulation (closer -> thicker, farther -> thinner)
                    upLadderScale = nextScale;
                    upLadderShouldDraw = true;
                }

                if (!frontBlock && frontIsDownLadder && !downLadderShouldDraw) {
                    // Create a centered trapezoid hole on the floor spanning one map square (inset slightly), half corridor width
                    int inset = Math.max(2, (int)Math.round((right - left) * 0.02));
                    int nearWidth = Math.max(1, right - left);
                    int farWidth = Math.max(1, rightNext - leftNext);
                    int cxNear = (left + right) / 2;
                    int cxFar = (leftNext + rightNext) / 2;
                    // target half-width factor
                    double widthFactor = 0.5;
                    int nearHalf = (int)Math.round(nearWidth * widthFactor / 2);
                    int farHalf = (int)Math.round(farWidth * widthFactor / 2);
                    downNearLeft = cxNear - nearHalf + inset;
                    downNearRight = cxNear + nearHalf - inset;
                    downFarLeft = cxFar - farHalf + inset;
                    downFarRight = cxFar + farHalf - inset;
                    // Position the hole down on the floor (near bottom of slice), with far edge slightly higher
                    // Nearly touch the floor edge: use minimal fixed pixel offsets
                    int floorInsetNear = 1; // 1px from bottom
                    int floorInsetFar = 2;  // slightly higher far edge for depth cue
                    downNearY = bottom - floorInsetNear;
                    downFarY = bottomNext - floorInsetFar;
                    downLadderShouldDraw = true;
                }


                // For first slice, draw immediate walls from screen edge if they exist
                if (dist == 1) {
                    if (immediateLeftWall) {
                        Polygon immLeftWall = new Polygon(
                            new int[]{0, left, left, 0}, 
                            new int[]{0, top, bottom, h}, 
                            4
                        );
                        g.setColor(new Color(80, 80, 80));
                        g.fillPolygon(immLeftWall);
                    }
                    if (immediateRightWall) {
                        Polygon immRightWall = new Polygon(
                            new int[]{right, w, w, right}, 
                            new int[]{top, 0, h, bottom}, 
                            4
                        );
                        g.setColor(new Color(80, 80, 80));
                        g.fillPolygon(immRightWall);
                    }
                }

                // draw left wall as solid trapezoid if wall exists
                if (leftWall) {

                    // solid trapezoid: only this distance slice, from left to leftNext
                    Polygon leftWallPoly = new Polygon(
                        new int[]{left, left, leftNext, leftNext}, 
                        new int[]{top, bottom, bottomNext, topNext}, 
                        4
                    );
                    g.setColor(new Color(80, 80, 80));
                    g.fillPolygon(leftWallPoly);
                } else {

                    // doorway on left - draw dark grey opening with subtle distance-based shading
                    Polygon leftDoorway = new Polygon(
                        new int[]{left, left, leftNext, leftNext}, 
                        new int[]{top, bottom, bottomNext, topNext}, 
                        4
                    );
                    int shadeL = (int)Math.max(20, Math.round(40 - 20.0 * ((double)dist / viewDepth)));
                    g.setColor(new Color(shadeL, shadeL, shadeL));
                    g.fillPolygon(leftDoorway);

                    // Fill top triangle with white (ceiling color)
                    g.setColor(Color.WHITE);
                    g.fillPolygon(new Polygon(new int[]{left, leftNext, left}, new int[]{topNext, topNext, top}, 3));

                    // Fill bottom triangle with floor color
                    g.setColor(new Color(150, 120, 80));
                    g.fillPolygon(new Polygon(new int[]{left, leftNext, left}, new int[]{bottomNext, bottomNext, bottom}, 3));

                    // Draw lines showing the side hallway ceiling/wall joint
                    g.setColor(Color.BLACK);

                    // Stop at current slice's near edge (where closer wall would block view)
                    g.drawLine(leftNext, topNext, left, topNext);  // top - horizontal
                    g.drawLine(leftNext, bottomNext, left, bottomNext);  // bottom
                }
                
                // draw right wall as solid trapezoid if wall exists
                if (rightWall) {

                    // solid trapezoid: only this distance slice, from right to rightNext
                    Polygon rightWallPoly = new Polygon(
                        new int[]{right, right, rightNext, rightNext}, 
                        new int[]{top, bottom, bottomNext, topNext}, 
                        4
                    );
                    g.setColor(new Color(80, 80, 80));
                    g.fillPolygon(rightWallPoly);
                } else {

                    // doorway on right - draw dark grey opening with subtle distance-based shading
                    Polygon rightDoorway = new Polygon(
                        new int[]{right, right, rightNext, rightNext}, 
                        new int[]{top, bottom, bottomNext, topNext}, 
                        4
                    );
                    int shadeR = (int)Math.max(20, Math.round(40 - 20.0 * ((double)dist / viewDepth)));
                    g.setColor(new Color(shadeR, shadeR, shadeR));
                    g.fillPolygon(rightDoorway);

                    // Fill top triangle with white (ceiling color)
                    g.setColor(Color.WHITE);
                    g.fillPolygon(new Polygon(new int[]{right, rightNext, right}, new int[]{topNext, topNext, top}, 3));

                    // Fill bottom triangle with floor color
                    g.setColor(new Color(150, 120, 80));
                    g.fillPolygon(new Polygon(new int[]{right, rightNext, right}, new int[]{bottomNext, bottomNext, bottom}, 3));

                    // Draw lines showing the side hallway ceiling/wall joint
                    g.setColor(Color.BLACK);

                    // Stop at current slice's near edge (where closer wall would block view)
                    g.drawLine(rightNext, topNext, right, topNext);  // top - horizontal
                    g.drawLine(rightNext, bottomNext, right, bottomNext);  // bottom
                }

                // (Key drawing deferred until after the loop)
                
                // if front blocked or exit, draw a wall across the opening and stop deeper drawing
                if (frontBlock || frontIsExit) {

                    // Use current slice dimensions to fill the opening completely
                    Polygon front = new Polygon(new int[]{left, right, right, left}, new int[]{top, top, bottom, bottom}, 4);

                    // Shade front wall to visually match doorway shade at the same perceived slice
                    // Doorways at this loop index effectively appear one slice nearer than the front wall
                    int effectiveDist = Math.max(0, dist - 1);
                    int shadeFront = (int)Math.max(20, Math.round(40 - 20.0 * ((double)effectiveDist / viewDepth)));
                    g.setColor(new Color(shadeFront, shadeFront, shadeFront));
                    g.fillPolygon(front);
                    
                    // If this is the exit, draw "Exit" text on the wall
                    if (frontIsExit) {
                        g.setColor(Color.RED);
                        Font oldFont = g.getFont();
                        int fontSize = (int)((right - left) * 0.35);
                        int exitFontSize = Math.max(12, fontSize);
                        g.setFont(new Font("SansSerif", Font.BOLD, exitFontSize));
                        String exitText = "Exit";
                        FontMetrics fmExit = g.getFontMetrics();
                        int exitWidth = fmExit.stringWidth(exitText);
                        int exitAscent = fmExit.getAscent();
                        int exitX = (left + right) / 2 - exitWidth / 2;
                        int wallHeight = bottom - top;
                        int verticalOffset = Math.max(6, wallHeight / 8); // move text a bit higher on the wall
                        int exitY = (top + bottom) / 2 - verticalOffset + exitAscent / 2;
                        g.drawString(exitText, exitX, exitY);

                        // If the key hasn't been found yet, show a small "(locked)" below the Exit
                        if (!hasKey) {
                            String lockedText = "(locked)";
                            int lockedFontSize = Math.max(8, exitFontSize / 5); // slightly smaller for better scaling
                            g.setFont(new Font("SansSerif", Font.PLAIN, lockedFontSize));
                            g.setColor(Color.BLACK);
                            FontMetrics fmLocked = g.getFontMetrics();
                            int lockedWidth = fmLocked.stringWidth(lockedText);
                            int lockedAscent = fmLocked.getAscent();
                            int lockedX = (left + right) / 2 - lockedWidth / 2;
                            int lockedY = exitY + lockedAscent + Math.max(2, lockedFontSize / 10);
                            g.drawString(lockedText, lockedX, lockedY);
                        }
                        g.setFont(oldFont);
                    }
                    break;
                }
            }

            // Draw the key after walls but before ladders, with subtle alpha if a ladder is present
            if (keyShouldDraw) {
                java.awt.Composite oldComp = g.getComposite();
                boolean ladderPresent = upLadderShouldDraw || downLadderShouldDraw;
                if (ladderPresent) {
                    g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.82f));
                }
                g.setColor(new Color(212, 172, 55));
                g.fillOval(pendingKeyX - pendingKeySize/2, pendingKeyY - pendingKeySize/2, pendingKeySize, pendingKeySize);
                g.fillRect(pendingKeyX, pendingKeyY - pendingKeySize/6, pendingKeySize * 2, pendingKeySize / 3);
                g.fillRect(pendingKeyX + pendingKeySize * 2, pendingKeyY - pendingKeySize/6, pendingKeySize / 3, pendingKeySize / 2);
                if (ladderPresent) g.setComposite(oldComp);
            }

            // Draw ladders after walls and key so they overlay key and back wall
            if (upLadderShouldDraw) {
                g.setColor(new Color(180, 140, 60));
                // Vary rail thickness by distance: closer slices thicker, farther thinner
                int minThick = 3;
                int maxThick = 8;
                int railThick = Math.max(2, (int)Math.round(minThick + (maxThick - minThick) * upLadderScale));
                int halfT = Math.max(1, railThick / 2);
                g.fillRect(upRailLeft - halfT, upTop, railThick, upBottom - upTop);
                g.fillRect(upRailRight - halfT, upTop, railThick, upBottom - upTop);
                for (int r = 1; r < upRungCount - 1; r++) {
                    int y = upTop + (int)Math.round((double)r / (Math.max(1, upRungCount - 1)) * (upBottom - upTop));
                    g.fillRect(upRailLeft, y, upRailRight - upRailLeft, 2);
                }
            }

            if (downLadderShouldDraw) {
                // Draw a dark trapezoid hole aligned with the floor slice
                int[] xPts = new int[] { downNearLeft, downNearRight, downFarRight, downFarLeft };
                int[] yPts = new int[] { downNearY, downNearY, downFarY, downFarY };
                g.setColor(new Color(70, 50, 40));
                g.fillPolygon(xPts, yPts, 4);
                g.setColor(Color.BLACK);
                g.drawPolygon(xPts, yPts, 4);
            }

            // draw mini-map (top-left) if enabled
            if (showMiniMap) {
                int mmX = 10, mmY = 10;
                int mmCols = maze[0].length; int mmRows = maze.length;
                for (int r = 0; r < mmRows; r++) {
                    for (int c = 0; c < mmCols; c++) {
                        char ch = maze[r][c];
                        if (ch == '#') g.setColor(Color.DARK_GRAY);
                        else if (ch == 'E') g.setColor(Color.RED);
                        else if (ch == 'U') g.setColor(new Color(0, 180, 255));
                        else if (ch == 'D') g.setColor(new Color(180, 0, 255));
                        else g.setColor(Color.LIGHT_GRAY);
                        g.fillRect(mmX + c*miniMapCell, mmY + r*miniMapCell, miniMapCell, miniMapCell);
                    }
                }

                // draw player on mini-map (interpolated during animation)
                double t = getAnimationProgress();
                double pr = animating ? (animFromI + (animToI - animFromI) * t) : posI;
                double pc = animating ? (animFromJ + (animToJ - animFromJ) * t) : posJ;
                g.setColor(Color.GREEN);
                int drawX = (int)Math.round(mmX + pc*miniMapCell);
                int drawY = (int)Math.round(mmY + pr*miniMapCell);
                g.fillOval(drawX, drawY, miniMapCell, miniMapCell);

                // draw facing arrow
                g.setColor(Color.YELLOW);
                int ax = (int)Math.round(mmX + pc*miniMapCell + miniMapCell/2);
                int ay = (int)Math.round(mmY + pr*miniMapCell + miniMapCell/2);
                int bx = ax + directions[facing][1]*miniMapCell;
                int by = ay + directions[facing][0]*miniMapCell;
                g.drawLine(ax, ay, bx, by);
            }

            // draw "Press Start" text overlay
            drawStartOverlay(g, w, h);
            g.dispose();
        }

        // check if the cell at forward distance 'forward' and side -1/0/1 is a wall
        boolean isWallAt(char[][] maze, int forward, int side) {

            // use interpolated base position during animation for smoother rendering
            double t = getAnimationProgress();
            double baseR = animating ? (animFromI + (animToI - animFromI) * t) : posI;
            double baseC = animating ? (animFromJ + (animToJ - animFromJ) * t) : posJ;
            int[] dir = directions[facing];
            int leftDirR = -dir[1];
            int leftDirC = dir[0];
            int r = (int)Math.round(baseR + dir[0]*forward + leftDirR*side);
            int c = (int)Math.round(baseC + dir[1]*forward + leftDirC*side);
                if (r < 0 || r >= maze.length || c < 0 || c >= maze[0].length) return true; // treat out-of-bounds as wall
            return maze[r][c] == '#';
        }
    }

    // Consolidated move method: step = +1 forward, -1 backward
    static boolean move(char[][] maze, int step) {
        if (animating) {
            if (debug) System.out.println("move ignored: already animating");
            return false; // ignore inputs while animating
        }
        int[] dir = directions[facing];
        int ni = posI + dir[0] * step;
        int nj = posJ + dir[1] * step;
        if (debug) System.out.println("Attempt move" + (step > 0 ? "Forward" : "Backward") + ": from=" + posI + "," + posJ + " facing=" + facing + " target=" + ni + "," + nj);
        if (ni < 0 || ni >= maze.length || nj < 0 || nj >= maze[0].length) {
            return false;
        }

        // Block entering exit unless holding the key
        if (maze[ni][nj] == 'E' && !hasKey) {
            if (debug) System.out.println("Exit locked: pick up the key first");
            return false;
        }
        if (maze[ni][nj] == '#') {
            return false;
        }

        // start animation from current pos to target
        animFromI = posI; animFromJ = posJ; animToI = ni; animToJ = nj;
        animStartTime = System.currentTimeMillis();
        animating = true;
        if (animTimer == null) {
            animTimer = new javax.swing.Timer(16, ev -> {
                long now = System.currentTimeMillis();
                double t = (double)(now - animStartTime) / animDurationMs;
                if (t >= 1.0) {
                    animTimer.stop();
                    animating = false;

                    // Use currentMaze to avoid stale captured references after regeneration
                    finalizeMove(currentMaze);
                } else {
                    view3d.repaint(); topdown.repaint();
                }
            });
            animTimer.setRepeats(true);
        }
        animTimer.start();

        // Play quiet footstep when movement starts
        playFootstepSound();
        return true;
    }

    // Move forward in the current facing direction if not blocked
    static boolean moveForward(char[][] maze) {
        return move(maze, 1);
    }

    // Move backward (step opposite of facing) if not blocked
    static boolean moveBackward(char[][] maze) {
        return move(maze, -1);
    }

    static void finalizeMove(char[][] maze) {

        // clear old position unless it is a special tile (preserve ladders and exit)
        char oldCh = maze[animFromI][animFromJ];
        if (oldCh != 'E' && oldCh != 'U' && oldCh != 'D') {
            maze[animFromI][animFromJ] = ' ';
        }
        posI = animToI; posJ = animToJ;

        // Pick up key only when entering its tile in 3D view and on the correct floor
        if (!hasKey && use3D && currentFloor == keyFloor && posI == keyI && posJ == keyJ) {
            hasKey = true;
            keyI = -1; keyJ = -1; // remove key from world
            playPickupSound();
        }

        // Check if we reached the exit BEFORE overwriting the cell
        boolean reachedExit = solveMaze(maze, posI, posJ);
        // do not overwrite ladders or exit with 'O'
        char newCh = maze[posI][posJ];
        if (newCh != 'E' && newCh != 'U' && newCh != 'D') {
            maze[posI][posJ] = 'O';
        }
        view3d.repaint(); topdown.repaint();

        // record step
        path.add(currentFloor + "," + posI + "," + posJ);
        if (reachedExit) {
            SwingUtilities.invokeLater(() -> {
                playExitSound();
                view3d.repaint(); topdown.repaint();
                JOptionPane.showMessageDialog(frame, "Exit found!");
                savePathAndExit();
            });
        }
    }

    // Top-down Maze panel
    static class MazePanel extends JPanel {
        private char[][] maze;
        private final int cellSize = 28;
        MazePanel(char[][] maze) {
            this.maze = maze;
            int w = maze[0].length * cellSize;
            int h = maze.length * cellSize;
            setPreferredSize(new Dimension(w, h));
            setBackground(Color.BLACK);
            setFocusable(false);
        }

        void setMaze(char[][] newMaze) {
            this.maze = newMaze;
            int w = maze[0].length * cellSize;
            int h = maze.length * cellSize;
            setPreferredSize(new Dimension(w, h));
            revalidate();
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            for (int i = 0; i < maze.length; i++) {
                for (int j = 0; j < maze[i].length; j++) {
                    int x = j * cellSize;
                    int y = i * cellSize;
                    char c = maze[i][j];
                    if (c == '#') {
                        g.setColor(Color.DARK_GRAY);
                        g.fillRect(x, y, cellSize, cellSize);
                    } else if (c == 'O') {
                        g.setColor(Color.GREEN);
                        g.fillRect(x, y, cellSize, cellSize);
                    } else if (c == 'E') {
                        g.setColor(Color.RED);
                        g.fillRect(x, y, cellSize, cellSize);
                    } else if (c == 'U') {
                        g.setColor(new Color(0, 180, 255));
                        g.fillRect(x, y, cellSize, cellSize);
                    } else if (c == 'D') {
                        g.setColor(new Color(180, 0, 255));
                        g.fillRect(x, y, cellSize, cellSize);
                    } else {
                        g.setColor(Color.LIGHT_GRAY);
                        g.fillRect(x, y, cellSize, cellSize);
                    }
                    g.setColor(Color.BLACK);
                    g.drawRect(x, y, cellSize, cellSize);
                }
            }

            // draw player (interpolated during animation)
            double t = getAnimationProgress();
            double pr = animating ? (animFromI + (animToI - animFromI) * t) : posI;
            double pc = animating ? (animFromJ + (animToJ - animFromJ) * t) : posJ;
            int px = (int)Math.round(pc * cellSize);
            int py = (int)Math.round(pr * cellSize);
            g.setColor(Color.YELLOW);
            g.fillOval(px + cellSize/4, py + cellSize/4, cellSize/2, cellSize/2);

            // facing arrow
            g.setColor(Color.BLUE);
            int ax = px + cellSize/2;
            int ay = py + cellSize/2;
            int bx = ax + directions[facing][1]*cellSize;
            int by = ay + directions[facing][0]*cellSize;
            g.drawLine(ax, ay, bx, by);

            // draw "Press Start" text overlay
            int w = getWidth();
            int h = getHeight();
            drawStartOverlay(g, w, h);
            g.dispose();
        }
    }
}
