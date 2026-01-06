import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Maze {
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

    static double getAnimationProgress() {
        if (!animating) return 0.0;
        double t = (double)(System.currentTimeMillis() - animStartTime) / (double)animDurationMs;
        if (t < 0.0) return 0.0;
        return Math.min(1.0, t);
    }

    // Maze generation options
    static int mazeRowsDefault = 21;
    static int mazeColsDefault = 21;
    static boolean allowLoops = false; // whether to allow loops in generated maze
    static double loopChance = 0.05; // chance to remove a wall and create a loop

    public static void main(String[] args) {
        // generate a random maze (may include loops when allowLoops is true)
        currentMaze = generateMaze(mazeRowsDefault, mazeColsDefault, allowLoops, loopChance);

        // record initial position (set by generateMaze)
        path.add(posI + "," + posJ);

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
                startBtn.setEnabled(false);
                frame.requestFocusInWindow();
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
                currentMaze = generateMaze(mazeRowsDefault, mazeColsDefault, allowLoops, loopChance);
                view3d.setMaze(currentMaze);
                topdown.setMaze(currentMaze);
                view3d.repaint();
                topdown.repaint();
                path.clear();
                path.add(posI + "," + posJ);
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
            moved = moveForward(maze);
        } else if (k == KeyEvent.VK_DOWN || k == KeyEvent.VK_S) {
            moved = moveBackward(maze);
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
        }

        // If an animation was started, finalization happens in the timer; otherwise, record and check immediately
        if (moved && !animating) {
            view3d.repaint(); topdown.repaint();
            // record step
            path.add(posI + "," + posJ);
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
        return maze[i][j] == 'E';
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
        return grid;
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

        char[][] getMaze() { return maze; }

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
                    // doorway on left - draw dark grey opening
                    Polygon leftDoorway = new Polygon(
                        new int[]{left, left, leftNext, leftNext}, 
                        new int[]{top, bottom, bottomNext, topNext}, 
                        4
                    );
                    g.setColor(new Color(40, 40, 40));
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
                    // doorway on right - draw dark grey opening
                    Polygon rightDoorway = new Polygon(
                        new int[]{right, right, rightNext, rightNext}, 
                        new int[]{top, bottom, bottomNext, topNext}, 
                        4
                    );
                    g.setColor(new Color(40, 40, 40));
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
                
                // if front blocked or exit, draw a wall across the opening and stop deeper drawing
                if (frontBlock || frontIsExit) {
                    Polygon front = new Polygon(new int[]{leftNext, rightNext, rightNext, leftNext}, new int[]{topNext, topNext, bottomNext, bottomNext}, 4);
                    g.setColor(new Color(40, 40, 40));
                    g.fillPolygon(front);
                    
                    // If this is the exit, draw "Exit" text on the wall
                    if (frontIsExit) {
                        // Draw "Exit" text on the wall
                        g.setColor(Color.RED);
                        Font oldFont = g.getFont();
                        int fontSize = (int)((rightNext - leftNext) * 0.4);
                        g.setFont(new Font("SansSerif", Font.BOLD, Math.max(12, fontSize)));
                        String exitText = "Exit";
                        FontMetrics fm = g.getFontMetrics();
                        int textWidth = fm.stringWidth(exitText);
                        int textHeight = fm.getAscent();
                        int textX = (leftNext + rightNext) / 2 - textWidth / 2;
                        int textY = (topNext + bottomNext) / 2 + textHeight / 2;
                        g.drawString(exitText, textX, textY);
                        g.setFont(oldFont);
                    }
                    
                    break;
                }
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

    // Move forward in the current facing direction if not blocked
    static boolean moveForward(char[][] maze) {
        if (animating) {
            if (debug) System.out.println("moveForward ignored: already animating");
            return false; // ignore inputs while animating
        }
        int[] dir = directions[facing];
        int ni = posI + dir[0];
        int nj = posJ + dir[1];
        if (debug) System.out.println("Attempt moveForward: from=" + posI + "," + posJ + " facing=" + facing + " target=" + ni + "," + nj);
        if (ni < 0 || ni >= maze.length || nj < 0 || nj >= maze[0].length) {
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
                    finalizeMove(maze);
                } else {
                    view3d.repaint(); topdown.repaint();
                }
            });
            animTimer.setRepeats(true);
        }
        animTimer.start();
        return true;
    }

    // Move backward (step opposite of facing) if not blocked
    static boolean moveBackward(char[][] maze) {
        if (animating) {
            if (debug) System.out.println("moveBackward ignored: already animating");
            return false; // ignore inputs while animating
        }
        int[] dir = directions[facing];
        int ni = posI - dir[0];
        int nj = posJ - dir[1];
        if (debug) System.out.println("Attempt moveBackward: from=" + posI + "," + posJ + " facing=" + facing + " target=" + ni + "," + nj);
        if (ni < 0 || ni >= maze.length || nj < 0 || nj >= maze[0].length) {
            return false;
        }
        if (maze[ni][nj] == '#') {
            return false;
        }
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
                    finalizeMove(maze);
                } else {
                    view3d.repaint(); topdown.repaint();
                }
            });
            animTimer.setRepeats(true);
        }
        animTimer.start();
        return true;
    }

    static void finalizeMove(char[][] maze) {
        // clear old position unless it is the exit
        if (maze[animFromI][animFromJ] != 'E') maze[animFromI][animFromJ] = ' ';
        posI = animToI; posJ = animToJ;
        // Check if we reached the exit BEFORE overwriting the cell
        boolean reachedExit = solveMaze(maze, posI, posJ);
        if (maze[posI][posJ] != 'E') maze[posI][posJ] = 'O';
        view3d.repaint(); topdown.repaint();
        // record step
        path.add(posI + "," + posJ);
        if (reachedExit) {
            SwingUtilities.invokeLater(() -> {
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

        char[][] getMaze() { return maze; }

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

            g.dispose();
        }
    }

}
