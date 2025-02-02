package com.medivh.blurview.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.locks.ReentrantLock;

public class DoubleCacheSurfaceRender extends SurfaceRender {

    private final Deque<Bitmap> bufferQueue = new LinkedList<>();
    private final ReentrantLock queueLock = new ReentrantLock();
    private final Object renderThreadLock = new Object();

    private Thread renderThread;

    private boolean stopThread = false;
    private boolean isRenderIdle = true;

    public DoubleCacheSurfaceRender(Context context) {
        this.context = context;
        init();
    }

    private void init() {
        HandlerThread blurThread = new HandlerThread("BlurThread");
        blurThread.setPriority(Thread.MAX_PRIORITY);
        blurThread.start();

        blurHandler = new Handler(blurThread.getLooper()) {

            @Override
            public void handleMessage(@NonNull Message msg) {
                long begin = System.currentTimeMillis();
                Bitmap background = (Bitmap) msg.obj;
                if (background == null) {
                    return;
                }

                Bitmap copy = Bitmap.createBitmap(background);
                Bitmap blurBackground = blurRenderScript.blurBitmap(context, copy, BlurLayout.BLUR_RADIUS);
                Log.i("BlurLayout", "cost:" + (System.currentTimeMillis() - begin) + "  blur bitmap");

                queueLock.lock();
                if (bufferQueue.size() > 1) {
                    bufferQueue.removeFirst().recycle();
                }

                bufferQueue.offer(blurBackground);
                Log.i("BlurLayout", "bufferQueue size:" + bufferQueue.size());

                queueLock.unlock();

                if (isRenderIdle) {
                    synchronized (renderThreadLock) {
                        isRenderIdle = false;
                        renderThreadLock.notify();
                    }
                }

                copy.recycle();
            }
        };
    }

    @Override
    public void surfaceCreated(@NonNull final SurfaceHolder holder) {
        super.surfaceCreated(holder);
    }

    @Override
    void drawFrame(final SurfaceHolder holder) {

        renderThread = new Thread(new Runnable() {
            final Paint paint = new Paint();

            @Override
            public void run() {
                while (true) {
                    if (stopThread) {
                        return;
                    }
                    if (bufferQueue.size() == 0) {
                        try {
                            synchronized (renderThreadLock) {
                                isRenderIdle = true;
                                Log.i("BlurLayout", "renderThreadLock.wait");
                                renderThreadLock.wait();
                            }

                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    long begin = System.currentTimeMillis();

                    queueLock.lock();
                    Bitmap blurBackground = bufferQueue.poll();
                    queueLock.unlock();

                    if (blurBackground == null) {
                        continue;
                    }

                    Canvas canvas = lockCanvas();

                    if (canvas != null) {
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                        canvas.drawBitmap(blurBackground, null, dst, paint);
                        if(coverColor != 0){
                            canvas.drawColor(coverColor);
                        }
                    }

                    unlockCanvasAndPost(canvas);
                    blurBackground.recycle();

                    Log.i("BlurLayout", (System.currentTimeMillis() - begin) + " draw surfaceView ");
                }

            }
        });

        renderThread.setPriority(Thread.MAX_PRIORITY);
        renderThread.start();

    }

    @Override
    void release() {
        if (blurHandler != null) {
            blurHandler.getLooper().quit();
            stopThread = true;
        }
    }
}
