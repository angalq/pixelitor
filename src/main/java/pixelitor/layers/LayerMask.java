/*
 * Copyright 2015 Laszlo Balazs-Csiki
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 *
 * Pixelitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.layers;

import pixelitor.Composition;
import pixelitor.utils.ImageUtils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;

import static java.awt.AlphaComposite.DstIn;

/**
 * A layer mask.
 */
public class LayerMask extends ImageLayer {
    private transient BufferedImage transparencyImage;
    private static final ColorModel transparencyColorModel;
    private boolean linked = true; // whether it moves together with its parent layer

    static {
        byte[] lookup = new byte[256];
        for (int i = 0; i < lookup.length; i++) {
            lookup[i] = (byte) i;
        }
        transparencyColorModel = new IndexColorModel(8, 256, lookup, lookup, lookup, lookup);
    }

//    public static final ColorSpace GRAY_SPACE = new ICC_ColorSpace(ICC_Profile.getInstance(ColorSpace.CS_GRAY));
//    public static final ColorModel GRAY_MODEL = new ComponentColorModel(GRAY_SPACE, false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);

    public LayerMask(Composition comp, BufferedImage bwImage, Layer layer) {
        super(comp, bwImage, layer.getName() + " MASK", layer);
    }

    public BufferedImage getTransparencyImage() {
        return transparencyImage;
    }

    public void applyToImage(BufferedImage in) {
        Graphics2D g = in.createGraphics();
        g.setComposite(DstIn);
        g.drawImage(getTransparencyImage(), 0, 0, null);
        g.dispose();
    }

    public void updateFromBWImage() {
//        System.out.println("LayerMask::updateFromBWImage: CALLED");

        assert image.getType() == BufferedImage.TYPE_BYTE_GRAY;
        assert image.getColorModel() != transparencyColorModel;

        // The transparency image shares the raster data with the BW image,
        // but interprets the bytes differently.
        // Therefore this method needs to be called only when
        // the image reference changes.
        WritableRaster raster = image.getRaster();
        this.transparencyImage = new BufferedImage(transparencyColorModel, raster, false, null);
    }

    @Override
    protected BufferedImage createEmptyImageForLayer(int width, int height) {
//        BufferedImage empty = new BufferedImage(GRAY_MODEL, GRAY_MODEL.createCompatibleWritableRaster(width, height), false, null);
        BufferedImage empty = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        // when enlarging a layer mask, the new areas need to be white
        Graphics2D g = empty.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.dispose();

        return empty;
    }

    @Override
    protected void imageRefChanged() {
        updateFromBWImage();
        comp.imageChanged(Composition.ImageChangeActions.FULL);
        updateIconImage();
    }

    public void updateIconImage() {
        LayerButton button = getLayerButton();
        if(button != null) { // can be null while deserializing
            button.updateLayerIconImage(image, true);
        }
    }

    public LayerMask duplicate(Layer duplicatedLayer) {
        LayerMask d = new LayerMask(comp, ImageUtils.copyImage(image), duplicatedLayer);
        return d;
    }

    public boolean isLinked() {
        return linked;
    }

    public void setLinked(boolean linked) {
        this.linked = linked;
    }
}
