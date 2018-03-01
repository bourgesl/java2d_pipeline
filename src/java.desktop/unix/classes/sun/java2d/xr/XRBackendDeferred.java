/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package sun.java2d.xr;

import java.awt.Point;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import sun.java2d.pipe.Region;
import static sun.java2d.xr.XRUtils.XDoubleToFixed;

/**
 * XRBackendDeferred
 *
 * @author Clemens Eisserer
 */
public class XRBackendDeferred extends XRBackendNative {

    private static final byte RENDER_CHANGE_PICTURE = 5;
    private static final byte RENDER_SET_PICTURE_CLIP_RECTANGLES = 6;
    private static final byte RENDER_FREE_PICTURE = 7;
    private static final byte RENDER_COMPOSITE = 8;
    private static final byte RENDER_FILL_RECTANGLES = 26;
    private static final byte RENDER_SET_PICTURE_TRANSFORM = 28;
    private static final byte RENDER_SET_PICTURE_FILTER = 30;
    private static final byte RENDER_COMPOSITE_GLYPH32 = 25;
    private static final byte RENDER_CREATE_LINEAR_GRADIENT = 34;
    private static final byte RENDER_CREATE_RADIAL_GRADIENT = 35;
    private static final byte FREE_PIXMAP = 54;

    private static final int BUFFER_SIZE = 128*1024; // 128K
    private static int RENDER_MAJOR_OPCODE;
    
    ByteBuffer buffer;
    
    boolean socketTaken;
    int requestCounter;
    int xcbReqSinceFlush;
   
    
    AATileBufMan aaTileMan;
    
    //TODO: Check for RadialGradient Correctness
    //TODO: Check for Text32 mask attribute
    
    public XRBackendDeferred() {  
        buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        nativeInit(buffer);
        
        socketTaken = false;
      
        aaTileMan = new AATileBufMan();
    }
    
    public void initResources(int parentXID) {
        aaTileMan.initResources(this, parentXID);
    }

    protected void takeSocket() {
        if (!socketTaken) {
            socketTaken = true;
           
            aaTileMan.pollPendingFences();
            
            long shmFenceSeq = takeSocketNative(aaTileMan.isFencePending());
            if(shmFenceSeq != -1) {
                aaTileMan.registerFenceSeqForActiveBuffer(shmFenceSeq);
            }
             
            xcbReqSinceFlush = 0;
        }
    }

    protected void releaseSocket() {
        if (socketTaken) {
            flushBuffer(true);
            socketTaken = false;
        }
    }

    protected void flushBuffer(boolean handoff) {
        if (requestCounter > 0) {
            AATileBuffer tileBuffer = aaTileMan.getActiveTileBuffer();
                        
           // if(System.getProperty("sun.java2d.debugxrdeferred") != null) {
           //     System.out.println("BufferFlush with AA tiles queued: " + tileBuffer.getTileCount() + " buffer: " + tileBuffer.getBufferBounds());
               // System.out.println("Flush xreq: "+xcbReqSinceFlush+ " req:"+requestCounter+" bytes: "+buffer.position());
           // }
            
            Point maskBufferBounds = tileBuffer.getBufferBounds();
            boolean useShmPutImg = tileBuffer.isUploadWithShmProfitable();
            
            releaseSocketNative(requestCounter, buffer.position(), aaTileMan.getMaskPixmapXid(), aaTileMan.getMaskGCPtr(), maskBufferBounds.x, 
                    maskBufferBounds.y, tileBuffer.getBufferScan(), tileBuffer.getYOffset(), useShmPutImg, tileBuffer.getByteBuffer());
            
           // System.out.println("was shm: " + shmMaskUsed);
            
            aaTileMan.markActiveBufferFlushed(useShmPutImg);
            
            buffer.clear();
            requestCounter = 0;
            
            if(!handoff)  {
                forceSocketReturn();
            }
        }
    }

    private native void releaseSocketNative(int requests, int bufferSize, int maskPixmap, long maskGC, int maskWidth, int maskHeight, int maskScan, int maskYOffset, boolean useShmPutImage, ByteBuffer buffer);

    private native long takeSocketNative(boolean queueShmFence);
    
    private static native void forceSocketReturn();

    private native void issueSyncReq();

    private native int generateXID();
    
    private native void nativeInit(ByteBuffer protoBuf);

    private void initNextRequest(int requestLength) {
        takeSocket();

        if (xcbReqSinceFlush > 65500) {
            issueSyncReq();
            takeSocket();
        }

        int maskTilesQueued = aaTileMan.getActiveTileBuffer().getTileCount();
        if ((maskTilesQueued == 0 && buffer.position() > 4 * 1024)
                || (buffer.position() + (requestLength * 4) > BUFFER_SIZE)) {
            flushBuffer(false);
        } 

        requestCounter++;
        xcbReqSinceFlush++;
    }

    @Override
    public void renderRectangle(int dst, byte op, XRColor color,
            int x, int y, int width, int height) {
      
        if(socketTaken) {
            initNextRequest(7);
            putRectHeader(dst, op, color, 7);

            buffer.putShort((short) x);
            buffer.putShort((short) y);
            buffer.putShort((short) width);
            buffer.putShort((short) height);
        } else {
            super.renderRectangle(dst, op, color, x, y, width, height);
        }
    }

    @Override
    public void renderRectangles(int dst, byte op, XRColor color,
            GrowableRectArray rects) { 
        int reqLen = 5 + 2 * rects.getSize();

        if (socketTaken && reqLen <= BUFFER_SIZE) {
            initNextRequest(reqLen);
            putRectHeader(dst, op, color, reqLen);
            putRects(rects);
        } else {
            super.renderRectangles(dst, op, color, rects);
        }
    }

    @Override
    public void setPictureRepeat(int picture, int repeat) {
        if(socketTaken) {
            initNextRequest(4);

            buffer.put((byte) RENDER_MAJOR_OPCODE);
            buffer.put(RENDER_CHANGE_PICTURE);
            buffer.putShort((short) 4);

            buffer.putInt(picture);
            buffer.putInt(1); //CPRepeat

            buffer.putInt(repeat);
        } else {
            super.setPictureRepeat(picture, repeat); 
        }
    }

    @Override
    public void setFilter(int picture, int filter) {
        if(socketTaken) {
            initNextRequest(4);

            buffer.put((byte) RENDER_MAJOR_OPCODE);
            buffer.put(RENDER_SET_PICTURE_FILTER);
            buffer.putShort((short) 4);

            buffer.putInt(picture);
            buffer.putShort((short) 4); //filtertLen
            buffer.putShort((short) 0); //pad

            buffer.put(XRUtils.getFilterName(filter));
        }else {
            super.setFilter(picture, filter);
        }
    }

    @Override
    public void renderComposite(byte op, int src, int mask,
            int dst, int srcX, int srcY,
            int maskX, int maskY, int dstX, int dstY,
            int width, int height) {
        
        if(socketTaken) {
            initNextRequest(9);

            buffer.put((byte) RENDER_MAJOR_OPCODE);
            buffer.put(RENDER_COMPOSITE);
            buffer.putShort((short) 9);
            buffer.put(op); //op

            //padding
            buffer.put((byte) 0);
            buffer.putShort((short) 0);

            buffer.putInt(src);
            buffer.putInt(mask);
            buffer.putInt(dst);

            buffer.putShort((short) srcX);
            buffer.putShort((short) srcY);
            buffer.putShort((short) maskX);
            buffer.putShort((short) maskY);
            buffer.putShort((short) dstX);
            buffer.putShort((short) dstY);
            buffer.putShort((short) width);
            buffer.putShort((short) height);
        } else {
             super.renderComposite(op, src, mask, dst, srcX, srcY, maskX, maskY, dstX, dstY, width, height);
        }
    }

    @Override
    public void maskedComposite(byte op, int src, int eaMask, int dst, 
            int srcX, int srcY, int dstX, int dstY, int width, 
            int height, int maskScan, int maskOff, byte[] mask, float ea) {
   
        if(mask == null) {
              renderComposite(op, src, eaMask, dst, srcX, srcY, 0, 0, dstX, dstY, width, height);
        } else {
            AATileBuffer tileBuffer = aaTileMan.getActiveTileBuffer();
            
            Point tilePos = tileBuffer.storeMaskTile(mask, width, height, maskOff, maskScan, ea);
            
            if(tilePos == null) {
                flushBuffer(false);    
// retry after flush ?
                maskedComposite(op, src, eaMask, dst, srcX, srcY, dstX, dstY, width, height, maskScan, maskOff, mask, ea);
                return;
            }  
            
            // Taking the socket here ensures, we emit the XRenderComposite ourself
            // so we can generate the XPutImage later when we have to hand the socket back to XCB
            takeSocket();
            renderComposite(op, src, aaTileMan.getMaskPictureXid(), dst, srcX, srcY, tilePos.x, tilePos.y, dstX, dstY, width, height);
        }        
    }
    

    @Override
    public void freePixmap(int pixmap) {
        if(socketTaken)  {
            initNextRequest(2);

            buffer.put(FREE_PIXMAP);
            buffer.put((byte) 0);
            buffer.putShort((short) 2);
            buffer.putInt(pixmap);
        } else {
            super.freePixmap(pixmap);
        }
    }

    @Override
    public void freePicture(int picture) {
        if(socketTaken) {
            initNextRequest(2);

            buffer.put((byte) RENDER_MAJOR_OPCODE);
            buffer.put(RENDER_FREE_PICTURE);
            buffer.putShort((short) 2);

            buffer.putInt(picture);
        } else {
            super.freePicture(picture);
        }
    }

    @Override
    public void setPictureTransform(int picture, AffineTransform transform) {
        if(socketTaken) {
            initNextRequest(11);

            buffer.put((byte) RENDER_MAJOR_OPCODE);
            buffer.put(RENDER_SET_PICTURE_TRANSFORM);
            buffer.putShort((short) 11);

            buffer.putInt(picture);

            buffer.putInt(XDoubleToFixed(transform.getScaleX()));
            buffer.putInt(XDoubleToFixed(transform.getShearX()));
            buffer.putInt(XDoubleToFixed(transform.getTranslateX()));
            buffer.putInt(XDoubleToFixed(transform.getShearY()));
            buffer.putInt(XDoubleToFixed(transform.getScaleY()));
            buffer.putInt(XDoubleToFixed(transform.getTranslateY()));
            buffer.putInt(0);
            buffer.putInt(0);
            buffer.putInt(1 << 16);
        } else {
             super.setPictureTransform(picture, transform);           
        }
    }

    @Override
    public int createLinearGradient(Point2D p1, Point2D p2, float[] fractions,
            int[] pixels, int repeat) {
        int reqLen = 7 + fractions.length + 2 * pixels.length;

        if (socketTaken && reqLen < BUFFER_SIZE) {

            int xid = generateXID();

            initNextRequest(reqLen);

            buffer.put((byte) RENDER_MAJOR_OPCODE);
            buffer.put(RENDER_CREATE_LINEAR_GRADIENT);
            buffer.putShort((short) reqLen);

            buffer.putInt(xid);

            buffer.putInt(XDoubleToFixed(p1.getX()));
            buffer.putInt(XDoubleToFixed(p1.getY()));
            buffer.putInt(XDoubleToFixed(p2.getX()));
            buffer.putInt(XDoubleToFixed(p2.getY()));

            putFractions(fractions);

            putPixels(pixels);

            return xid;
        } else {
            return super.createLinearGradient(p1, p2, fractions, pixels, repeat);
        }
    }

    @Override
    public int createRadialGradient(float centerX, float centerY,
            float innerRadius, float outerRadius,
            float[] fractions, int[] pixels, int repeat) {

        int reqLen = 8 + fractions.length + 2 * pixels.length;

        if (socketTaken && reqLen < BUFFER_SIZE) {
            int xid = generateXID();

            initNextRequest(reqLen);

            buffer.put((byte) RENDER_MAJOR_OPCODE);
            buffer.put(RENDER_CREATE_RADIAL_GRADIENT);
            buffer.putShort((short) reqLen);

            buffer.putInt(xid);

            //inner
            buffer.putInt(XDoubleToFixed(centerX));
            buffer.putInt(XDoubleToFixed(centerY));
            //outer
            buffer.putInt(XDoubleToFixed(centerX));
            buffer.putInt(XDoubleToFixed(centerY));

            buffer.putInt(XDoubleToFixed(innerRadius));
            buffer.putInt(XDoubleToFixed(outerRadius));

            putFractions(fractions);
            putPixels(pixels);

            return xid;
        } else {
            return super.createRadialGradient(centerX, centerY, innerRadius, outerRadius, fractions, pixels, repeat);
        }
    }

    // @Override
    public void XRenderCompositeText(byte op, int src, int dst,
            int maskFormatID,
            int sx, int sy, int dx, int dy,
            int glyphset, GrowableEltArray elts) {

        if(!socketTaken) {
            super.XRenderCompositeText(op, src, dst, maskFormatID, sx, sy, dx, dy, glyphset, elts);
            return;
        }  
      
        // super.XRenderCompositeText(op, src, dst, maskFormatID, sx, sy, dx, dy, glyphset, elts);
        int len = 7 + elts.getGlyphs().getSize() + elts.getSize() * 2;
        int activeGlyphSet = elts.getGlyphSet(0);

        //calculate request length
        for (int elt = 0; elt < elts.getSize(); elt++) {
            int newGlyphSet = elts.getGlyphSet(elt);
            if (activeGlyphSet != newGlyphSet) {
                len += 3;
            }
        }
        activeGlyphSet = elts.getGlyphSet(0);

        if (len <= BUFFER_SIZE) {
            initNextRequest(len);

            buffer.put((byte) RENDER_MAJOR_OPCODE);
            buffer.put(RENDER_COMPOSITE_GLYPH32);
            buffer.putShort((short) len);

            //TODO: Implement glyphset change!top
            buffer.put(op); //op

            //padding
            buffer.put((byte) 0);
            buffer.putShort((short) 0);

            buffer.putInt(src);
            buffer.putInt(dst);
            buffer.putInt(0);

            buffer.putInt(activeGlyphSet);

            buffer.putShort(XRUtils.clampToShort(sx));
            buffer.putShort(XRUtils.clampToShort(sy));

            int glyphsWritten = 0;

            for (int elt = 0; elt < elts.getSize(); elt++) {
                int newGlyphSet = elts.getGlyphSet(elt);
                if (activeGlyphSet != newGlyphSet) {
                    putGlyphSet(255, 0, 0);
                    buffer.putInt(newGlyphSet);
                }

                putGlyphSet(elts.getCharCnt(elt), elts.getXOff(elt), elts.getYOff(elt));

                for (int g = 0; g < elts.getCharCnt(elt); g++) {
                    buffer.putInt(elts.getGlyphs().getInt(glyphsWritten++));
                }
            }
        } else {
            super.XRenderCompositeText(op, src, dst, maskFormatID, sx, sy, dx, dy, glyphset, elts);
        }
    }

    @Override
    public void setClipRectangles(int picture, Region clip) {
        if (socketTaken && clip == null) {
            XRSetClipRectangle(picture, 0, 0, 32767, 32767);
        } else if (socketTaken && clip.isRectangular()) {
            XRSetClipRectangle(picture, clip.getLoX(), clip.getLoY(),
                    clip.getHiX(), clip.getHiY());
        } else {
            super.setClipRectangles(picture, clip);
        }
    }
    
    private void putFractions(float[] fractions) {
        buffer.putInt(fractions.length);
        for (int i = 0; i < fractions.length; i++) {
            buffer.putInt(XRUtils.XDoubleToFixed(fractions[i]));
        }
    }

    private void putPixels(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            XRColor c = new XRColor();
            c.setColorValues(pixels[i]);
            buffer.putShort((short) c.red);
            buffer.putShort((short) c.green);
            buffer.putShort((short) c.blue);
            buffer.putShort((short) c.alpha);
        }
    }
    
    private void putGlyphSet(int charCnt, int xOff, int yOff) {
        buffer.put((byte) charCnt);
        //padding
        buffer.put((byte) 0);
        buffer.putShort((short) 0);

        buffer.putShort((short) xOff);
        buffer.putShort((short) yOff);
    }

    private void XRSetClipRectangle(int picture, int x, int y, int x2, int y2) {
        initNextRequest(5);

        // System.out.println("Set clip: "+x+"/"+y+" "+width+"/"+height);
        buffer.put((byte) RENDER_MAJOR_OPCODE);
        buffer.put(RENDER_SET_PICTURE_CLIP_RECTANGLES);
        buffer.putShort((short) 5);

        buffer.putInt(picture);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);

        buffer.putShort(XRUtils.clampToShort(x));
        buffer.putShort(XRUtils.clampToShort(y));
        buffer.putShort((short) XRUtils.clampToUShort((x2 - x)));
        buffer.putShort((short) XRUtils.clampToUShort((y2 - y)));
    }

    private void putRectHeader(int dst, byte op, XRColor color, int reqLen) {
        buffer.put((byte) RENDER_MAJOR_OPCODE);
        buffer.put(RENDER_FILL_RECTANGLES);
        buffer.putShort((short) reqLen);
        buffer.put(op); //op

        //padding
        buffer.put((byte) 0);
        buffer.putShort((short) 0);

        buffer.putInt(dst);

        putXRColor(color);
    }

    private void putRects(GrowableRectArray rects) {
        for (int i = 0; i < rects.getSize(); i++) {
            buffer.putShort((short) rects.getX(i));
            buffer.putShort((short) rects.getY(i));
            buffer.putShort((short) rects.getWidth(i));
            buffer.putShort((short) rects.getHeight(i));
        }
    }

    private void putXRColor(XRColor color) {
        buffer.putShort((short) color.red);
        buffer.putShort((short) color.green);
        buffer.putShort((short) color.blue);
        buffer.putShort((short) color.alpha);
    }
}
