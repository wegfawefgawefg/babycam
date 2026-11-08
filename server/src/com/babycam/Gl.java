package com.babycam;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

// Camera frames -> one GL pass (rotate, zoom/pan crop, mono, gain, gamma) -> encoder surface.
// Every few frames also renders a tiny copy to measure brightness for our auto exposure.
final class Gl {
    static volatile double lastMean = 0;
    static volatile long lastFrameNs = System.nanoTime();

    static final String VS =
        "attribute vec2 aPos; varying vec2 vUv;\n" +
        "void main(){ vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }\n";
    static final String FS =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision highp float;\n" +
        "uniform samplerExternalOES uTex; uniform mat4 uSt; uniform mat2 uRot;\n" +
        "uniform vec2 uCenter; uniform float uScale, uMono, uGain, uGamma;\n" +
        "varying vec2 vUv;\n" +
        "void main(){\n" +
        "  vec2 s = uCenter + (uRot * (vUv - 0.5)) * uScale;\n" +
        "  vec3 c = texture2D(uTex, (uSt * vec4(s, 0.0, 1.0)).xy).rgb;\n" +
        "  if (uMono > 0.5) c = vec3(dot(c, vec3(0.299, 0.587, 0.114)));\n" +
        "  c = pow(clamp(c * uGain, 0.0, 1.0), vec3(uGamma));\n" +
        "  gl_FragColor = vec4(c, 1.0);\n" +
        "}\n";

    final Handler handler;
    final int outW, outH;
    EGLDisplay dpy;
    EGLContext ctx;
    EGLSurface surf;
    int prog, tex, fbo, fboTex;
    int aPos, uSt, uRot, uCenter, uScale, uMono, uGain, uGamma;
    SurfaceTexture st;
    Surface camSurface;
    final float[] stMatrix = new float[16];
    final FloatBuffer quad;
    final ByteBuffer pixels;
    static final int MW = 32, MH = 24;
    long frames = 0;
    Runnable onMeasure;
    volatile Runnable firstFrameHook;

    Gl(Handler handler, Surface encoderSurface, int outW, int outH, int srcW, int srcH) {
        this.handler = handler;
        this.outW = outW;
        this.outH = outH;
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        pixels = ByteBuffer.allocateDirect(MW * MH * 4).order(ByteOrder.nativeOrder());

        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        EGL14.eglInitialize(dpy, v, 0, v, 1);
        int[] attr = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE};
        EGLConfig[] cfg = new EGLConfig[1];
        int[] n = new int[1];
        EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, n, 0);
        ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        surf = EGL14.eglCreateWindowSurface(dpy, cfg[0], encoderSurface, new int[] {EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx);

        prog = program(VS, FS);
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        uSt = GLES20.glGetUniformLocation(prog, "uSt");
        uRot = GLES20.glGetUniformLocation(prog, "uRot");
        uCenter = GLES20.glGetUniformLocation(prog, "uCenter");
        uScale = GLES20.glGetUniformLocation(prog, "uScale");
        uMono = GLES20.glGetUniformLocation(prog, "uMono");
        uGain = GLES20.glGetUniformLocation(prog, "uGain");
        uGamma = GLES20.glGetUniformLocation(prog, "uGamma");

        int[] t = new int[2];
        GLES20.glGenTextures(2, t, 0);
        tex = t[0];
        fboTex = t[1];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, MW, MH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        fbo = f[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        st = new SurfaceTexture(tex);
        st.setDefaultBufferSize(srcW, srcH);
        st.setOnFrameAvailableListener(s -> frame(), handler);
        camSurface = new Surface(st);
    }

    static int program(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    void frame() {
        st.updateTexImage();
        lastFrameNs = System.nanoTime();
        st.getTransformMatrix(stMatrix);
        frames++;
        if (firstFrameHook != null) { Runnable r = firstFrameHook; firstFrameHook = null; r.run(); }
        if (frames % 4 == 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            draw(MW, MH, true);
            pixels.position(0);
            GLES20.glReadPixels(0, 0, MW, MH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            long sum = 0;
            for (int i = 0; i < MW * MH; i++) {
                int r = pixels.get(i * 4) & 0xff, g = pixels.get(i * 4 + 1) & 0xff, b = pixels.get(i * 4 + 2) & 0xff;
                sum += (r * 299 + g * 587 + b * 114) / 1000;
            }
            lastMean = sum / (double) (MW * MH) / 255.0;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (onMeasure != null) onMeasure.run();
        }
        draw(outW, outH, false);
        EGLExt.eglPresentationTimeANDROID(dpy, surf, st.getTimestamp());
        EGL14.eglSwapBuffers(dpy, surf);
    }

    // measure = raw brightness (no digital gain/gamma) for auto exposure.
    void draw(int w, int h, boolean measure) {
        GLES20.glViewport(0, 0, w, h);
        GLES20.glUseProgram(prog);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0);
        double a = Math.toRadians(Settings.rot);
        float c = (float) Math.round(Math.cos(a)), s = (float) Math.round(Math.sin(a));
        GLES20.glUniformMatrix2fv(uRot, 1, false, new float[] {c, s, -s, c}, 0);
        double zoom = Math.max(1, Math.min(8, Settings.zoom));
        double travel = 0.5 - 0.5 / zoom;
        double px = Math.max(-1, Math.min(1, Settings.panX)) * travel;
        double py = Math.max(-1, Math.min(1, Settings.panY)) * travel;
        // Pan is given in viewer axes; turn it into source axes (y up in GL).
        double sx = c * px - s * -py, sy = s * px + c * -py;
        GLES20.glUniform2f(uCenter, (float) (0.5 + sx), (float) (0.5 + sy));
        GLES20.glUniform1f(uScale, (float) (1 / zoom));
        GLES20.glUniform1f(uMono, Settings.mono ? 1 : 0);
        GLES20.glUniform1f(uGain, measure ? 1 : (float) Settings.gain);
        GLES20.glUniform1f(uGamma, measure ? 1 : (float) Settings.gamma);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }
}
