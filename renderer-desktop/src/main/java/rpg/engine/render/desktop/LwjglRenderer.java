package rpg.engine.render.desktop;

import rpg.engine.render.Renderer;
import org.lwjgl.glfw.GLFWWindowSizeCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;

import java.nio.IntBuffer;

/**
 * LWJGL/GLFW immediate-mode renderer with dynamic window sizing,
 * orthographic projection and camera.
 *
 * The window tracks its real framebuffer size via callbacks, so it renders
 * correctly when resized, maximised or tiled by a window manager.
 */
public final class LwjglRenderer implements Renderer {

    private static final int TILE_HW = 16;
    private static final int TILE_HH = 8;

    private long window;
    private int fbw = 1280, fbh = 720;

    private double camX, camY;
    private double zoom = 1.0;

    private GLFWWindowSizeCallback winSizeCb;
    private GLFWFramebufferSizeCallback fbSizeCb;
    private GLFWKeyCallback keyCb;

    private final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];

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

        winSizeCb = GLFWWindowSizeCallback.create((win, width, height) -> { });
        glfwSetWindowSizeCallback(window, winSizeCb);

        fbSizeCb = GLFWFramebufferSizeCallback.create((win, width, height) -> { fbw = width; fbh = height; });
        glfwSetFramebufferSizeCallback(window, fbSizeCb);

        keyCb = GLFWKeyCallback.create((win, key, scancode, action, mods) -> {
            if (key >= 0 && key <= GLFW_KEY_LAST) keys[key] = (action != GLFW_RELEASE);
        });
        glfwSetKeyCallback(window, keyCb);

        // Query current size once so fbw/fbh are correct before any callback fires.
        try (MemoryStack stack = stackPush()) {
            IntBuffer wb = stack.mallocInt(1), hb = stack.mallocInt(1);
            glfwGetFramebufferSize(window, wb, hb);
            if (wb.get(0) > 0) fbw = wb.get(0);
            if (hb.get(0) > 0) fbh = hb.get(0);
        }

        glfwShowWindow(window);
    }

    // ── Lifecycle ────────────────────────────────────────────────
    public boolean shouldClose() { return glfwWindowShouldClose(window); }
    public void poll() { glfwPollEvents(); }

    private String title = "";
    public void setTitle(String t) { if (!t.equals(title)) { title = t; glfwSetWindowTitle(window, t); } }

    @Override
    public void begin(int width, int height) {
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

    @Override
    public void end() { glfwSwapBuffers(window); }

    @Override
    public void close() {
        if (keyCb != null) keyCb.free();
        if (winSizeCb != null) winSizeCb.free();
        if (fbSizeCb != null) fbSizeCb.free();
        if (window != 0) glfwDestroyWindow(window);
        glfwTerminate();
    }

    // ── Camera / zoom ────────────────────────────────────────────
    public void setCamera(double wx, double wy) { camX = wx; camY = wy; }
    public void setZoom(double z) { zoom = Math.max(0.25, Math.min(z, 4.0)); }
    public double zoom() { return zoom; }

    /** Call after begin() to translate the modelview so (camX, camY) is screen-centred. */
    public void applyCamera() {
        double camSx = (camX - camY) * TILE_HW * zoom;
        double camSy = (camX + camY) * TILE_HH * zoom;
        glTranslated(fbw * 0.5 - camSx, fbh * 0.33 - camSy, 0);
    }

    // ── Renderer interface ───────────────────────────────────────
    @Override
    public void tile(int x, int y, int id) {
        double sx = (x - y) * TILE_HW * zoom;
        double sy = (x + y) * TILE_HH * zoom;
        double hw = TILE_HW * zoom, hh = TILE_HH * zoom;
        glBegin(GL_QUADS);
        glColor3f(0.18f + 0.03f * (id % 3), 0.22f, 0.18f);
        glVertex2d(sx, sy);
        glVertex2d(sx + hw, sy + hh);
        glVertex2d(sx, sy + hh * 2);
        glVertex2d(sx - hw, sy + hh);
        glEnd();
    }

    @Override
    public void sprite(double x, double y, double elevation, int resource) {
        double sx = (x - y) * TILE_HW * zoom;
        double sy = (x + y) * TILE_HH * zoom - elevation * 8 * zoom;
        double hw = 8 * zoom, h = 20 * zoom;
        glBegin(GL_QUADS);
        glColor3f(0.8f, 0.7f, 0.3f);
        glVertex2d(sx - hw, sy - h);
        glVertex2d(sx + hw, sy - h);
        glVertex2d(sx + hw, sy);
        glVertex2d(sx - hw, sy);
        glEnd();
    }

    // ── Input helpers ────────────────────────────────────────────
    public boolean keyDown(int glfwKey) { return glfwKey >= 0 && glfwKey <= GLFW_KEY_LAST && keys[glfwKey]; }
    public boolean isFocused() { return glfwGetWindowAttrib(window, GLFW_FOCUSED) == GLFW_TRUE; }
    public int framebufferWidth() { return fbw; }
    public int framebufferHeight() { return fbh; }
}