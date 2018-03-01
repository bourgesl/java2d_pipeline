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
import java.nio.ByteBuffer;

/**
 * Buffer for uploading AA mask tile data.
 * Supports uploading multiple AA tiles within one buffer, by simply appending
 * the tiles in x-direction and starting a new line once the remaining width
 * is not sufficient anymore.
 *
 * @author Clemens Eisserer
 */
public class AATileBuffer {
    private final static int SHM_THRESHOLD = 8192;
    private final static int SHM_THRESHOLD2 = 16384;
    
    final int bufferWidth;
    final int bufferHeight;
    final int bufferScan;
    final int yOffset;
    
    final AATileBufMan bufMan;
    final ByteBuffer buffer;
    final int bufferId; 
    final boolean shmCapable;
    
    int tileCount;
    int currY;
    int currX;
    int currentRowHeight;
    int maxRowWidth;
    
    public AATileBuffer(AATileBufMan bufMan, int bufferWidth, int bufferHeight, int bufferScan, int yOffset, int bufferId, ByteBuffer buffer, boolean shmCapable) {
    	this.bufMan = bufMan;
        this.bufferWidth = bufferWidth;
    	this.bufferHeight = bufferHeight;
        this.bufferScan = bufferScan;
        this.shmCapable = shmCapable;
        
        this.yOffset = yOffset;
        this.bufferId = bufferId;
        
        this.buffer = buffer;
    }
    
    public Point storeTile(int width, int height)  {
    	int dstX;
    	
    	if(bufferHeight - currY - currentRowHeight < height) {
    		return null;
    	}
    	
        if(bufferHeight - currX > width) {
           dstX = currX;
           currX += width;
           currentRowHeight = Math.max(currentRowHeight, height);
           maxRowWidth = Math.max(maxRowWidth, currX);
        } else
        {
          dstX = 0;
          currX = width;
          currY += currentRowHeight;            
          currentRowHeight = height;
        }
        
        tileCount++;
        
        return new Point(dstX, currY);
    }
    
    public int getTileCount() {
    	return tileCount;
    }
    
    public void reset() {
    	currX = 0;
    	currY = 0;
    	currentRowHeight = 0;
    	maxRowWidth = 0;
    	tileCount = 0;
    }
    
    public Point getBufferBounds() {
    	return new Point(maxRowWidth, currY + currentRowHeight);
    }
    
    public Point storeMaskTile(byte[] tileData, int width, int height, int maskOff,
            int tileScan, float ea) {
        
        Point pt = storeTile(width, height);
        if(pt == null) {
            return null;
        }
        
        /*
        if(ea < 0.99804687f) {
            for(int y=0; y < height; y++) {
               for(int x = 0; x < width; x++) {
                   tileData[]
               }
            }
        } */
        
        for(int y=0; y < height; y++) {
            int srcPos = tileScan * y  + maskOff;
            int dstPos = bufferScan * (pt.y + y) + pt.x;
            
            buffer.position(dstPos);
            buffer.put(tileData, srcPos, width);
        }
        
        return pt;
    }
    
    public ByteBuffer getByteBuffer() {
        return buffer;
    }
    
    public int getBufferScan() {
        return bufferScan;
    }
    
    public int getYOffset() {
        return yOffset;
    }
    
    public boolean isShmCapable() {
        return shmCapable;
    }
    
    public boolean isUploadWithShmProfitable() {
       int pixelsOccupied = maxRowWidth * (currY + currentRowHeight);
       
       // in case only one shm capable buffer is left, set the treshold higher
       // so we save the shm buffer for larger uploads later
       return ((bufMan.getIdleShmBufferCnt() > 1 && pixelsOccupied >= SHM_THRESHOLD)
               || pixelsOccupied >= SHM_THRESHOLD2) && shmCapable;
    }
    
    public int getBufferId() {
        return bufferId;
    }
}
