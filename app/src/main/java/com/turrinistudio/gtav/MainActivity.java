package com.turrinistudio.gtav;

import android.app.Activity;
import android.os.Bundle;
import android.opengl.GLSurfaceView;
import android.opengl.GLES30;
import android.view.*;
import android.widget.*;
import android.graphics.Color;
import java.nio.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity {
    static { System.loadLibrary("rage"); }

    public native void nativeInit();
    public native void nativeUpdate(float dt, boolean gas, boolean brake, boolean left, boolean right);
    public native float nativeX(), nativeY(), nativeZ(), nativeYaw(), nativePitch(), nativeRoll(), nativeSpeed();

    volatile boolean gas, brake, left, right;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        nativeInit();

        GLSurfaceView view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(3);
        view.setRenderer(new Renderer());
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        FrameLayout root = new FrameLayout(this);
        root.addView(view);

        TextView hud = new TextView(this);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(18);
        hud.setShadowLayer(5,0,0,Color.BLACK);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2,-2);
        hp.leftMargin=24; hp.topMargin=20;
        root.addView(hud,hp);

        Button gasB = button("GAS");
        Button brakeB = button("BRAKE");
        Button leftB = button("◀");
        Button rightB = button("▶");

        touch(gasB, v -> gas=v);
        touch(brakeB, v -> brake=v);
        touch(leftB, v -> left=v);
        touch(rightB, v -> right=v);

        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(18,18,18,28);
        controls.addView(leftB);
        controls.addView(rightB);
        controls.addView(gasB);
        controls.addView(brakeB);

        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,180);
        cp.gravity=Gravity.BOTTOM;
        root.addView(controls,cp);

        setContentView(root);

        new Thread(() -> {
            while(true) {
                final String s=String.format("TURRINI REALISTIC DRIVE\n%.0f KM/H",nativeSpeed());
                runOnUiThread(() -> hud.setText(s));
                try { Thread.sleep(100); } catch(Exception e) {}
            }
        }).start();
    }

    Button button(String s){
        Button b=new Button(this); b.setText(s); b.setTextSize(18); b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.argb(180,20,20,20));
        b.setMinWidth(120); return b;
    }

    void touch(Button b, java.util.function.Consumer<Boolean> f){
        b.setOnTouchListener((v,e)->{
            int a=e.getActionMasked();
            if(a==MotionEvent.ACTION_DOWN) f.accept(true);
            if(a==MotionEvent.ACTION_UP || a==MotionEvent.ACTION_CANCEL) f.accept(false);
            return true;
        });
    }

    class Renderer implements GLSurfaceView.Renderer {
        int program, uMVP, uColor;
        FloatBuffer cube;
        long last=System.nanoTime();

        final String VS =
            "#version 300 es\nlayout(location=0) in vec3 aPos;\n"+
            "uniform mat4 uMVP; void main(){gl_Position=uMVP*vec4(aPos,1.0);}";

        final String FS =
            "#version 300 es\nprecision mediump float;\n"+
            "uniform vec4 uColor; out vec4 frag; void main(){frag=uColor;}";

        public void onSurfaceCreated(GL10 gl,EGLConfig c){
            program=makeProgram(VS,FS);
            uMVP=GLES30.glGetUniformLocation(program,"uMVP");
            uColor=GLES30.glGetUniformLocation(program,"uColor");
            float[] v={
                -1,-1,-1, 1,-1,-1, 1,1,-1, -1,1,-1,
                -1,-1,1, 1,-1,1, 1,1,1, -1,1,1
            };
            ByteBuffer bb=ByteBuffer.allocateDirect(v.length*4).order(ByteOrder.nativeOrder());
            cube=bb.asFloatBuffer(); cube.put(v).position(0);
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glClearColor(.035f,.055f,.08f,1);
        }

        public void onSurfaceChanged(GL10 gl,int w,int h){ GLES30.glViewport(0,0,w,h); }

        public void onDrawFrame(GL10 gl){
            long now=System.nanoTime();
            float dt=Math.min(.033f,(now-last)/1e9f); last=now;
            nativeUpdate(dt,gas,brake,left,right);

            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
            GLES30.glUseProgram(program);

            // Simple mobile-friendly perspective/camera using CPU matrices.
            float yaw=nativeYaw(), x=nativeX(), z=nativeZ(), y=nativeY();
            float[] p=perspective(65,16f/9f,.1f,600);
            float[] view=lookAt(x-(float)Math.sin(yaw)*7, y+4.0f, z-(float)Math.cos(yaw)*7,
                                x,y+0.8f,z);
            float[] vp=mul(p,view);

            // Procedural terrain patches.
            for(int ix=-12;ix<=12;ix++) for(int iz=-12;iz<=12;iz++){
                float wx=ix*18, wz=iz*18;
                float h=(float)(Math.sin(wx*.035)*1.8+Math.cos(wz*.045)*1.5+
                                Math.sin((wx+wz)*.018)*3.0);
                draw(vp,wx,h-1.2f,wz,9,0.5f,9,.10f,.28f,.12f);
            }

            // Road.
            for(int i=-40;i<=40;i++){
                float wz=i*6;
                float h=(float)(Math.sin(wz*.045)*1.5+Math.cos(wz*.02)*1.0);
                draw(vp,0,h+.05f,wz,3.3f,.08f,3.0f,.15f,.15f,.16f);
            }

            // Vehicle body.
            drawRot(vp,x,y+.75f,z,1.9f,.7f,3.4f,yaw,.72f,.06f,.04f);
            drawRot(vp,x,y+1.25f,z-.15f,1.55f,.5f,1.65f,yaw,.08f,.12f,.16f);

            // Four wheels.
            float sx=.92f, sz=1.25f;
            wheel(vp,x,y+.35f,z+sz,yaw,sx);
            wheel(vp,x,y+.35f,z+sz,yaw,-sx);
            wheel(vp,x,y+.35f,z-sz,yaw,sx);
            wheel(vp,x,y+.35f,z-sz,yaw,-sx);
        }

        void wheel(float[] vp,float x,float y,float z,float yaw,float sx){
            float wx=x+(float)Math.cos(yaw)*sx;
            float wz=z-(float)Math.sin(yaw)*sx;
            drawRot(vp,wx,y,wz,.38f,.38f,.22f,yaw,.03f,.03f,.03f);
        }

        int makeProgram(String vs,String fs){
            int a=GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER);
            GLES30.glShaderSource(a,vs); GLES30.glCompileShader(a);
            int b=GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER);
            GLES30.glShaderSource(b,fs); GLES30.glCompileShader(b);
            int p=GLES30.glCreateProgram(); GLES30.glAttachShader(p,a); GLES30.glAttachShader(p,b);
            GLES30.glLinkProgram(p); return p;
        }

        void draw(float[] vp,float x,float y,float z,float sx,float sy,float sz,float r,float g,float b){
            drawRot(vp,x,y,z,sx,sy,sz,0,r,g,b);
        }
        void drawRot(float[] vp,float x,float y,float z,float sx,float sy,float sz,float ry,float r,float g,float b){
            float[] m=identity(); translate(m,x,y,z); rotateY(m,ry); scale(m,sx,sy,sz);
            float[] mvp=mul(vp,m);
            GLES30.glUniformMatrix4fv(uMVP,1,false,mvp,0);
            GLES30.glUniform4f(uColor,r,g,b,1);
            GLES30.glEnableVertexAttribArray(0); cube.position(0);
            GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,0,cube);
            int[] faces={0,4,8,0,4,8};
            for(int i=0;i<6;i++) GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN,0,4);
        }

        float[] identity(){ float[] a=new float[16]; a[0]=a[5]=a[10]=a[15]=1; return a; }
        void translate(float[]m,float x,float y,float z){m[12]+=x;m[13]+=y;m[14]+=z;}
        void scale(float[]m,float x,float y,float z){m[0]*=x;m[5]*=y;m[10]*=z;}
        void rotateY(float[]m,float a){float c=(float)Math.cos(a),s=(float)Math.sin(a);float a0=m[0],a2=m[2];m[0]=a0*c+a2*s;m[2]=a0*-s+a2*c;}
        float[] mul(float[]a,float[]b){float[]r=new float[16];for(int i=0;i<4;i++)for(int j=0;j<4;j++)for(int k=0;k<4;k++)r[i*4+j]+=a[i*4+k]*b[k*4+j];return r;}
        float[] perspective(float f,float asp,float n,float far){float[]m=new float[16];float t=(float)(1/Math.tan(Math.toRadians(f)/2));m[0]=t/asp;m[5]=t;m[10]=(far+n)/(n-far);m[11]=-1;m[14]=2*far*n/(n-far);return m;}
        float[] lookAt(float ex,float ey,float ez,float cx,float cy,float cz){
            float fx=cx-ex,fy=cy-ey,fz=cz-ez;float fl=(float)Math.sqrt(fx*fx+fy*fy+fz*fz);fx/=fl;fy/=fl;fz/=fl;
            float rx=fz,ry=0,rz=-fx;float rl=(float)Math.sqrt(rx*rx+rz*rz);rx/=rl;rz/=rl;
            float ux=ry*fz-rz*fy,uy=rz*fx-rx*fz,uz=rx*fy-ry*fx;
            float[]m=identity();m[0]=rx;m[1]=ux;m[2]=-fx;m[4]=ry;m[5]=uy;m[6]=-fy;m[8]=rz;m[9]=uz;m[10]=-fz;m[12]=-(rx*ex+ry*ey+rz*ez);m[13]=-(ux*ex+uy*ey+uz*ez);m[14]=fx*ex+fy*ey+fz*ez;return m;
        }
    }
  }
