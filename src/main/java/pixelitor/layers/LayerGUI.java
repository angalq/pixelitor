/*
 * Copyright 2024 Laszlo Balazs-Csiki and Contributors
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

import org.jdesktop.swingx.VerticalLayout;
import pixelitor.GUIMode;
import pixelitor.gui.View;
import pixelitor.gui.utils.GUIUtils;
import pixelitor.gui.utils.PAction;
import pixelitor.gui.utils.Themes;
import pixelitor.utils.Icons;
import pixelitor.utils.ImageUtils;
import pixelitor.utils.debug.Debug;
import pixelitor.utils.debug.DebugNode;

import javax.swing.*;
import javax.swing.plaf.ButtonUI;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static java.awt.RenderingHints.KEY_ANTIALIASING;
import static java.awt.RenderingHints.VALUE_ANTIALIAS_ON;
import static pixelitor.utils.Threads.calledOnEDT;
import static pixelitor.utils.Threads.threadInfo;

/**
 * The selectable and draggable component representing
 * a layer in the "Layers" part of the GUI.
 */
public class LayerGUI extends JToggleButton implements LayerUI {
    private static final Icon OPEN_EYE_ICON = Icons.load("eye_open.png", "eye_open_dark.png");
    private static final Icon CLOSED_EYE_ICON = Icons.load("eye_closed.png", "eye_closed_dark.png");

    public static final Color UNSELECTED_COLOR = new Color(214, 217, 223);
    public static final Color SELECTED_COLOR = new Color(48, 76, 111);
    private static final Color SELECTED_DARK_COLOR = new Color(16, 16, 16);
    private static final Color SEMI_SELECTED_COLOR = new Color(131, 146, 167);
    private static final Color SEMI_SELECTED_DARK_COLOR = new Color(38, 39, 40);

    public static final int BORDER_WIDTH = 2;
    private DragReorderHandler dragReorderHandler;

    // this field can't be called parent, because the setters/getters
    // would conflict with the methods in java.awt.Component
    private LayerGUI parentUI;

    private final List<LayerGUI> children = new ArrayList<>();

    // Most often false, but when opening serialized pxc files,
    // the mask/smart filter label might be added before the drag handler,
    // and in unit tests the drag handler isn't added at all.
    private boolean hasLateDragHandler;

    // for debugging only: each layer GUI has a different id
    private static int idCounter = 0;
    private final int uniqueId;
    private JPanel childrenPanel;

    private SelectionState selectionState;

    private Layer layer;
    private final LayerGUILayout layout;
    private boolean userInteraction = true;

    private JCheckBox visibilityCB;
    private LayerNameEditor nameEditor;
    private JLabel layerIconLabel;
    private JLabel maskIconLabel;

    /**
     * The Y coordinate in the parent when it is not dragged.
     */
    private int staticY;

    public LayerGUI(Layer layer) {
        assert calledOnEDT() : threadInfo();
        assert !GUIMode.isUnitTesting() : "Swing component in unit test";

        this.layer = layer;

        layout = new LayerGUILayout(layer);
        setLayout(layout);

        initLayerVisibilityCB();
        initLayerNameEditor();
        updateChildrenPanel();

        configureLayerIcon();

        if (layer.hasMask()) {
            addMaskIcon();
        }

        wireSelectionWithLayerActivation();
        uniqueId = idCounter++;
    }

    @Override
    public void updateChildrenPanel() {
        if (!(layer instanceof LayerHolder holder)) {
            if (childrenPanel != null) { // can happen after holder rasterization.
                remove(childrenPanel);
                childrenPanel = null;
            }
            return;
        }
        int numChildLayers = holder.getNumLayers();
        if (numChildLayers > 0) {
            if (childrenPanel == null) {
                VerticalLayout innerLayout = new VerticalLayout();
                childrenPanel = new JPanel(innerLayout);
            } else {
                childrenPanel.removeAll();
            }
        } else {
            if (childrenPanel != null) {
                // all children have been removed
                remove(childrenPanel);
                childrenPanel = null;
            }
        }

        // TODO it's not elegant to detach all layer GUIs and
        //   then reattach those that weren't changed.
        for (LayerGUI child : children) {
            child.detach();
        }
        children.clear();

        // children are added from the bottom up
        for (int i = numChildLayers - 1; i >= 0; i--) {
            Layer child = holder.getLayer(i);
            LayerGUI childUI = (LayerGUI) child.createUI();
            childUI.setParentUI(this);
            assert !children.contains(childUI);
            children.add(childUI);

            // when duplicating a smart object with filters
            // this is null, and it's only set later
            if (dragReorderHandler != null) {
                childUI.setDragReorderHandler(dragReorderHandler);
            }
            assert childUI != null;
            childrenPanel.add(childUI);
        }
        if (numChildLayers > 0) {
            add(childrenPanel, LayerGUILayout.CHILDREN);
        }
    }

    private void configureLayerIcon() {
        Icon icon = createLayerIcon(layer);
        layerIconLabel = new JLabel(icon);
        if (layer instanceof TextLayer) {
            layerIconLabel.setToolTipText("<html><b>Double-click</b> to edit the text layer.");
        }

        layerIconLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                layerIconClicked(e);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    layerPopupTriggered(e);
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    // by putting it into mouse pressed, it is consistent
                    // with the mask clicks
                    selectLayerIfIconClicked(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    layerPopupTriggered(e);
                }
            }
        });

        layerIconLabel.setName("layerIcon");
        add(layerIconLabel, LayerGUILayout.LAYER);
    }

    private static Icon createLayerIcon(Layer layer) {
        if (layer instanceof TextLayer) {
            return Icons.getTextLayerIcon();
        } else if (layer.getClass() == AdjustmentLayer.class) {
            return Icons.getAdjLayerIcon();
        } else if (layer.getClass() == SmartFilter.class) {
            return Icons.getSmartFilterIcon();
        } else {
            return null;
        }
    }

    private void layerIconClicked(MouseEvent e) {
        activateLayerNow();

        int clickCount = e.getClickCount();
        if (clickCount == 1) {
            MaskViewMode.NORMAL.activate(layer);
        } else {
            layer.edit();
        }
    }

    // Called when one of the icons is clicked
    private void activateLayerNow() {
        // the layer would be activated anyway, but only in an invokeLater,
        // and the mask activation expects events to be coming from the active layer
        layer.activate();
    }

    private void layerPopupTriggered(MouseEvent e) {
        JPopupMenu popup = layer.createLayerIconPopupMenu();
        if (popup != null) {
            if (GUIMode.isDevelopment()) {
                popup.add(new PAction("Internal State...", () ->
                    Debug.showTree(layer, layer.getTypeString())));
            }

            popup.show(this, e.getX(), e.getY());
        }
    }

    public static void selectLayerIfIconClicked(MouseEvent e) {
        // By adding a mouse listener to the JLabel, it loses the
        // ability to automatically transmit the mouse events to its
        // parent, and therefore the layer cannot be selected anymore
        // by left-clicking on this label. This is the workaround.
        JLabel source = (JLabel) e.getSource();
        LayerGUI layerGUI = (LayerGUI) source.getParent();
        layerGUI.setSelected(true);
    }

    private void initLayerVisibilityCB() {
        visibilityCB = createVisibilityCheckBox(false);

        // when loading pxc files, the layer might not be visible
        visibilityCB.setSelected(layer.isVisible());
        visibilityCB.setToolTipText("<html><b>Click</b> to hide/show this layer.<br><b>Alt-click</b> to isolate this layer.");
        add(visibilityCB, LayerGUILayout.CHECKBOX);
    }

    private JCheckBox createVisibilityCheckBox(boolean smartFilter) {
        JCheckBox cb = new JCheckBox(CLOSED_EYE_ICON) {
            @Override
            public void setUI(ButtonUI ui) {
                super.setUI(ui);
                // after an LF change, it's necessary to reset the border to null
                setBorder(null);
            }

            @Override
            protected void processMouseEvent(MouseEvent e) {
                // isolating works after a theme-change only if the
                // mouse event processing is overridden at this level

                if (smartFilter) {
                    super.processMouseEvent(e);
                } else if (e.getID() == MouseEvent.MOUSE_CLICKED) {
                    boolean altDown = (e.getModifiersEx() & MouseEvent.ALT_DOWN_MASK) == MouseEvent.ALT_DOWN_MASK;
                    if (altDown) {
                        layer.isolate();
                    } else {
                        // normal behaviour
                        boolean newVisibility = !visibilityCB.isSelected();
                        layer.setVisible(newVisibility, true, true);
                    }
                }
            }
        };
        cb.setRolloverIcon(CLOSED_EYE_ICON);
        cb.setSelectedIcon(OPEN_EYE_ICON);
        cb.setFocusPainted(false);
        cb.setIconTextGap(0);
        cb.setBorder(null);
        cb.setBorderPainted(false);

        return cb;
    }

    private void initLayerNameEditor() {
        nameEditor = new LayerNameEditor(this);
        add(nameEditor, LayerGUILayout.NAME_EDITOR);
        addPropertyChangeListener("name", evt -> updateName());
    }

    private void wireSelectionWithLayerActivation() {
        addItemListener(e -> {
            if (userInteraction) {
                // invoke later, when isSelected() returns the correct value
                EventQueue.invokeLater(this::buttonActivationChanged);
            }
        });
    }

    private void buttonActivationChanged() {
        if (isSelected()) {
            // during comp actions, the active layer might already be inside the active layer
            boolean setActiveLayer = !layer.contains(layer.getComp().getActiveLayer());
            if (setActiveLayer) {
                layer.activate();
            }
        } else {
            // the layer was just deactivated
            nameEditor.disableEditing();
        }
        updateSelectionState();
    }

    @Override
    public void setOpenEye(boolean newVisibility) {
        visibilityCB.setSelected(newVisibility);
        layer.setVisible(newVisibility, true, true);
    }

    @Override
    public boolean isEyeOpen() {
        return visibilityCB.isSelected();
    }

    public void setUserInteraction(boolean userInteraction) {
        this.userInteraction = userInteraction;
    }

    private void setDragReorderHandler(DragReorderHandler handler) {
        if (dragReorderHandler != null) {
            return; // don't attach twice
        }
        addDragReorderHandler(handler);
    }

    public void addDragReorderHandler(DragReorderHandler handler) {
        assert dragReorderHandler == null;
        assert handler != null;

        dragReorderHandler = handler;
        handler.attachTo(this);
        handler.attachTo(nameEditor);
        handler.attachTo(layerIconLabel);

        if (hasLateDragHandler) {
            if (maskIconLabel != null) {
                handler.attachTo(maskIconLabel);
            }
        }

        for (LayerGUI child : children) {
            // if it was already set, then the call will be ignored
            child.setDragReorderHandler(handler);
        }
    }

    public void removeDragReorderHandler() {
        if (dragReorderHandler == null) {
            return;
        }

        dragReorderHandler.detachFrom(this);
        dragReorderHandler.detachFrom(nameEditor);
        dragReorderHandler.detachFrom(layerIconLabel);

        if (hasMaskIcon()) {
            dragReorderHandler.detachFrom(maskIconLabel);
        }

        for (LayerGUI child : children) {
            child.removeDragReorderHandler();
        }

        dragReorderHandler = null;
    }

    @Override
    public boolean hasMaskIcon() {
        return maskIconLabel != null;
    }

    public int getStaticY() {
        return staticY;
    }

    public void setStaticY(int staticY) {
        this.staticY = staticY;
    }

    public void dragFinished(int newLayerIndex) {
        layer.changeStackIndex(newLayerIndex);
    }

    @Override
    public Layer getLayer() {
        return layer;
    }

    @Override
    public String getLayerName() {
        return layer.getName();
    }

    public boolean isNameEditing() {
        return nameEditor.isEditable();
    }

    @Override
    public void updateName() {
        nameEditor.setText(layer.getName());
    }

    @Override
    public void updateLayerIconImageAsync(Layer layer) {
        assert calledOnEDT() : threadInfo();
        assert layer.hasRasterThumbnail();

        // the synchronous update avoids starting a filter twice
        boolean synchronousUpdate = layer instanceof CompositeLayer;

        if (synchronousUpdate) {
            BufferedImage thumb = layer.createIconThumbnail();
            assert thumb != null;
            if (thumb != null) {
                updateIconOnEDT(layer, thumb);
            }
            return;
        }

        Runnable notEDT = () -> {
            BufferedImage thumb = layer.createIconThumbnail();
            assert thumb != null;
            if (thumb != null) {
                SwingUtilities.invokeLater(() -> updateIconOnEDT(layer, thumb));
            }
        };

        new Thread(notEDT).start();
    }

    private void updateIconOnEDT(Layer layer, BufferedImage thumb) {
        assert calledOnEDT() : threadInfo();
        if (layer instanceof LayerMask mask) {
            if (!hasMaskIcon()) {
                return;
            }
            boolean disabledMask = !mask.getOwner().isMaskEnabled();
            if (disabledMask) {
                ImageUtils.paintRedXOn(thumb);
            }
            maskIconLabel.setIcon(new ImageIcon(thumb));
        } else {
            layerIconLabel.setIcon(new ImageIcon(thumb));
        }
        repaint();
    }

    @Override
    public void addMaskIcon() {
        assert !hasMaskIcon() : "layer '" + layer.getName() + "' already has mask icon";

        maskIconLabel = new JLabel("", null, CENTER);
        maskIconLabel.setToolTipText("<html>" +
            "<b>Click</b> activates mask editing,<br>" +
            "<b>Shift-click</b> disables/enables the mask,<br>" +
            "<b>Alt-click</b> toggles mask/layer view,<br>" +
            "<b>Shift-Alt-click</b> toggles rubylith/normal view,<br>" +
            "<b>Right-click</b> shows more options");

        LayerMaskActions.addPopupMenu(maskIconLabel, layer);
        maskIconLabel.setName("maskIcon");
        add(maskIconLabel, LayerGUILayout.MASK);

        // there is another mouse listener for the right-click popups
        maskIconLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                maskIconClicked(e);
            }
        });

        if (dragReorderHandler != null) {
            dragReorderHandler.attachTo(maskIconLabel);
            hasLateDragHandler = false;
        } else {
            hasLateDragHandler = true;
        }

        // don't call layer.getMask().updateIconImage(); because
        // it requires an ui, which could be constructed right now.
        updateLayerIconImageAsync(layer.getMask());
        revalidate();
    }

    private void maskIconClicked(MouseEvent e) {
        activateLayerNow();

        boolean altClick = e.isAltDown();
        boolean shiftClick = e.isShiftDown();

        if (altClick && shiftClick) {
            // shift-alt-click switches to RUBYLITH
            // except when it already is in RUBYLITH
            View view = layer.getComp().getView();
            if (view.getMaskViewMode() == MaskViewMode.RUBYLITH) {
                MaskViewMode.EDIT_MASK.activate(view, layer);
            } else {
                MaskViewMode.RUBYLITH.activate(view, layer);
            }
        } else if (altClick) {
            // alt-click switches to SHOW_MASK
            // except when it already is in SHOW_MASK
            View view = layer.getComp().getView();
            if (view.getMaskViewMode() == MaskViewMode.SHOW_MASK) {
                MaskViewMode.EDIT_MASK.activate(view, layer);
            } else {
                MaskViewMode.SHOW_MASK.activate(view, layer);
            }
        } else if (shiftClick) {
            // shift-click toggles the enabled-disabled state
            layer.setMaskEnabled(!layer.isMaskEnabled(), true);
        } else { // plain click, without key modifiers
            View view = layer.getComp().getView();

            // don't change SHOW_MASK or RUBYLITH into EDIT_MASK
            if (!view.getMaskViewMode().editMask()) {
                MaskViewMode.EDIT_MASK.activate(layer);
            }
            // ...but make sure that the layer is notified even if
            // the view already was in mask editing mode
            // (this is important for smart filters)
            layer.setMaskEditing(true);
        }
    }

    @Override
    public void removeMaskIcon() {
        assert maskIconLabel != null;

        // the mask icon label is not going to be used again, remove all listeners
        if (dragReorderHandler != null) { // null in unit tests
            dragReorderHandler.detachFrom(maskIconLabel);
        }

        // remove the left-click and right-click mouse listeners
        GUIUtils.removeAllMouseListeners(maskIconLabel);

        remove(maskIconLabel);
        revalidate();
        repaint();
        maskIconLabel = null;

        hasLateDragHandler = false;
    }

    @Override
    public void updateSelectionState() {
        if (!layer.isActive()) {
            setSelectionState(SelectionState.UNSELECTED);
        } else if (layer.isMaskEditing()) {
            setSelectionState(SelectionState.MASK_SELECTED);
        } else {
            setSelectionState(SelectionState.LAYER_SELECTED);
        }
    }

    private void setSelectionState(SelectionState newSelectionState) {
        if (newSelectionState != selectionState) {
            selectionState = newSelectionState;
            selectionState.show(layerIconLabel, maskIconLabel);
        }
    }

    @Override
    public void changeLayer(Layer newLayer) {
        this.layer = newLayer;
        updateName();
        Icon icon = createLayerIcon(layer);
        if (icon != null) { // has fix icon
            layerIconLabel.setIcon(icon);
        } else {
            updateLayerIconImageAsync(layer);
        }

        if (maskIconLabel != null) {
            removeMaskIcon();
        }
        if (newLayer.hasMask()) {
            addMaskIcon();
        }
        updateSelectionState();
        updateChildrenPanel();
    }

    @Override
    public void updateUI() {
        // don't use any UI
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!layer.getTopLevelLayer().isActiveRoot()) {
            // no custom painting if not selected or semi-selected
            return;
        }

        Color selectedColor;
        if (layer.isActive()) {
            if (Themes.getCurrent().isDark()) {
                selectedColor = SELECTED_DARK_COLOR;
            } else {
                selectedColor = SELECTED_COLOR;
            }
        } else {
            if (Themes.getCurrent().isDark()) {
                selectedColor = SEMI_SELECTED_DARK_COLOR;
            } else {
                selectedColor = SEMI_SELECTED_COLOR;
            }
        }

        Graphics2D g2 = (Graphics2D) g;

        // save Graphics settings
        Color oldColor = g.getColor();
        Object oldAA = g2.getRenderingHint(KEY_ANTIALIASING);

        // paint a rounded rectangle with the
        // selection color on the selected layer GUI
        g2.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
        g.setColor(selectedColor);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);

        // restore Graphics settings
        g.setColor(oldColor);
        g2.setRenderingHint(KEY_ANTIALIASING, oldAA);
    }

    @Override
    public int getId() {
        return uniqueId;
    }

    public int getPreferredHeight() {
        return layout.getPreferredHeight();
    }

    public void updateThumbSize(int newThumbSize) {
        layout.updateHeight(newThumbSize);
        for (LayerGUI child : children) {
            child.updateThumbSize(newThumbSize);
        }
    }

    @Override
    public void setParentUI(LayerUI parentUI) {
        this.parentUI = (LayerGUI) parentUI;
    }

    public LayerGUI getParentUI() {
        return parentUI;
    }

    @Override
    public void detach() {
        setParentUI(null);
        removeDragReorderHandler();
    }

    public List<LayerGUI> getChildren() {
        return children;
    }

    public boolean isEmbedded() {
        return parentUI != null;
    }

    @Override
    public boolean checkInvariants() {
        if (parentUI != null) {
            if (!parentUI.containsChild(this)) {
                throw new AssertionError(parentUI.getLayerName() + " UI doesn't contain " + this.getLayerName() + " UI");
            }
        }
        if (!layer.isTopLevel()) {
            if (parentUI == null) {
                throw new AssertionError("null parentUI in " + getLayerName()
                    + " UI, holder class = " + layer.getHolder().getClass().getSimpleName());
            }

            LayerUI holderUI = ((CompositeLayer) layer.getHolder()).getUI();
            if (holderUI != parentUI) {
                throw new AssertionError("mismatched UIs: holderUI = " + holderUI.getLayerName()
                    + ", parentUI = " + parentUI.getLayerName());
            }
        }
        return true;
    }

    private boolean containsChild(LayerGUI layerGUI) {
        return children.contains(layerGUI);
    }

    @Override
    public DebugNode createDebugNode(String key) {
        DebugNode node = new DebugNode(key, this);
        node.addInt("unique id", uniqueId);

        node.addNullableProperty("dragReorderHandler", dragReorderHandler);
        node.addNullableProperty("parentUI", parentUI);

        for (LayerGUI child : children) {
            node.add(child.createDebugNode("child " + child.getLayer().getName()));
        }

        node.addBoolean("lateDragHandler", hasLateDragHandler);
        node.addAsString("selectionState", selectionState);
        node.addString("layer name", layer.getName());

        return node;
    }

    @Override
    public String toString() {
        return "LayerGUI{" +
            "name='" + getLayerName() + '\'' +
            ", id='" + getId() + '\'' +
            ", has mask icon: " + (hasMaskIcon() ? "YES" : "NO") +
            '}';
    }
}
