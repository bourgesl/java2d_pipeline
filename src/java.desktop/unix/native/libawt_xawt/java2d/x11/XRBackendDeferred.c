/*
 * Copyright (c) 2010, 2016, Oracle and/or its affiliates. All rights reserved.
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

#include "X11SurfaceData.h"
#include <jni.h>

#include <X11/extensions/XShm.h>
#include <sys/ipc.h>
#include <sys/shm.h>

#include <X11/extensions/Xrender.h>
#include <X11/Xlib-xcb.h>
#include <xcb/xcbext.h>

JavaVM *jvm;
jobject backendObj;
jmethodID releaseSocketMID;

xcb_connection_t* xcbCon;
void* protBufPtr;

jint shmMajor;

XImage *ximg;
XShmSegmentInfo *shminfo;

static void returnSocketCB(void *closure)
{    
    JNIEnv *env;
    (*jvm)->AttachCurrentThread(jvm, (void **)&env, NULL);
    (*env)->CallVoidMethod(env, backendObj, releaseSocketMID);
}

JNIEXPORT jboolean JNICALL
Java_sun_java2d_xr_XRBackendDeferred_nativeInit
 (JNIEnv *env, jobject this, jobject protoBuf) {     
    int status = (*env)->GetJavaVM(env, &jvm);
    if(status != 0) {
        return JNI_FALSE;
    }
    
    jclass cls = (*env)->GetObjectClass(env, this);
    
    releaseSocketMID = (*env)->GetMethodID(env, cls, "releaseSocket", "()V");
    if (releaseSocketMID == NULL) {
        return JNI_FALSE;
    }
    
    jfieldID renderMajorID = (*env)->GetStaticFieldID(env, cls, "RENDER_MAJOR_OPCODE", "I");
    if (renderMajorID == NULL) {
        return JNI_FALSE;
    }
    
    int major_opcode, first_event, first_error;
    XQueryExtension(awt_display, "RENDER", &major_opcode, &first_event, &first_error);
    
    xcbCon = XGetXCBConnection(awt_display);
    protBufPtr = (*env)->GetDirectBufferAddress(env, protoBuf);
    backendObj = (jobject) (*env)->NewGlobalRef(env, this);
    (*env)->SetStaticIntField(env, cls, renderMajorID, (jint) major_opcode);
    
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_sun_java2d_xr_XRBackendDeferred_issueSyncReq
 (JNIEnv *env, jobject this) {
     xcb_discard_reply(xcbCon, xcb_get_input_focus(xcbCon).sequence);   
}

JNIEXPORT jint JNICALL
Java_sun_java2d_xr_XRBackendDeferred_generateXID
 (JNIEnv *env, jobject this) {
    return (jint) xcb_generate_id(xcbCon);
}

JNIEXPORT void JNICALL
Java_sun_java2d_xr_XRBackendDeferred_forceSocketReturn(JNIEnv *env, jclass cls) {
    XNoOp(awt_display);
}

JNIEXPORT jlong JNICALL
Java_sun_java2d_xr_XRBackendDeferred_takeSocketNative
 (JNIEnv *env, jobject this, jboolean queueFence) {
    uint32_t flags = 0;
    uint64_t sent;

    // A XGetInputFocus event is used to get notified about completion
    // of XShmPutImage operations, to know when the XServer has finished
    // accessing a certain SHM area and we can start writing to it again.
    // However, we can only use xcb's event handling when xcb has taken
    // the socket, so we actually queue the event of a previous XShmPutImage
    // only after we request the socket the next time.
    jlong fenceSeq = -1;
    if(queueFence) {
        fenceSeq = xcb_get_input_focus(xcbCon).sequence;
    }
    
    xcb_take_socket(xcbCon, &returnSocketCB, &flags, flags, &sent);    

    return fenceSeq;
}

JNIEXPORT void JNICALL
Java_sun_java2d_xr_XRBackendDeferred_releaseSocketNative
 (JNIEnv *env, jobject this, jint requestCnt, jint writtenBytes, jint maskPixmap, jlong maskGC, jint maskWidth, jint maskHeight, jint maskScan, 
        jint maskYOffset, jboolean useShmPutImage, jobject directMaskBuffer) {                
    uint8_t getInputFocusReq[4];
    getInputFocusReq[0] = 43;
    getInputFocusReq[1] = 0;
    getInputFocusReq[2] = 1;
    getInputFocusReq[3] = 0;
    
    int paddedWidth = maskWidth + (4 - (maskWidth % 4));
    
    // no mask to upload
    if(maskWidth == 0) {
        struct iovec vects[2];
        vects[0].iov_base = getInputFocusReq;
        vects[0].iov_len = 4;
        vects[1].iov_base = protBufPtr;
        vects[1].iov_len = writtenBytes;
        xcb_writev(xcbCon, &vects[1], 1, requestCnt + 0);
    } else if(useShmPutImage)
    {                
        struct iovec vects[3];
        vects[0].iov_base = getInputFocusReq;
        vects[0].iov_len = 4;
       
        uint8_t shmPutImgReq[40];
        shmPutImgReq[0] = shmMajor;
        shmPutImgReq[1] = 3; //shmPutImage
        *((uint16_t*) &shmPutImgReq[2]) = 10; //req length
        *((uint32_t*) &shmPutImgReq[4]) = maskPixmap;
        *((uint32_t*) &shmPutImgReq[8]) = (uint32_t) XGContextFromGC((GC) jlong_to_ptr(maskGC));
        *((uint16_t*) &shmPutImgReq[12]) = ximg->width;
        *((uint16_t*) &shmPutImgReq[14]) = ximg->height;
        *((uint16_t*) &shmPutImgReq[16]) = 0; //srcX
        *((uint16_t*) &shmPutImgReq[18]) = maskYOffset; //srcY
        *((uint16_t*) &shmPutImgReq[20]) = paddedWidth; //src_width
        *((uint16_t*) &shmPutImgReq[22]) = maskHeight; //src_height
        *((uint16_t*) &shmPutImgReq[24]) = 0; //dstX
        *((uint16_t*) &shmPutImgReq[26]) = 0; //dstY
        shmPutImgReq[28] = 8; //Depth
        shmPutImgReq[29] = 2;  //ZImage
        shmPutImgReq[30] = 0; //SendEvent
        shmPutImgReq[31] = 0; //Pad
        *((uint32_t*) &shmPutImgReq[32]) = shminfo->shmseg;
        *((uint32_t*) &shmPutImgReq[36]) = ximg->data - shminfo->shmaddr;
       
        //XShmPutImage
        vects[1].iov_base = shmPutImgReq;
        vects[1].iov_len = 40;
        
        vects[2].iov_base = protBufPtr;
        vects[2].iov_len = writtenBytes;
        xcb_writev(xcbCon, &vects[1], 2, requestCnt + 1);
    } else {
        // mask upload using XPutImage - in case the mask is not
        // shm capable or there is too little data to upload to
        // make the extra overhead of shm buffer handling 
        // (socket handoff + event) worthwhile.
        
        struct iovec* vects = (struct iovec*) malloc(sizeof(struct iovec) * (maskHeight + 3));
        if(vects == NULL) {
            return;
        }
        
        uint8_t* maskBufferAddr = (*env)->GetDirectBufferAddress(env, directMaskBuffer);
        
        vects[0].iov_base = getInputFocusReq;
        vects[0].iov_len = 4;
        
        //TODO: Include some heuristic to round to buffer width if not too far away
        // and send the data with a single iovec in this case
        uint8_t putImgReqHeader8[24];
        putImgReqHeader8[0] = 72; //XPutImage
        putImgReqHeader8[1] = 2;  //ZImage
        *((uint16_t*) &putImgReqHeader8[2]) = 6 + (paddedWidth * maskHeight) / 4; //len in 32-bit words
        *((uint32_t*) &putImgReqHeader8[4]) = maskPixmap;
        *((uint32_t*) &putImgReqHeader8[8]) = (uint32_t) XGContextFromGC((GC) jlong_to_ptr(maskGC));
        *((uint16_t*) &putImgReqHeader8[12]) = paddedWidth;
        *((uint16_t*) &putImgReqHeader8[14]) = maskHeight;
        *((uint16_t*) &putImgReqHeader8[16]) = 0; //dstX
        *((uint16_t*) &putImgReqHeader8[18]) = 0; //dstY
        putImgReqHeader8[20] = 0; //pad
        putImgReqHeader8[21] = 8; //depth
        *((uint16_t*) &putImgReqHeader8[22]) = 0; //pad
        
        //XPutImage Request
        vects[1].iov_base = putImgReqHeader8;
        vects[1].iov_len = 24; 
        
        for(int y = 0; y < maskHeight; y++) {
            vects[y + 2].iov_base = maskBufferAddr + (y * maskScan);
            vects[y + 2].iov_len = paddedWidth;
        }
    
        vects[maskHeight + 2].iov_base = protBufPtr;
        vects[maskHeight + 2].iov_len = writtenBytes;
    
        xcb_writev(xcbCon, &vects[1], maskHeight + 2, requestCnt + 1);
    
        free(vects);
    }
}

JNIEXPORT jboolean JNICALL
Java_sun_java2d_xr_AATileBufMan_pollForTileCompletion
 (JNIEnv *env, jclass this, jlong fenceSeq) {     
    void* fenceReply;
    
    if(xcb_poll_for_reply(xcbCon, (unsigned int) fenceSeq, &fenceReply, NULL) > 0) {
        free(fenceReply);
        return JNI_TRUE;
    }
    
    return JNI_FALSE;
 }

JNIEXPORT jobject JNICALL
Java_sun_java2d_xr_AATileBufMan_initShmImage
 (JNIEnv *env, jobject this, jint width, jint height) {
   ximg = NULL;
   shminfo = NULL;
   
   jclass cls = (*env)->GetObjectClass(env, this);
   
   int first_event, first_error;
   if(!XQueryExtension(awt_display, "MIT-SHM", &shmMajor, &first_event, &first_error)) {
      return NULL;
   }

    jfieldID bufferScanID = (*env)->GetFieldID(env, cls, "shmBufferScan", "I");
    if (bufferScanID == NULL) {
        return NULL;
    }    
    
    shminfo = malloc(sizeof(XShmSegmentInfo));
    ximg = XShmCreateImage(awt_display, NULL, 8, ZPixmap, NULL, shminfo, width, height);
                                
    if(!ximg) {
        fprintf(stderr, "XShmCreateImage XShm problems\n");
        return NULL;
    }
    
    if((shminfo->shmid = shmget(IPC_PRIVATE, ximg->bytes_per_line * ximg->height, IPC_CREAT | 0777)) == -1){
        fprintf(stderr, "shmget XShm problems\n");
        return NULL;
    }
    if((shminfo->shmaddr = ximg->data = shmat(shminfo->shmid, 0, 0)) == (void *)-1){
        fprintf(stderr, "shmat XShm problems\n");
        return NULL;
    }
    shminfo->readOnly = False;
    if(!XShmAttach(awt_display, shminfo)){
        fprintf(stderr, "XShmAttach XShm problems, falling back to to XImage\n");
        return NULL;
    }
    
    (*env)->SetIntField(env, this, bufferScanID, ximg->bytes_per_line);
    
    return (*env)->NewDirectByteBuffer(env, ximg->data, ximg->bytes_per_line * ximg->height);
 }
 

