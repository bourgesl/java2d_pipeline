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

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;

/**
 * Manages the state of the buffers for uploading the AA tiles.
 * There is always only one non-Shared-memory buffer (can be re-used immediately
 * once XPutImage is issued), and a various number of SHM buffers in case SHM
 * is supported on the system.
 *
 * @author Clemens Eisserer
 */
public final class AATileBufMan {
    private final static boolean STATS = false;

    private final static int DEF_TILE_BUF_SIZE = 256;
    private final static int TILE_BUF_WIDTH;
    private final static int TILE_BUF_HEIGHT;
   
    private final static int DEF_SHM_NUM_BUFFERS = 4;
    private final static int SHM_NUM_BUFFERS;

    static {
        int size = DEF_TILE_BUF_SIZE;
        String sizeProp = System.getProperty("sun.java2d.xr.tile");
        if (sizeProp != null) {
            final int val = Integer.parseInt(sizeProp);
            if (val >= 32 && val <= 4096) {
                size = val;
            }
        }
        TILE_BUF_WIDTH = size;
        TILE_BUF_HEIGHT = size;

        String shmProp = System.getProperty("sun.java2d.xr.shm");
        
        if (shmProp != null && shmProp.equalsIgnoreCase("false")) {
            SHM_NUM_BUFFERS = 0;
        } else {
            int shmBuffers = DEF_SHM_NUM_BUFFERS; // default
            shmProp = System.getProperty("sun.java2d.shmBuffers");
            if (shmProp != null) {
                shmBuffers = Integer.parseInt(shmProp);
            }
            SHM_NUM_BUFFERS = shmBuffers;
        }
        System.out.println("AATileBufMan: Using tile size " + size);
        System.out.println("AATileBufMan: Using " + SHM_NUM_BUFFERS + " shmBuffers");
    }
    
    // fields accessed from native code:
    ByteBuffer shmBuffer = null;
    int shmBufferScan;
    
// TODO: make most fields PRIVATE !
    AATileBuffer nonShmTile;
    
    AATileBuffer activeTile;
// TODO: use ArrayList for performance !
    LinkedList<AATileBuffer> idleShmMasks;
    HashMap<Long, AATileBuffer> pendingShmMasks;
    AATileBuffer fenceQueuePendingTile;
    
// stats
    int shmCnt;
    int noShmCnt;
    
    int maskPicture;
    int maskPixmap;
    long maskGC;
    
    public AATileBufMan() {
        idleShmMasks = new LinkedList<>();
        pendingShmMasks = new HashMap<>();                        

        nonShmTile = new AATileBuffer(this, TILE_BUF_WIDTH, TILE_BUF_HEIGHT, TILE_BUF_WIDTH, 0, 0, ByteBuffer.allocateDirect(TILE_BUF_WIDTH * TILE_BUF_HEIGHT), false);
        
        if(SHM_NUM_BUFFERS > 0) {
            if((shmBuffer = initShmImage(TILE_BUF_WIDTH, TILE_BUF_HEIGHT * SHM_NUM_BUFFERS)) != null) {
                for(int i = 0; i < SHM_NUM_BUFFERS; i++) {
                    int tileYOffset = TILE_BUF_HEIGHT * i;
                    
                    shmBuffer.position(tileYOffset * shmBufferScan);
                    ByteBuffer tileBuffer = shmBuffer.slice();
                    AATileBuffer aaShmBuf = new AATileBuffer(this, TILE_BUF_WIDTH, TILE_BUF_HEIGHT, shmBufferScan, tileYOffset, i + 1, tileBuffer, true);
                    idleShmMasks.add(aaShmBuf);
                }
                shmBuffer.position(0);
            }
        }
    }
    
    public void initResources(XRBackendDeferred con, int parentXID) {
        maskPixmap = con.createPixmap(parentXID, 8, TILE_BUF_WIDTH, TILE_BUF_HEIGHT);
        maskPicture = con.createPicture(maskPixmap, XRUtils.PictStandardA8);
        maskGC = con.createGC(maskPixmap);
        con.setGCExposures(maskGC, false);
    }
    
   
    public AATileBuffer getActiveTileBuffer() {
        if(activeTile != null) {
            return activeTile;
        }
        
        if(idleShmMasks.size() > 0) {
            //System.out.println("shm tiles available: "+idleShmMasks.size());
            activeTile = idleShmMasks.removeLast();
            if (STATS) {
                shmCnt++;
            }
        } else {
//            System.out.println("shmtiles exhausted");
            activeTile = nonShmTile;
            if (STATS) {
                noShmCnt++;
            }
        }
        if (STATS && ((shmCnt + noShmCnt) % 10000 == 0)) {
            System.out.println("Shm: "+shmCnt+" noShm:"+noShmCnt);
        }
        return activeTile;
    }
    
    public void pollPendingFences() {
        if(pendingShmMasks.size() > 0) {
            java.util.Iterator<Long> shmSeqIt = pendingShmMasks.keySet().iterator();
            
            while(shmSeqIt.hasNext()) {
                long pendingSeq = shmSeqIt.next();
                
                if(pollForTileCompletion(pendingSeq)) {
              //      System.out.println("Pending fence received: " + pendingSeq);
                    AATileBuffer idleBuffer = pendingShmMasks.get(pendingSeq);
                //    System.out.println("tile now available again: "+idleBuffer.getTileId());
                    idleShmMasks.add(idleBuffer);
                    
                    shmSeqIt.remove();
                }                   
            }
        }
    }
    
    public void registerFenceSeqForActiveBuffer(long seq) {
        assert(fenceQueuePendingTile != null);
        pendingShmMasks.put(seq, fenceQueuePendingTile);
       // System.out.println("fence queued for tile: "+fenceQueuePendingTile.getTileId());
        fenceQueuePendingTile = null;
    }
    
    
    public void markActiveBufferFlushed(boolean shmQueued) {
        if(shmQueued) {
           // pendingShmMasks.push(activeTile);
           assert (fenceQueuePendingTile == null);
           fenceQueuePendingTile = activeTile;
        } else if(activeTile != nonShmTile) {
            idleShmMasks.add(activeTile);
        }
        
        activeTile.reset();        
        activeTile = null;
    }
    
    public int getIdleShmBufferCnt() {
        return idleShmMasks.size();
    }

    public boolean isFencePending() {
        return fenceQueuePendingTile != null;
    }
  
    public int getMaskPictureXid() {
        return maskPicture;
    }
    
    public long getMaskGCPtr() {
        return maskGC;
    }
    
    public int getMaskPixmapXid() {
        return maskPixmap;
    }
    
    private native ByteBuffer initShmImage(int width, int height);
    
    private native boolean pollForTileCompletion(long fenceSeq);
  
}
