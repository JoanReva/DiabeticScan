/*******************************************************************
 * @title FLIR Atlas Android SDK
 * @file FrameDataHolder.java
 * @Author Teledyne FLIR
 *
 * @brief Container class that holds references to Bitmap images
 *
 * Copyright 2023:    Teledyne FLIR
 ********************************************************************/

package com.drateor.diabeticscan;

import android.graphics.Bitmap;

class FrameDataHolder {

    public final Bitmap msxBitmap;
    public final Bitmap dcBitmap;
    public final String informacion;

    FrameDataHolder(Bitmap msxBitmap, Bitmap dcBitmap, String informacion) {
        this.msxBitmap = msxBitmap;
        this.dcBitmap = dcBitmap;
        this.informacion = informacion;
    }
}
