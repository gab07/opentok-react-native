package com.opentokreactnative.utils;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;
import android.view.View;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactContext;
import com.opentok.android.BaseVideoRenderer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.locks.ReentrantLock;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class ScreenshotVideoRenderer extends BaseVideoRenderer {

    private final static String TAG = ScreenshotVideoRenderer.class.getSimpleName();

    ReactContext context;
    GLSurfaceView view;
    MyRenderer renderer;

    static class MyRenderer implements GLSurfaceView.Renderer {
        int textureIds[] = new int[3];
        float[] scaleMatrix = new float[16];

        private FloatBuffer vertexBuffer;
        private FloatBuffer textureBuffer;
        private ShortBuffer drawListBuffer;

        boolean videoFitEnabled = true;
        boolean videoDisabled = false;

        // number of coordinates per vertex in this array
        static final int COORDS_PER_VERTEX = 3;
        static final int TEXTURE_COORDS_PER_VERTEX = 2;
        private static final int PREALLOCATE_SIZE = 64 * 1024;

        static float xyzCoords[] = {-1.0f, 1.0f, 0.0f, // top left
                -1.0f, -1.0f, 0.0f, // bottom left
                1.0f, -1.0f, 0.0f, // bottom right
                1.0f, 1.0f, 0.0f // top right
        };

        static float uvCoords[] = {0, 0, // top left
                0, 1, // bottom left
                1, 1, // bottom right
                1, 0}; // top right

        private short vertexIndex[] = {0, 1, 2, 0, 2, 3}; // order to draw
        // vertices

        private final String vertexShaderCode = "uniform mat4 uMVPMatrix;"
                + "attribute vec4 aPosition;\n"
                + "attribute vec2 aTextureCoord;\n"
                + "varying vec2 vTextureCoord;\n" + "void main() {\n"
                + "  gl_Position = uMVPMatrix * aPosition;\n"
                + "  vTextureCoord = aTextureCoord;\n" + "}\n";

        private final String fragmentShaderCode = "precision mediump float;\n"
                + "uniform sampler2D Ytex;\n"
                + "uniform sampler2D Utex,Vtex;\n"
                + "varying vec2 vTextureCoord;\n"
                + "void main(void) {\n"
                + "  float nx,ny,r,g,b,y,u,v;\n"
                + "  mediump vec4 txl,ux,vx;"
                + "  nx=vTextureCoord[0];\n"
                + "  ny=vTextureCoord[1];\n"
                + "  y=texture2D(Ytex,vec2(nx,ny)).r;\n"
                + "  u=texture2D(Utex,vec2(nx,ny)).r;\n"
                + "  v=texture2D(Vtex,vec2(nx,ny)).r;\n"

                //+ "  y=1.0-1.1643*(y-0.0625);\n" // Invert effect
                + "  y=1.1643*(y-0.0625);\n" // Normal renderer

                + "  u=u-0.5;\n" + "  v=v-0.5;\n" + "  r=y+1.5958*v;\n"
                + "  g=y-0.39173*u-0.81290*v;\n" + "  b=y+2.017*u;\n"
                + "  gl_FragColor=vec4(r,g,b,1.0);\n" + "}\n";

        ReentrantLock frameLock = new ReentrantLock();
        Frame currentFrame;

        private int program;
        private int textureWidth;
        private int textureHeight;
        private int viewportWidth;
        private int viewportHeight;
        private boolean saveScreenshot;
        private Promise promise;
        private static byte[] outputBuffer = new byte[PREALLOCATE_SIZE];

        public MyRenderer() {
            ByteBuffer bb = ByteBuffer.allocateDirect(xyzCoords.length * 4);
            bb.order(ByteOrder.nativeOrder());
            vertexBuffer = bb.asFloatBuffer();
            vertexBuffer.put(xyzCoords);
            vertexBuffer.position(0);

            ByteBuffer tb = ByteBuffer.allocateDirect(uvCoords.length * 4);
            tb.order(ByteOrder.nativeOrder());
            textureBuffer = tb.asFloatBuffer();
            textureBuffer.put(uvCoords);
            textureBuffer.position(0);

            ByteBuffer dlb = ByteBuffer.allocateDirect(vertexIndex.length * 2);
            dlb.order(ByteOrder.nativeOrder());
            drawListBuffer = dlb.asShortBuffer();
            drawListBuffer.put(vertexIndex);
            drawListBuffer.position(0);
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER,
                    vertexShaderCode);
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER,
                    fragmentShaderCode);

            program = GLES20.glCreateProgram(); // create empty OpenGL ES
            // Program
            GLES20.glAttachShader(program, vertexShader); // add the vertex
            // shader to program
            GLES20.glAttachShader(program, fragmentShader); // add the fragment
            // shader to
            // program
            GLES20.glLinkProgram(program);

            int positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            int textureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");

            GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX,
                    GLES20.GL_FLOAT, false, COORDS_PER_VERTEX * 4,
                    vertexBuffer);

            GLES20.glEnableVertexAttribArray(positionHandle);

            GLES20.glVertexAttribPointer(textureHandle,
                    TEXTURE_COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                    TEXTURE_COORDS_PER_VERTEX * 4, textureBuffer);

            GLES20.glEnableVertexAttribArray(textureHandle);

            GLES20.glUseProgram(program);
            int i = GLES20.glGetUniformLocation(program, "Ytex");
            GLES20.glUniform1i(i, 0); /* Bind Ytex to texture unit 0 */

            i = GLES20.glGetUniformLocation(program, "Utex");
            GLES20.glUniform1i(i, 1); /* Bind Utex to texture unit 1 */

            i = GLES20.glGetUniformLocation(program, "Vtex");
            GLES20.glUniform1i(i, 2); /* Bind Vtex to texture unit 2 */

            textureWidth = 0;
            textureHeight = 0;
        }

        static void initializeTexture(int name, int id, int width, int height) {
            GLES20.glActiveTexture(name);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                    width, height, 0, GLES20.GL_LUMINANCE,
                    GLES20.GL_UNSIGNED_BYTE, null);
        }

        void setupTextures(Frame frame) {
            if (textureIds[0] != 0) {
                GLES20.glDeleteTextures(3, textureIds, 0);
            }
            GLES20.glGenTextures(3, textureIds, 0);

            int w = frame.getWidth();
            int h = frame.getHeight();
            int hw = (w + 1) >> 1;
            int hh = (h + 1) >> 1;

            initializeTexture(GLES20.GL_TEXTURE0, textureIds[0], w, h);
            initializeTexture(GLES20.GL_TEXTURE1, textureIds[1], hw, hh);
            initializeTexture(GLES20.GL_TEXTURE2, textureIds[2], hw, hh);

            textureWidth = frame.getWidth();
            textureHeight = frame.getHeight();
        }

        void updateTextures(Frame frame) {
            int width = frame.getWidth();
            int height = frame.getHeight();
            int half_width = (width + 1) >> 1;
            int half_height = (height + 1) >> 1;
            int y_size = width * height;
            int uv_size = half_width * half_height;

            ByteBuffer bb = frame.getBuffer();
            // If we are reusing this frame, make sure we reset position and
            // limit
            bb.clear();

            if (bb.remaining() == y_size + uv_size * 2) {
                bb.position(0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0]);
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width,
                        height, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
                        bb);

                bb.position(y_size);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[1]);
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                        half_width, half_height, GLES20.GL_LUMINANCE,
                        GLES20.GL_UNSIGNED_BYTE, bb);

                bb.position(y_size + uv_size);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[2]);
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                        half_width, half_height, GLES20.GL_LUMINANCE,
                        GLES20.GL_UNSIGNED_BYTE, bb);
            } else {
                textureWidth = 0;
                textureHeight = 0;
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            viewportWidth = width;
            viewportHeight = height;
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            frameLock.lock();
            if (currentFrame != null && !videoDisabled) {
                GLES20.glUseProgram(program);

                if (textureWidth != currentFrame.getWidth()
                        || textureHeight != currentFrame.getHeight()) {
                    setupTextures(currentFrame);
                }
                updateTextures(currentFrame);

                Matrix.setIdentityM(scaleMatrix, 0);
                float scaleX = 1.0f, scaleY = 1.0f;
                float ratio = (float) currentFrame.getWidth()
                        / currentFrame.getHeight();
                float vratio = (float) viewportWidth / viewportHeight;

                if (videoFitEnabled) {
                    if (ratio > vratio) {
                        scaleY = vratio / ratio;
                    } else {
                        scaleX = ratio / vratio;
                    }
                } else {
                    if (ratio < vratio) {
                        scaleY = vratio / ratio;
                    } else {
                        scaleX = ratio / vratio;
                    }
                }

                Matrix.scaleM(scaleMatrix, 0,
                        scaleX * (currentFrame.isMirroredX() ? -1.0f : 1.0f),
                        scaleY, 1);

                int mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
                GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, scaleMatrix, 0);

                GLES20.glDrawElements(GLES20.GL_TRIANGLES, vertexIndex.length, GLES20.GL_UNSIGNED_SHORT, drawListBuffer);
            }

            frameLock.unlock();
        }

        public void displayFrame(Frame frame) {
            frameLock.lock();

            if (currentFrame != null) {
                currentFrame.destroy(); // Disposes previous frame
            }

            currentFrame = frame;
            frameLock.unlock();

            if (saveScreenshot && promise != null) {
                Log.d(TAG, "Screenshot capture");

                ByteBuffer bb = frame.getBuffer();
                bb.clear();

                int width = frame.getWidth();
                int height = frame.getHeight();
                int half_width = (width + 1) >> 1;
                int half_height = (height + 1) >> 1;
                int y_size = width * height;
                int uv_size = half_width * half_height;

                byte[] yuv = new byte[y_size + uv_size * 2];
                bb.get(yuv);
                int[] intArray = new int[width * height];

                // Decode Yuv data to integer array
                decodeYUV420(intArray, yuv, width, height);

                // Initialize the bitmap, with the replaced color
                Bitmap bmp = Bitmap.createBitmap(intArray, width, height, Bitmap.Config.ARGB_8888);

                try {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
                    byte[] byteArray = byteArrayOutputStream .toByteArray();
                    String encoded = Base64.encodeToString(byteArray, Base64.DEFAULT);

                    promise.resolve(encoded);

                    byteArrayOutputStream.flush();
                    byteArrayOutputStream.close();
                } catch (Exception e) {
                    e.printStackTrace();
                    promise.reject("Unable to snapshot", e);
                    return;
                }

                saveScreenshot = false;
                promise = null;
            }
        }

        static public void decodeYUV420(int[] rgba, byte[] yuv420, int width, int height) {
            int halfWidth = (width + 1) >> 1;
            int halfHeight = (height + 1) >> 1;
            int ySize = width * height;
            int uvSize = halfWidth * halfHeight;

            for (int j = 0; j < height; j++) {
                for (int i = 0; i < width; i++) {

                    double y = (yuv420[j * width + i]) & 0xff;
                    double v = (yuv420[ySize + (j >> 1) * halfWidth + (i >> 1)]) & 0xff;
                    double u = (yuv420[ySize + uvSize + (j >> 1) * halfWidth + (i >> 1)]) & 0xff;

                    double r;
                    double g;
                    double b;

                    r = y + 1.402 * (u - 128);
                    g = y - 0.34414 * (v - 128) - 0.71414 * (u - 128);
                    b = y + 1.772 * (v - 128);

                    if (r < 0) r = 0;
                    else if (r > 255) r = 255;
                    if (g < 0) g = 0;
                    else if (g > 255) g = 255;
                    if (b < 0) b = 0;
                    else if (b > 255) b = 255;

                    int ir = (int) r;
                    int ig = (int) g;
                    int ib = (int) b;
                    rgba[j * width + i] = 0xff000000 | (ir << 16) | (ig << 8) | ib;
                }
            }
        }


        public static int loadShader(int type, String shaderCode) {
            int shader = GLES20.glCreateShader(type);

            GLES20.glShaderSource(shader, shaderCode);
            GLES20.glCompileShader(shader);

            return shader;
        }

        public void disableVideo(boolean b) {
            frameLock.lock();

            videoDisabled = b;

            if (videoDisabled) {
                this.currentFrame = null;
            }

            frameLock.unlock();
        }

        public void enableVideoFit(boolean enableVideoFit) {
            videoFitEnabled = enableVideoFit;
        }

        public void saveScreenshot(Promise promise) {
            saveScreenshot = true;
            this.promise = promise;
        }
    };

    public ScreenshotVideoRenderer(ReactContext context) {
        this.context = context;

        view = new GLSurfaceView(context);
        view.setEGLContextClientVersion(2);

        renderer = new MyRenderer();
        view.setRenderer(renderer);

        view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void onFrame(Frame frame) {
        renderer.displayFrame(frame);
        view.requestRender();
    }

    @Override
    public void setStyle(String key, String value) {
        if (BaseVideoRenderer.STYLE_VIDEO_SCALE.equals(key)) {
            if (BaseVideoRenderer.STYLE_VIDEO_FIT.equals(value)) {
                renderer.enableVideoFit(true);
            } else if (BaseVideoRenderer.STYLE_VIDEO_FILL.equals(value)) {
                renderer.enableVideoFit(false);
            }
        }
    }

    @Override
    public void onVideoPropertiesChanged(boolean videoEnabled) {
        renderer.disableVideo(!videoEnabled);
    }

    @Override
    public View getView() {
        return view;
    }

    @Override
    public void onPause() {
        view.onPause();
    }

    @Override
    public void onResume() {
        view.onResume();
    }

    public void saveScreenshot(Promise promise) {
        renderer.saveScreenshot(promise);
    }
}