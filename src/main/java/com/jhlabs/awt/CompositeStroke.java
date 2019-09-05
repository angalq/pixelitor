/*
Copyright 2006 Jerry Huxtable

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.jhlabs.awt;

import java.awt.Shape;
import java.awt.Stroke;

/**
 * See http://www.jhlabs.com/java/java2d/strokes/
 * <p>
 * "One interesting thing we can do is take an existing stroke
 * and draw the outline of the stroke using another stroke."
 */
public class CompositeStroke implements Stroke {
    private final Stroke stroke1;
    private final Stroke stroke2;

    public CompositeStroke(Stroke stroke1, Stroke stroke2 ) {
        this.stroke1 = stroke1;
        this.stroke2 = stroke2;
    }

    @Override
    public Shape createStrokedShape(Shape shape ) {
        return stroke2.createStrokedShape( stroke1.createStrokedShape( shape ) );
    }
}
