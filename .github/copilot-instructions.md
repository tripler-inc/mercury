# Copilot Instructions for Maze Project

## Project Overview
A Java Swing-based maze game with dual-view rendering (first-person 3D and top-down 2D). Players navigate procedurally-generated mazes with animated movement, finding the exit while their path is automatically logged.

## Architecture

### Core Components
- **Single-file architecture**: All code lives in [Maze.java](../Maze.java) (~750 lines)
- **View3DPanel**: First-person raycaster-style renderer with perspective corridor slicing (lines 400-600)
- **MazePanel**: Top-down 2D grid view with real-time player tracking (lines 700-756)
- **Animation system**: Smooth tile-to-tile transitions using interpolated positions (`getAnimationProgress()`, ~160ms duration)

### State Management
- **Global state pattern**: All game state is static class variables (position, facing, maze, animation state)
- **Position tracking**: `posI`/`posJ` (discrete grid), `animFromI`/`animToI` (interpolated during animation)
- **Path logging**: Appends to `path` ArrayList, written to `maze_path.txt` on exit (format: one "row,col" per line)

### Maze Generation
- **Algorithm**: Depth-first search with stack-based backtracking (lines 230-290)
- **Loop support**: Optional wall removal via `allowLoops` flag and `loopChance` (default 5%)
- **Dimensions**: Always odd-numbered (auto-adjusted: `if (rows % 2 == 0) rows++;`)
- **Entry/Exit placement**: Opposite borders (vertical or horizontal), marked as 'O' (start) and 'E' (exit on border cell)

## Key Conventions

### Coordinate System
- Grid indices: `(i, j)` where `i` is row (0=top), `j` is column (0=left)
- Facing directions: `0=Up, 1=Right, 2=Down, 3=Left` (index into `directions[][]`)
- Direction vectors: `{{-1,0}, {0,1}, {1,0}, {0,-1}}` (row-delta, col-delta)

### Animation Pattern
Movement is *always* animated. Never directly update `posI`/`posJ` during movement:
```java
// ✓ Correct: Start animation, let timer complete it
animFromI = posI; animToI = ni; animating = true; animTimer.start();

// ✗ Wrong: Direct position update during movement
posI = ni; posJ = nj; // This breaks smooth rendering
```

Use `finalizeMove()` to complete position updates after animation completes.

### Input Handling
- **Debouncing**: `keysDown` HashSet prevents auto-repeat (add on press, remove on release)
- **Animation blocking**: All movement/rotation inputs ignored when `animating == true`
- **Dual control schemes**: Arrow keys OR WASD (Left/A=rotate CCW, Right/D=rotate CW, Up/W=forward, Down/S=backward)

## Development Workflows

### Building & Running
```powershell
# Compile (from project root)
javac Maze.java

# Run
java Maze
```
No external dependencies required—pure Java Swing/AWT.

### Debugging
Enable debug mode by setting `static boolean debug = true;` (line 27). Outputs:
- Maze generation details
- Movement attempts and animation states
- Path save operations with stack traces

### Backup Management
The `backups/` directory contains a PowerShell-based rotation system:
- Script: [rotate_backups.ps1](../backups/rotate_backups.ps1)
- Looks for `Maze_backup_*.zip` pattern
- Supports retention policies: count-based (default: keep 5), age-based, and size-based pruning
- Usage: `.\rotate_backups.ps1 -Keep 7 -MaxAgeDays 30 -WhatIf`

## Rendering Details

### 3D View (View3DPanel)
- **Slice-based rendering**: Draws trapezoids for each distance (1-8 tiles ahead)
- **Wall detection**: `isWallAt(forward, side)` where `side` is -1 (left), 0 (center), 1 (right)
- **Exit marking**: Red "Exit" text drawn on wall when `frontIsExit == true`
- **Mini-map**: Toggle with 'M' key, shows full maze + player position in top-left corner

### Top-Down View (MazePanel)
- **Cell size**: Fixed 28px per grid cell
- **Color coding**: Walls=dark gray, passages=light gray, player='O'=green, exit='E'=red
- **Player rendering**: Yellow circle with blue facing arrow

### Animation Interpolation
Both views use the same interpolation:
```java
double t = getAnimationProgress(); // 0.0 to 1.0
double pr = animating ? (animFromI + (animToI - animFromI) * t) : posI;
```
This ensures synchronized rendering across views during movement.

## Common Patterns

### Adding New Controls
1. Add key code check in `handleKey()` (lines 150-210)
2. Respect `acceptingInput` flag (ignore input before game starts)
3. Check `animating` flag for movement-related actions
4. Call `frame.requestFocusInWindow()` after UI state changes

### Modifying Maze Generation
- Keep dimensions odd (enforced by `generateMaze()`)
- Ensure at least one passage cell exists for start/exit placement
- Use `grid[r][c] = ' '` for passages, `'#'` for walls
- Update `posI`/`posJ` after placing start position

### Extending Rendering
- Access current position: Use interpolated `pr`/`pc` during `animating`, else use `posI`/`posJ`
- Check cell contents: `maze[i][j]` returns `'#'`, `' '`, `'O'`, or `'E'`
- Direction-relative checks: Use `directions[facing]` array for forward/side offsets
