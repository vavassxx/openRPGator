package rpg.engine.render.desktop;

import rpg.engine.render.Renderer;
import rpg.engine.pak.PakImage;
import org.lwjgl.glfw.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.BufferUtils;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.system.MemoryStack.stackPush;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * LWJGL/GLFW immediate-mode renderer with dynamic window sizing,
 * orthographic projection, camera, bitmap text, filled rectangles
 * and mouse input.
 */
public final class LwjglRenderer implements Renderer {

    private static final int TILE_HW = 16;
    private static final int TILE_HH = 8;

    // ── Key constants ─────────────────────────────────────────────
    public static final int KEY_W = GLFW_KEY_W, KEY_A = GLFW_KEY_A,
            KEY_S = GLFW_KEY_S, KEY_D = GLFW_KEY_D;
    public static final int KEY_UP = GLFW_KEY_UP, KEY_DOWN = GLFW_KEY_DOWN,
            KEY_LEFT = GLFW_KEY_LEFT, KEY_RIGHT = GLFW_KEY_RIGHT;
    public static final int KEY_J = GLFW_KEY_J, KEY_K = GLFW_KEY_K,
            KEY_L = GLFW_KEY_L, KEY_I = GLFW_KEY_I;
    public static final int KEY_EQUAL = GLFW_KEY_EQUAL, KEY_MINUS = GLFW_KEY_MINUS;
    public static final int KEY_KP_ADD = GLFW_KEY_KP_ADD, KEY_KP_SUBTRACT = GLFW_KEY_KP_SUBTRACT;
    public static final int KEY_ESCAPE = GLFW_KEY_ESCAPE,
            KEY_ENTER = GLFW_KEY_ENTER,
            KEY_BACKSPACE = GLFW_KEY_BACKSPACE,
            KEY_TAB = GLFW_KEY_TAB;

    private long window;
    private volatile boolean closed;
    private int fbw = 1280, fbh = 720;

    private double camX, camY;
    private double zoom = 1.0;

    // ── Pak-backed textures (tiles by map tile id; sprites keyed by sprite name) ──
    private int[] tileTex = new int[0];
    private int[] spriteTex = new int[0];
    private int[] spriteTexW = new int[0];
    private int[] spriteTexH = new int[0];
    private Map<String, Integer> spriteIndex = new HashMap<>();

    private GLFWWindowSizeCallback winSizeCb;
    private GLFWFramebufferSizeCallback fbSizeCb;
    private GLFWKeyCallback keyCb;
    private GLFWCharCallback charCb;
    private GLFWMouseButtonCallback mouseBtnCb;
    private GLFWCursorPosCallback cursorPosCb;

    private final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] keyEdge = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] mouseBtn = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private final boolean[] mouseBtnEdge = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private double mouseX, mouseY;
    private int pendingChar;

    // ── 5×7 bitmap font, row-major, bits 4..0 = cols 4..0 ─────────
    // ASCII 32..126, then the Cyrillic А..Я (0x0410..0x042F) and а..я (0x0430..0x044F);
    // lowercase naturally reuses the uppercase glyphs (standard for a 5×7 font). Ё/ё render as Е.
    private static final int FONT_FIRST = 32, FONT_LAST = 126;
    private static final int FONT_ASCII_COUNT = FONT_LAST - FONT_FIRST + 1;   // 95
    private static final int CYR_INDEX = FONT_ASCII_COUNT;                    // 95
    private static final int CYR_LOWER_INDEX = CYR_INDEX + 32;                // 127
    private static final int FONT_SIZE = CYR_LOWER_INDEX + 32;                // 159
    private static final byte[][] FONT = buildFont();

    public LwjglRenderer(int w, int h, String title) {
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(w, h, title, 0, 0);
        if (window == 0) { glfwTerminate(); throw new IllegalStateException("window creation failed"); }
        glfwMakeContextCurrent(window);
        org.lwjgl.opengl.GL.createCapabilities();
        glfwSwapInterval(1);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        winSizeCb = GLFWWindowSizeCallback.create((win, width, height) -> {});
        glfwSetWindowSizeCallback(window, winSizeCb);

        fbSizeCb = GLFWFramebufferSizeCallback.create((win, width, height) -> { fbw = width; fbh = height; });
        glfwSetFramebufferSizeCallback(window, fbSizeCb);

        keyCb = GLFWKeyCallback.create((win, key, scancode, action, mods) -> {
            if (key >= 0 && key <= GLFW_KEY_LAST) {
                keys[key] = (action != GLFW_RELEASE);
                if (action == GLFW_PRESS) keyEdge[key] = true;
            }
        });
        glfwSetKeyCallback(window, keyCb);

        charCb = GLFWCharCallback.create((win, codepoint) -> {
            if (codepoint >= 32 && codepoint < 127) pendingChar = codepoint;
        });
        glfwSetCharCallback(window, charCb);

        mouseBtnCb = GLFWMouseButtonCallback.create((win, button, action, mods) -> {
            if (button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST) {
                boolean pressed = (action == GLFW_PRESS);
                mouseBtnEdge[button] = pressed && !mouseBtn[button];
                mouseBtn[button] = pressed;
            }
        });
        glfwSetMouseButtonCallback(window, mouseBtnCb);

        cursorPosCb = GLFWCursorPosCallback.create((win, x, y) -> { mouseX = x; mouseY = y; });
        glfwSetCursorPosCallback(window, cursorPosCb);

        try (MemoryStack stack = stackPush()) {
            IntBuffer wb = stack.mallocInt(1), hb = stack.mallocInt(1);
            glfwGetFramebufferSize(window, wb, hb);
            if (wb.get(0) > 0) fbw = wb.get(0);
            if (hb.get(0) > 0) fbh = hb.get(0);
        }

        glfwShowWindow(window);
    }

    // ── Lifecycle ──────────────────────────────────────────────────
    public boolean shouldClose() { return closed || (window != 0 && glfwWindowShouldClose(window)); }
    public boolean isClosed() { return closed; }
    public void poll() {
        resetEdges();
        if (!closed) glfwPollEvents();
    }

    private String title = "";
    public void setTitle(String t) { if (!t.equals(title)) { title = t; glfwSetWindowTitle(window, t); } }

    @Override public void begin(int width, int height) {
        fbw = width; fbh = height;
        glViewport(0, 0, width, height);
        glClearColor(0.06f, 0.06f, 0.07f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        glOrtho(0, width, height, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
    }

    @Override public void end() { glfwSwapBuffers(window); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (keyCb != null) keyCb.free();
        if (charCb != null) charCb.free();
        if (winSizeCb != null) winSizeCb.free();
        if (fbSizeCb != null) fbSizeCb.free();
        if (mouseBtnCb != null) mouseBtnCb.free();
        if (cursorPosCb != null) cursorPosCb.free();
        if (window != 0) glfwDestroyWindow(window);
        window = 0;
        glfwTerminate();
    }

    // ── Camera / zoom ─────────────────────────────────────────────
    public void setCamera(double wx, double wy) { camX = wx; camY = wy; }
    public void setZoom(double z) { zoom = Math.max(0.25, Math.min(z, 4.0)); }
    public double zoom() { return zoom; }

    public void applyCamera() {
        double camSx = (camX - camY) * TILE_HW * zoom;
        double camSy = (camX + camY) * TILE_HH * zoom;
        glTranslated(fbw * 0.5 - camSx, fbh * 0.33 - camSy, 0);
    }

    /** Resets the modelview to identity so screen-space HUD can be drawn after applyCamera. */
    public void resetView() {
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
    }

    // ── Renderer interface ────────────────────────────────────────

    /** Uploads pak tile rasters as GL textures; indexes match the tile ids in the map. */
    public void setTileImages(PakImage[] images) {
        tileTex = upload(images);
    }

    /** Uploads pak sprite rasters as GL textures, keyed by sprite name (see {@code sprite(*)}). */
    public void setSpriteImages(PakImage[] images, String[] keys) {
        int n = images.length;
        spriteTex = upload(images);
        spriteTexW = new int[n];
        spriteTexH = new int[n];
        spriteIndex = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (images[i] == null) continue;
            spriteTexW[i] = images[i].width(); spriteTexH[i] = images[i].height();
            if (keys != null && i < keys.length && keys[i] != null) spriteIndex.put(keys[i], i);
        }
    }

    private int[] upload(PakImage[] images) {
        int n = images.length;
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            if (images[i] == null) continue;
            PakImage img = images[i];
            int w = img.width(), h = img.height();
            int id = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, id);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            // PakImage is row-major, top row first; OpenGL treats data row 0 as v=0 (the BOTTOM
            // of the texture). Upload rows bottom-to-top so the image's top row lands at v=1 and
            // quads drawn with v=1 at the top vertex render upright.
            ByteBuffer buf = BufferUtils.createByteBuffer(img.bytes());
            int rowBytes = w * 4;
            for (int r = h - 1; r >= 0; r--) buf.put(img.rgba(), r * rowBytes, rowBytes);
            buf.flip();
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            ids[i] = id;
        }
        return ids;
    }

    @Override public void tile(int x, int y, int id) {
        double sx = (x - y) * TILE_HW * zoom;
        double sy = (x + y) * TILE_HH * zoom;
        double hw = TILE_HW * zoom, hh = TILE_HH * zoom;
        if (id >= 0 && id < tileTex.length && tileTex[id] != 0) {
            glEnable(GL_TEXTURE_2D);
            glBindTexture(GL_TEXTURE_2D, tileTex[id]);
            glColor3f(1f, 1f, 1f);
            glBegin(GL_QUADS);
            glTexCoord2f(0.5f, 1f); glVertex2d(sx, sy);
            glTexCoord2f(1f, 0f);   glVertex2d(sx + hw, sy + hh);
            glTexCoord2f(0.5f, 0f); glVertex2d(sx, sy + hh * 2);
            glTexCoord2f(0f, 0f);   glVertex2d(sx - hw, sy + hh);
            glEnd();
            glDisable(GL_TEXTURE_2D);
        } else {
            glBegin(GL_QUADS);
            glColor3f(0.18f + 0.03f * (id % 3), 0.22f, 0.18f);
            glVertex2d(sx, sy);
            glVertex2d(sx + hw, sy + hh);
            glVertex2d(sx, sy + hh * 2);
            glVertex2d(sx - hw, sy + hh);
            glEnd();
        }
    }

    /**
     * Draws the sprite addressed by {@code key} (a {@code sprite/*} pak entry, e.g. {@code player})
     * at world position {@code (x,y)} with the given elevation. {@code scale} multiplies the
     * sprite's native size; missing keys render as a small marker quad.
     */
    @Override public void sprite(double x, double y, double elevation, String key, double scale) {
        double sx = (x - y) * TILE_HW * zoom;
        double sy = (x + y) * TILE_HH * zoom - elevation * 8 * zoom;
        int idx = key == null ? -1 : spriteIndex.getOrDefault(key, -1);
        int tex = idx >= 0 && idx < spriteTex.length ? spriteTex[idx] : 0;
        int tw = idx >= 0 && idx < spriteTexW.length ? spriteTexW[idx] : 0;
        int th = idx >= 0 && idx < spriteTexH.length ? spriteTexH[idx] : 0;
        if (tex != 0 && th > 0) {
            double w = tw * zoom * scale, h = th * zoom * scale;
            glEnable(GL_TEXTURE_2D);
            glBindTexture(GL_TEXTURE_2D, tex);
            glColor3f(1f, 1f, 1f);
            glBegin(GL_QUADS);
            glTexCoord2f(0f, 1f); glVertex2d(sx - w / 2, sy - h);
            glTexCoord2f(1f, 1f); glVertex2d(sx + w / 2, sy - h);
            glTexCoord2f(1f, 0f); glVertex2d(sx + w / 2, sy);
            glTexCoord2f(0f, 0f); glVertex2d(sx - w / 2, sy);
            glEnd();
            glDisable(GL_TEXTURE_2D);
        } else {
            double hw = 8 * zoom, h = 20 * zoom;
            glBegin(GL_QUADS);
            glColor3f(0.8f, 0.7f, 0.3f);
            glVertex2d(sx - hw, sy - h);
            glVertex2d(sx + hw, sy - h);
            glVertex2d(sx + hw, sy);
            glVertex2d(sx - hw, sy);
            glEnd();
        }
    }

    // ── UI primitives ─────────────────────────────────────────────
    public void rect(double x, double y, double w, double h,
                     float r, float g, float b, float a) {
        glDisable(GL_TEXTURE_2D);
        glColor4f(r, g, b, a);
        glBegin(GL_QUADS);
        glVertex2d(x, y);
        glVertex2d(x + w, y);
        glVertex2d(x + w, y + h);
        glVertex2d(x, y + h);
        glEnd();
    }

    public void text(double x, double y, String text, float r, float g, float b) {
        text(x, y, text, 1, r, g, b);
    }

    public void text(double x, double y, String text, int scale,
                     float r, float g, float b) {
        glDisable(GL_TEXTURE_2D);
        glColor4f(r, g, b, 1f);
        int cx = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') { cx = 0; y += 8 * scale; continue; }
            byte[] glyph = FONT[fontIndex(ch)];
            for (int row = 0; row < 7; row++) {
                byte bits = glyph[row];
                for (int col = 0; col < 5; col++) {
                    if ((bits & (1 << (4 - col))) != 0) {
                        double px = x + (cx + col) * scale;
                        double py = y + row * scale;
                        glBegin(GL_QUADS);
                        glVertex2d(px, py);
                        glVertex2d(px + scale, py);
                        glVertex2d(px + scale, py + scale);
                        glVertex2d(px, py + scale);
                        glEnd();
                    }
                }
            }
            cx += 6 * scale;
        }
    }

    public double textWidth(String text, int scale) {
        int w = 0, maxW = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') { maxW = Math.max(maxW, w); w = 0; }
            else w += 6 * scale;
        }
        return Math.max(maxW, w);
    }

    public int textHeight(int lines, int scale) { return lines * 8 * scale; }

    // ── Input helpers ─────────────────────────────────────────────
    public boolean keyDown(int glfwKey) { return glfwKey >= 0 && glfwKey <= GLFW_KEY_LAST && keys[glfwKey]; }

    /** True on the frame a key was pressed; consumed on read (one shot per press). */
    public boolean keyPressed(int glfwKey) {
        if (glfwKey >= 0 && glfwKey <= GLFW_KEY_LAST && keyEdge[glfwKey]) {
            keyEdge[glfwKey] = false;
            return true;
        }
        return false;
    }

    /** Consumes and returns the first key pressed this frame (edge), or -1 — for "press a key" rebinding. */
    public int consumeKey() {
        for (int i = 0; i <= GLFW_KEY_LAST; i++) {
            if (keyEdge[i]) {
                keyEdge[i] = false;
                return i;
            }
        }
        return -1;
    }

    private static final Map<Integer, String> KEY_NAMES = buildKeyNames();
    private static Map<Integer, String> buildKeyNames() {
        Map<Integer, String> m = new HashMap<>();
        for (int c = 0; c < 26; c++) m.put(GLFW_KEY_A + c, String.valueOf((char) ('A' + c)));
        for (int c = 0; c < 10; c++) m.put(GLFW_KEY_0 + c, String.valueOf((char) ('0' + c)));
        m.put(GLFW_KEY_UP, "UP"); m.put(GLFW_KEY_DOWN, "DOWN");
        m.put(GLFW_KEY_LEFT, "LEFT"); m.put(GLFW_KEY_RIGHT, "RIGHT");
        m.put(GLFW_KEY_SPACE, "Space"); m.put(GLFW_KEY_ENTER, "Enter"); m.put(GLFW_KEY_TAB, "Tab");
        m.put(GLFW_KEY_ESCAPE, "Esc"); m.put(GLFW_KEY_BACKSPACE, "Backspace"); m.put(GLFW_KEY_DELETE, "Delete");
        m.put(GLFW_KEY_LEFT_SHIFT, "LShift"); m.put(GLFW_KEY_RIGHT_SHIFT, "RShift");
        m.put(GLFW_KEY_LEFT_CONTROL, "LCtrl"); m.put(GLFW_KEY_RIGHT_CONTROL, "RCtrl");
        m.put(GLFW_KEY_LEFT_ALT, "LAlt"); m.put(GLFW_KEY_RIGHT_ALT, "RAlt");
        m.put(GLFW_KEY_EQUAL, "+"); m.put(GLFW_KEY_MINUS, "-");
        m.put(GLFW_KEY_KP_ADD, "KP+"); m.put(GLFW_KEY_KP_SUBTRACT, "KP-");
        m.put(GLFW_KEY_PERIOD, "."); m.put(GLFW_KEY_COMMA, ",");
        m.put(GLFW_KEY_SLASH, "/"); m.put(GLFW_KEY_SEMICOLON, ";");
        for (int f = 0; f < 12; f++) m.put(GLFW_KEY_F1 + f, "F" + (f + 1));
        return m;
    }

    /** Human-readable name for a GLFW key code ("W", "UP", ...). */
    public String keyName(int glfwKey) { return KEY_NAMES.getOrDefault(glfwKey, "Key " + glfwKey); }

    public boolean mouseDown(int button) {
        return button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST && mouseBtn[button];
    }
    public boolean mouseClicked(int button) {
        if (button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST && mouseBtnEdge[button]) {
            mouseBtnEdge[button] = false;
            return true;
        }
        return false;
    }
    public double mouseX() { return mouseX; }
    public double mouseY() { return mouseY; }

    /** Returns the next typed character (ASCII 32..126), consumed on read; 0 if none. */
    public int consumeChar() {
        int c = pendingChar;
        pendingChar = 0;
        return c;
    }

    public boolean isFocused() { return glfwGetWindowAttrib(window, GLFW_FOCUSED) == GLFW_TRUE; }
    public int framebufferWidth() { return fbw; }
    public int framebufferHeight() { return fbh; }

    /** Returns the GLFW window handle (for external cursor configuration if needed). */
    public long windowHandle() { return window; }

    private void resetEdges() {
        for (int i = 0; i < mouseBtnEdge.length; i++) mouseBtnEdge[i] = false;
        for (int i = 0; i < keyEdge.length; i++) keyEdge[i] = false;
    }

    // ── Font data ─────────────────────────────────────────────────
    /** Maps a character to its glyph slot: ASCII, Cyrillic А..Я / а..я, Ё/ё → Е, typographic
     * punctuation → ASCII, else '?'. */
    private static int fontIndex(char ch) {
        if (ch >= FONT_FIRST && ch <= FONT_LAST) return ch - FONT_FIRST;
        if (ch >= 0x0410 && ch <= 0x042F) return CYR_INDEX + (ch - 0x0410);
        if (ch >= 0x0430 && ch <= 0x044F) return CYR_LOWER_INDEX + (ch - 0x0430);
        if (ch == 0x0401 || ch == 0x0451) return CYR_INDEX + ('Е' - 0x0410); // Ё/ё render as Е
        switch (ch) {
            case '\u2013': case '\u2014': return '-' - FONT_FIRST;           // – — → -
            case '\u2018': case '\u2019': return '\'' - FONT_FIRST;          // ' ' → '
            case '\u201C': case '\u201D': case '\u201E': return '"' - FONT_FIRST; // " " „ → "
            case '\u00AB': return '<' - FONT_FIRST;                          // « → <
            case '\u00BB': return '>' - FONT_FIRST;                          // » → >
            case '\u2026': return '.' - FONT_FIRST;                          // … → .
            case '\u00A0': return ' ' - FONT_FIRST;                          // nbsp → space
            default: break;
        }
        return '?' - FONT_FIRST;
    }

    private static byte[][] buildFont() {
        byte[][] f = new byte[FONT_SIZE][7];
        // space (32)
        f[0] = new byte[]{0x00,0x00,0x00,0x00,0x00,0x00,0x00};
        // ! (33)
        f[1] = new byte[]{0x04,0x04,0x04,0x04,0x04,0x00,0x04};
        // " (34)
        f[2] = new byte[]{0x0A,0x0A,0x00,0x00,0x00,0x00,0x00};
        // # (35)
        f[3] = new byte[]{0x0A,0x0A,0x1F,0x0A,0x1F,0x0A,0x0A};
        // $ (36)
        f[4] = new byte[]{0x04,0x0F,0x14,0x0E,0x05,0x1E,0x04};
        // % (37)
        f[5] = new byte[]{0x18,0x19,0x02,0x04,0x08,0x13,0x03};
        // & (38)
        f[6] = new byte[]{0x0C,0x12,0x14,0x08,0x15,0x12,0x0D};
        // ' (39)
        f[7] = new byte[]{0x04,0x04,0x08,0x00,0x00,0x00,0x00};
        // ( (40)
        f[8] = new byte[]{0x02,0x04,0x08,0x08,0x08,0x04,0x02};
        // ) (41)
        f[9] = new byte[]{0x08,0x04,0x02,0x02,0x02,0x04,0x08};
        // * (42)
        f[10] = new byte[]{0x00,0x04,0x15,0x0E,0x15,0x04,0x00};
        // + (43)
        f[11] = new byte[]{0x00,0x04,0x04,0x1F,0x04,0x04,0x00};
        // , (44)
        f[12] = new byte[]{0x00,0x00,0x00,0x00,0x00,0x04,0x08};
        // - (45)
        f[13] = new byte[]{0x00,0x00,0x00,0x1F,0x00,0x00,0x00};
        // . (46)
        f[14] = new byte[]{0x00,0x00,0x00,0x00,0x00,0x00,0x04};
        // / (47)
        f[15] = new byte[]{0x00,0x01,0x02,0x04,0x08,0x10,0x00};
        // 0-9 (48-57)
        f[16] = new byte[]{0x0E,0x11,0x13,0x15,0x19,0x11,0x0E};
        f[17] = new byte[]{0x04,0x0C,0x04,0x04,0x04,0x04,0x0E};
        f[18] = new byte[]{0x0E,0x11,0x01,0x02,0x04,0x08,0x1F};
        f[19] = new byte[]{0x1F,0x02,0x04,0x02,0x01,0x11,0x0E};
        f[20] = new byte[]{0x02,0x06,0x0A,0x12,0x1F,0x02,0x02};
        f[21] = new byte[]{0x1F,0x10,0x1E,0x01,0x01,0x11,0x0E};
        f[22] = new byte[]{0x06,0x08,0x10,0x1E,0x11,0x11,0x0E};
        f[23] = new byte[]{0x1F,0x01,0x02,0x04,0x08,0x08,0x08};
        f[24] = new byte[]{0x0E,0x11,0x11,0x0E,0x11,0x11,0x0E};
        f[25] = new byte[]{0x0E,0x11,0x11,0x0F,0x01,0x02,0x0C};
        // : (58)
        f[26] = new byte[]{0x00,0x00,0x04,0x00,0x00,0x04,0x00};
        // ; (59)
        f[27] = new byte[]{0x00,0x00,0x04,0x00,0x00,0x04,0x08};
        // < (60)
        f[28] = new byte[]{0x02,0x04,0x08,0x10,0x08,0x04,0x02};
        // = (61)
        f[29] = new byte[]{0x00,0x00,0x1F,0x00,0x1F,0x00,0x00};
        // > (62)
        f[30] = new byte[]{0x08,0x04,0x02,0x01,0x02,0x04,0x08};
        // ? (63)
        f[31] = new byte[]{0x0E,0x11,0x01,0x02,0x04,0x00,0x04};
        // @ (64)
        f[32] = new byte[]{0x0E,0x11,0x15,0x1D,0x15,0x01,0x1E};
        // A-Z (65-90)
        f[33] = new byte[]{0x0E,0x11,0x11,0x1F,0x11,0x11,0x11};
        f[34] = new byte[]{0x1E,0x11,0x11,0x1E,0x11,0x11,0x1E};
        f[35] = new byte[]{0x0E,0x11,0x10,0x10,0x10,0x11,0x0E};
        f[36] = new byte[]{0x1E,0x11,0x11,0x11,0x11,0x11,0x1E};
        f[37] = new byte[]{0x1F,0x10,0x10,0x1E,0x10,0x10,0x1F};
        f[38] = new byte[]{0x1F,0x10,0x10,0x1E,0x10,0x10,0x10};
        f[39] = new byte[]{0x0E,0x11,0x10,0x17,0x11,0x11,0x0F};
        f[40] = new byte[]{0x11,0x11,0x11,0x1F,0x11,0x11,0x11};
        f[41] = new byte[]{0x0E,0x04,0x04,0x04,0x04,0x04,0x0E};
        f[42] = new byte[]{0x07,0x02,0x02,0x02,0x02,0x12,0x0C};
        f[43] = new byte[]{0x11,0x12,0x14,0x18,0x14,0x12,0x11};
        f[44] = new byte[]{0x10,0x10,0x10,0x10,0x10,0x10,0x1F};
        f[45] = new byte[]{0x11,0x1B,0x15,0x15,0x11,0x11,0x11};
        f[46] = new byte[]{0x11,0x19,0x15,0x13,0x11,0x11,0x11};
        f[47] = new byte[]{0x0E,0x11,0x11,0x11,0x11,0x11,0x0E};
        f[48] = new byte[]{0x1E,0x11,0x11,0x1E,0x10,0x10,0x10};
        f[49] = new byte[]{0x0E,0x11,0x11,0x11,0x15,0x12,0x0D};
        f[50] = new byte[]{0x1E,0x11,0x11,0x1E,0x14,0x12,0x11};
        f[51] = new byte[]{0x0E,0x11,0x10,0x0E,0x01,0x11,0x0E};
        f[52] = new byte[]{0x1F,0x04,0x04,0x04,0x04,0x04,0x04};
        f[53] = new byte[]{0x11,0x11,0x11,0x11,0x11,0x11,0x0E};
        f[54] = new byte[]{0x11,0x11,0x11,0x11,0x0A,0x0A,0x04};
        f[55] = new byte[]{0x11,0x11,0x11,0x15,0x15,0x1B,0x11};
        f[56] = new byte[]{0x11,0x11,0x0A,0x04,0x0A,0x11,0x11};
        f[57] = new byte[]{0x11,0x11,0x0A,0x04,0x04,0x04,0x04};
        f[58] = new byte[]{0x1F,0x01,0x02,0x04,0x08,0x10,0x1F};
        // [ (91)
        f[59] = new byte[]{0x0E,0x08,0x08,0x08,0x08,0x08,0x0E};
        // \ (92)
        f[60] = new byte[]{0x00,0x10,0x08,0x04,0x02,0x01,0x00};
        // ] (93)
        f[61] = new byte[]{0x0E,0x02,0x02,0x02,0x02,0x02,0x0E};
        // ^ (94)
        f[62] = new byte[]{0x04,0x0A,0x11,0x00,0x00,0x00,0x00};
        // _ (95)
        f[63] = new byte[]{0x00,0x00,0x00,0x00,0x00,0x00,0x1F};
        // ` (96)
        f[64] = new byte[]{0x08,0x04,0x02,0x00,0x00,0x00,0x00};
        // a-z (97-122)
        f[65] = new byte[]{0x00,0x00,0x0E,0x01,0x0F,0x11,0x0F};
        f[66] = new byte[]{0x10,0x10,0x16,0x19,0x11,0x11,0x1E};
        f[67] = new byte[]{0x00,0x00,0x0E,0x10,0x10,0x11,0x0E};
        f[68] = new byte[]{0x01,0x01,0x0D,0x13,0x11,0x11,0x0F};
        f[69] = new byte[]{0x00,0x00,0x0E,0x11,0x1F,0x10,0x0E};
        f[70] = new byte[]{0x06,0x09,0x08,0x1E,0x08,0x08,0x08};
        f[71] = new byte[]{0x00,0x0F,0x11,0x11,0x0F,0x01,0x0E};
        f[72] = new byte[]{0x10,0x10,0x16,0x19,0x11,0x11,0x11};
        f[73] = new byte[]{0x04,0x00,0x0C,0x04,0x04,0x04,0x0E};
        f[74] = new byte[]{0x02,0x00,0x06,0x02,0x02,0x12,0x0C};
        f[75] = new byte[]{0x10,0x10,0x12,0x14,0x18,0x14,0x12};
        f[76] = new byte[]{0x0C,0x04,0x04,0x04,0x04,0x04,0x0E};
        f[77] = new byte[]{0x00,0x00,0x1A,0x15,0x15,0x11,0x11};
        f[78] = new byte[]{0x00,0x00,0x16,0x19,0x11,0x11,0x11};
        f[79] = new byte[]{0x00,0x00,0x0E,0x11,0x11,0x11,0x0E};
        f[80] = new byte[]{0x00,0x00,0x1E,0x11,0x1E,0x10,0x10};
        f[81] = new byte[]{0x00,0x00,0x0D,0x13,0x0F,0x01,0x01};
        f[82] = new byte[]{0x00,0x00,0x16,0x19,0x10,0x10,0x10};
        f[83] = new byte[]{0x00,0x00,0x0E,0x10,0x0E,0x01,0x1E};
        f[84] = new byte[]{0x08,0x08,0x1E,0x08,0x08,0x09,0x06};
        f[85] = new byte[]{0x00,0x00,0x11,0x11,0x11,0x13,0x0D};
        f[86] = new byte[]{0x00,0x00,0x11,0x11,0x11,0x0A,0x04};
        f[87] = new byte[]{0x00,0x00,0x11,0x11,0x15,0x15,0x0A};
        f[88] = new byte[]{0x00,0x00,0x11,0x0A,0x04,0x0A,0x11};
        f[89] = new byte[]{0x00,0x00,0x11,0x11,0x0F,0x01,0x0E};
        f[90] = new byte[]{0x00,0x00,0x1F,0x02,0x04,0x08,0x1F};
        // { (123)
        f[91] = new byte[]{0x02,0x04,0x04,0x08,0x04,0x04,0x02};
        // | (124)
        f[92] = new byte[]{0x04,0x04,0x04,0x00,0x04,0x04,0x04};
        // } (125)
        f[93] = new byte[]{0x08,0x04,0x04,0x02,0x04,0x04,0x08};
        // ~ (126)
        f[94] = new byte[]{0x00,0x04,0x08,0x1F,0x08,0x04,0x00};

        // ── Cyrillic А..Я (0x0410..0x042F), 5×7; lowercase а..я reuses the same glyphs ──
        byte[][] cyr = new byte[][]{
            // А  Б  В  Г  Д  Е  Ж  З  И  Й  К  Л  М  Н  О  П
            {0x0E,0x11,0x11,0x1F,0x11,0x11,0x11},
            {0x1E,0x10,0x16,0x19,0x11,0x11,0x1E},
            {0x1E,0x11,0x11,0x1E,0x11,0x11,0x1E},
            {0x1F,0x10,0x10,0x10,0x10,0x10,0x10},
            {0x0E,0x11,0x11,0x11,0x11,0x0A,0x1F},
            {0x1F,0x10,0x10,0x1E,0x10,0x10,0x1F},
            {0x11,0x0A,0x04,0x04,0x04,0x0A,0x11},
            {0x0E,0x11,0x01,0x02,0x01,0x11,0x0E},
            {0x11,0x11,0x13,0x15,0x19,0x11,0x11},
            {0x0A,0x11,0x13,0x15,0x19,0x11,0x11},
            {0x11,0x12,0x14,0x18,0x14,0x12,0x11},
            {0x03,0x05,0x09,0x09,0x11,0x11,0x11},
            {0x11,0x1B,0x15,0x15,0x11,0x11,0x11},
            {0x11,0x11,0x11,0x1F,0x11,0x11,0x11},
            {0x0E,0x11,0x11,0x11,0x11,0x11,0x0E},
            {0x1F,0x11,0x11,0x11,0x11,0x11,0x11},
            // Р  С  Т  У  Ф  Х  Ц  Ч  Ш  Щ  Ъ  Ы  Ь  Э  Ю  Я
            {0x1E,0x11,0x11,0x1E,0x10,0x10,0x10},
            {0x0E,0x11,0x10,0x10,0x10,0x11,0x0E},
            {0x1F,0x04,0x04,0x04,0x04,0x04,0x04},
            {0x11,0x11,0x0A,0x04,0x04,0x04,0x04},
            {0x04,0x0E,0x15,0x15,0x15,0x0E,0x04},
            {0x11,0x11,0x0A,0x04,0x0A,0x11,0x11},
            {0x11,0x11,0x11,0x11,0x11,0x12,0x1F},
            {0x11,0x11,0x11,0x0F,0x01,0x01,0x01},
            {0x15,0x15,0x15,0x15,0x15,0x15,0x1F},
            {0x15,0x15,0x15,0x15,0x15,0x14,0x1F},
            {0x1E,0x02,0x02,0x02,0x0E,0x09,0x0E},
            {0x11,0x11,0x11,0x1F,0x11,0x11,0x0E},
            {0x01,0x01,0x01,0x0F,0x11,0x11,0x0E},
            {0x0E,0x11,0x01,0x0F,0x01,0x11,0x0E},
            {0x1E,0x19,0x19,0x19,0x19,0x19,0x1E},
            {0x0F,0x11,0x11,0x0F,0x05,0x09,0x11},
        };
        System.arraycopy(cyr, 0, f, CYR_INDEX, cyr.length);
        System.arraycopy(cyr, 0, f, CYR_LOWER_INDEX, cyr.length);
        return f;
    }
}