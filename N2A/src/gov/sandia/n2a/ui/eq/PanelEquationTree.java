/*
Copyright 2013-2019 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui.eq;

import gov.sandia.n2a.db.MNode;
import gov.sandia.n2a.db.MVolatile;
import gov.sandia.n2a.eqset.MPart;
import gov.sandia.n2a.ui.eq.PanelEquations.FocusCacheEntry;
import gov.sandia.n2a.ui.eq.tree.NodeAnnotation;
import gov.sandia.n2a.ui.eq.tree.NodeAnnotations;
import gov.sandia.n2a.ui.eq.tree.NodeBase;
import gov.sandia.n2a.ui.eq.tree.NodePart;
import gov.sandia.n2a.ui.eq.tree.NodeVariable;
import gov.sandia.n2a.ui.eq.undo.AddAnnotation;
import gov.sandia.n2a.ui.eq.undo.ChangeOrder;
import gov.sandia.n2a.ui.eq.undo.DeleteAnnotation;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Enumeration;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

@SuppressWarnings("serial")
public class PanelEquationTree extends JScrollPane
{
    public    JTree             tree;
    public    FilteredTreeModel model;
    public    NodePart          root;
    protected PanelEquations    container;
    protected boolean           needsFullRepaint;

    public PanelEquationTree (PanelEquations container, NodePart initialRoot, boolean headless)
    {
        this.container = container;

        root  = initialRoot;
        model = new FilteredTreeModel (initialRoot);  // Can be null
        tree  = new JTree (model)
        {
            public String convertValueToText (Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus)
            {
                if (value == null) return "";
                return ((NodeBase) value).getText (expanded, false);
            }

            public void startEditingAtPath (TreePath path)
            {
                super.startEditingAtPath (path);

                // Ensure that graph node is big enough to edit name without activating scroll bars.
                GraphNode gn = root.graph;
                if (gn == null) return;
                Point     p = gn.getLocation ();
                Dimension d = gn.getPreferredSize ();
                root.graph.animate (new Rectangle (p, d));
            }

            public String getToolTipText (MouseEvent e)
            {
                TreePath path = getPathForLocation (e.getX (), e.getY ());
                if (path == null) return null;
                NodeBase node = (NodeBase) path.getLastPathComponent ();
                if (! (node instanceof NodeVariable)) return null;

                MPart source = node.source;
                String notes = source.get ("$metadata", "notes");
                if (notes.isEmpty ()) notes = source.get ("$metadata", "note");
                if (notes.isEmpty ()) return null;

                int paneWidth = PanelEquationTree.this.getWidth ();
                FontMetrics fm = getFontMetrics (getFont ());
                int notesWidth = fm.stringWidth (notes);
                if (notesWidth < paneWidth) return notes;

                paneWidth = Math.max (300, paneWidth);
                notes = notes.replace ("\n", "<br>");
                return "<html><p  width=\"" + paneWidth + "\">" + notes + "</p></html>";
            }

            public void updateUI ()
            {
                // We need to reset the renderer font first, before polling for cell sizes.
                if (root != null) root.filter (FilteredTreeModel.filterLevel);  // Force update to tab stops, in case font has changed.
                super.updateUI ();
            }
        };

        tree.setExpandsSelectedPaths (true);
        tree.setScrollsOnExpand (true);
        tree.getSelectionModel ().setSelectionMode (TreeSelectionModel.SINGLE_TREE_SELECTION);  // No multiple selection. It only makes deletes and moves more complicated.
        tree.setEditable (! container.locked);
        tree.setInvokesStopCellEditing (true);  // auto-save current edits, as much as possible
        tree.setDragEnabled (true);
        tree.setToggleClickCount (0);  // Disable expand/collapse on double-click
        ToolTipManager.sharedInstance ().registerComponent (tree);
        tree.setTransferHandler (container.transferHandler);
        tree.setCellRenderer (container.renderer);
        tree.setCellEditor (container.editor);
        tree.addTreeSelectionListener (container.editor);

        // Special cases for nodes in graph. Also includes "parent" which lacks an associated graph node.
        if (headless)
        {
            tree.setRootVisible (false);
            tree.setShowsRootHandles (true);
            setBorder (BorderFactory.createEmptyBorder ());
        }

        InputMap inputMap = tree.getInputMap ();
        inputMap.put (KeyStroke.getKeyStroke ("shift UP"),    "moveUp");
        inputMap.put (KeyStroke.getKeyStroke ("shift DOWN"),  "moveDown");
        inputMap.put (KeyStroke.getKeyStroke ("INSERT"),      "add");
        inputMap.put (KeyStroke.getKeyStroke ("DELETE"),      "delete");
        inputMap.put (KeyStroke.getKeyStroke ("BACK_SPACE"),  "delete");
        inputMap.put (KeyStroke.getKeyStroke ("ENTER"),       "startEditing");
        inputMap.put (KeyStroke.getKeyStroke ("ctrl ENTER"),  "startEditing");
        inputMap.put (KeyStroke.getKeyStroke ("shift SPACE"), "drillUp");
        inputMap.put (KeyStroke.getKeyStroke ("SPACE"),       "drillDown");

        ActionMap actionMap = tree.getActionMap ();
        Action selectPrevious = actionMap.get ("selectPrevious");
        Action selectParent   = actionMap.get ("selectParent");
        actionMap.put ("selectPrevious", new AbstractAction ()
        {
            public void actionPerformed (ActionEvent e)
            {
                if (tree.getLeadSelectionRow () == 0)
                {
                    if (root.graph != null)
                    {
                        root.graph.switchFocus (true);
                        return;
                    }
                    if (container.panelParent.panelEquations == PanelEquationTree.this)
                    {
                        container.switchFocus (true);
                        return;
                    }
                }
                selectPrevious.actionPerformed (e);
            }
        });
        actionMap.put ("selectParent", new AbstractAction ()
        {
            public void actionPerformed (ActionEvent e)
            {
                TreePath path = tree.getLeadSelectionPath ();
                if (path != null  &&  path.getPathCount () == 2  &&  tree.isCollapsed (path))  // This is a direct child of the root (which is not visible).
                {
                    if (root.graph != null)
                    {
                        root.graph.switchFocus (true);
                        return;
                    }
                    if (container.panelParent.panelEquations == PanelEquationTree.this)
                    {
                        container.switchFocus (true);
                        return;
                    }
                }
                selectParent.actionPerformed (e);
            }
        });
        actionMap.put ("moveUp", new AbstractAction ()
        {
            public void actionPerformed (ActionEvent e)
            {
                moveSelected (-1);
            }
        });
        actionMap.put ("moveDown", new AbstractAction ()
        {
            public void actionPerformed (ActionEvent e)
            {
                moveSelected (1);
            }
        });
        actionMap.put ("add", new AbstractAction ()
        {
            public void actionPerformed (ActionEvent e)
            {
                addAtSelected ("");
            }
        });
        actionMap.put ("delete", new AbstractAction ()
        {
            public void actionPerformed (ActionEvent e)
            {
                deleteSelected ();
            }
        });
        actionMap.put ("startEditing", new AbstractAction ()
        {
            public void actionPerformed (ActionEvent e)
            {
                TreePath path = tree.getSelectionPath ();
                if (path != null  &&  ! container.locked)
                {
                    boolean isControlDown = (e.getModifiers () & ActionEvent.CTRL_MASK) != 0;
                    if (isControlDown  &&  ! (path.getLastPathComponent () instanceof NodePart)) container.editor.multiLineRequested = true;
                    tree.startEditingAtPath (path);
                }
            }
        });
        actionMap.put ("drillUp", new AbstractAction ()
        {
            public void actionPerformed (ActionEvent e)
            {
                container.drillUp ();
            }
        });
        actionMap.put ("drillDown", new AbstractAction ()
        {
            public void actionPerformed (ActionEvent e)
            {
                // Find the nearest enclosing NodePart and drill into it.
                TreePath path = tree.getSelectionPath ();
                if (path == null) return;
                for (int i = path.getPathCount () - 1; i >= 0; i--)
                {
                    Object o = path.getPathComponent (i);
                    if (o instanceof NodePart)
                    {
                        container.drill ((NodePart) o);
                        break;
                    }
                }
            }
        });

        MouseAdapter mouseAdapter = new MouseAdapter ()
        {
            @Override
            public void mouseDragged (MouseEvent e)
            {
                // For some reason, a DnD gesture does not cause JTree to take focus.
                // This is a hack to grab focus in that case. At the start of a drag,
                // we receive a small handful of mouseDragged() messages. Perhaps
                // they stop because DnD takes over. In any case, this is sufficient to detect
                // the start of DnD and grab focus.
                if (! tree.isFocusOwner ()) tree.requestFocusInWindow ();
            }

            public void switchToTree ()
            {
                // When we grab focus via a direct click, need to note the title is no longer focused.
                if (root == null) return;
                if (root.graph != null)
                {
                    root.graph.titleFocused = false;
                    return;
                }
                PanelEquations pe = PanelModel.instance.panelEquations;
                if (PanelEquationTree.this == pe.panelParent.panelEquations) pe.titleFocused = false;
            }

            public void mouseClicked (MouseEvent e)
            {
                int x = e.getX ();
                int y = e.getY ();
                int clicks = e.getClickCount ();

                TreePath path = tree.getClosestPathForLocation (x, y);
                if (path != null)
                {
                    // Constrain click to be close to node content, but allow some slack.
                    Rectangle r = tree.getPathBounds (path);
                    r.width += 20;
                    r.width = Math.max (100, r.width);
                    if (! r.contains (x, y)) path = null;
                }

                if (SwingUtilities.isLeftMouseButton (e))
                {
                    if (clicks == 1)
                    {
                        switchToTree ();
                    }
                    else if (clicks == 2)  // Drill down on parts, or edit any other node type.
                    {
                        NodePart part = null;
                        if (path != null)
                        {
                            Object temp = path.getLastPathComponent ();
                            if (temp instanceof NodePart  &&  ! container.viewTree)
                            {
                                part = (NodePart) temp;
                                // and drill down
                            }
                            else  // any other node type
                            {
                                tree.setSelectionPath (path);
                                tree.startEditingAtPath (path);
                                return;
                            }
                        }
                        if (part == null) part = root;  // Drill down without selecting a specific node.
                        container.drill (part);
                    }
                }
                else if (SwingUtilities.isRightMouseButton (e))
                {
                    if (clicks == 1)  // Show popup menu
                    {
                        if (path != null)
                        {
                            switchToTree ();
                            tree.setSelectionPath (path);
                            takeFocus ();
                            container.menuPopup.show (tree, x, y);
                        }
                    }
                }
            }
        };
        tree.addMouseListener (mouseAdapter);
        tree.addMouseMotionListener (mouseAdapter);

        // Hack for slow Swing repaint when clicking to select new node
        tree.addTreeSelectionListener (new TreeSelectionListener ()
        {
            NodeBase oldSelection;
            Rectangle oldBounds;

            public void valueChanged (TreeSelectionEvent e)
            {
                if (! e.isAddedPath ()) return;
                TreePath path = e.getPath ();
                NodeBase newSelection = (NodeBase) path.getLastPathComponent ();
                if (newSelection == oldSelection) return;

                if (oldBounds != null) tree.paintImmediately (oldBounds);
                Rectangle newBounds = tree.getPathBounds (path);
                if (newBounds != null) tree.paintImmediately (newBounds);
                oldSelection = newSelection;
                oldBounds    = newBounds;
            }
        });

        tree.addTreeWillExpandListener (new TreeWillExpandListener ()
        {
            @Override
            public void treeWillExpand (TreeExpansionEvent event) throws ExpandVetoException
            {
            }

            @Override
            public void treeWillCollapse (TreeExpansionEvent event) throws ExpandVetoException
            {
                TreePath path = event.getPath ();
                NodeBase node = (NodeBase) path.getLastPathComponent ();
                if (node == root) throw new ExpandVetoException (event);
            }
        });

        tree.addTreeExpansionListener (new TreeExpansionListener ()
        {
            public void treeExpanded (TreeExpansionEvent event)
            {
                if (! expandGraphNode (event, true)) repaintSouth (event.getPath ());
            }

            public void treeCollapsed (TreeExpansionEvent event)
            {
                if (! expandGraphNode (event, false)) repaintSouth (event.getPath ());
            }

            public boolean expandGraphNode (TreeExpansionEvent event, boolean open)
            {
                TreePath path = event.getPath ();
                Object o = path.getLastPathComponent ();
                if (o instanceof NodePart)
                {
                    NodePart part = (NodePart) o;
                    GraphNode gn = part.graph;
                    if (gn != null)
                    {
                        if (open) gn.parent.setComponentZOrder (gn, 0);
                        Point     p = gn.getLocation ();
                        Dimension d = gn.getPreferredSize ();
                        Rectangle bounds = new Rectangle (p, d);
                        gn.animate (bounds);
                        gn.parent.scrollRectToVisible (bounds);
                        return true;
                    }
                }
                return false;
            }
        });

        tree.addFocusListener (new FocusListener ()
        {
            public void focusGained (FocusEvent e)
            {
                restoreFocus ();
            }

            public void focusLost (FocusEvent e)
            {
                // The shift to the editing component appears as a loss of focus.
                // The shift to a popup menu appears as a "temporary" loss of focus.
                if (! e.isTemporary ()  &&  ! tree.isEditing ()) yieldFocus ();
            }
        });

        setViewportView (tree);
    }

    public Dimension getMinimumSize ()
    {
        TreePath path = tree.getPathForRow (0);
        if (path == null) return super.getMinimumSize ();
        Dimension result = tree.getPathBounds (path).getSize ();
        // Add some compensation, so scroll bars don't overwrite part name.
        result.height += getHorizontalScrollBar ().getPreferredSize ().height;
        result.width  += getVerticalScrollBar   ().getPreferredSize ().width;
        return result;
    }

    public Dimension getPreferredSize ()
    {
        // Set row count so tree will request a reasonable size during processing of super.getPreferredSize()
        int rc = tree.getRowCount ();
        rc = Math.min (rc, 20);  // The default from openjdk source code for JTree
        rc = Math.max (rc, 6);   // An arbitrary lower limit
        tree.setVisibleRowCount (rc);

        Dimension result = super.getPreferredSize ();

        // If tree is empty, it could collapse to very small width. Keep this from getting smaller than cell editor preferred size.
        Insets insets = tree.getInsets ();
        result.width = Math.max (result.width, 100 + EquationTreeCellEditor.offsetPerLevel + insets.left + insets.right);

        return result;
    }

    /**
        Resize graph node based on any changes in our preferred size.
    **/
    public void animate ()
    {
        if (root == null) return;
        if (root.graph != null) root.graph.animate ();
        else if (root == container.part  &&  container.panelParent.isVisible ()) container.panelParent.animate ();
    }

    /**
        @param part Should always be non-null. To clear the tree, call clear() rather than setting null here.
    **/
    public void loadPart (NodePart part)
    {
        if (part == root) return;
        if (root != null) root.pet = null;

        root = part;
        root.pet = this;
        model.setRoot (root);  // triggers repaint, but may be too slow
        needsFullRepaint = true;  // next call to repaintSouth() will repaint everything
    }

    public void clear ()
    {
        if (container.active == this) container.active = null;
        if (root == null) return;
        root.pet = null;
        root = null;
        model.setRoot (null);
    }

    public void updateLock ()
    {
        tree.setEditable (! container.locked);
    }

    public StoredPath saveFocus (StoredPath previous)
    {
        // Save tree state, but only if it's better than the previously-saved state.
        if (previous == null  ||  tree.getSelectionPath () != null) return new StoredPath (tree);
        return previous;
    }

    public void yieldFocus ()
    {
        tree.stopEditing ();
        if (root == null) return;
        FocusCacheEntry fce = container.createFocus (root);
        fce.sp = saveFocus (fce.sp);
        tree.clearSelection ();

        // Auto-close graph node when it loses focus, if it was auto-opened.
        if (root.graph == null)
        {
            PanelEquations pe = PanelModel.instance.panelEquations;
            if (this == pe.panelParent.panelEquations)
            {
                boolean open = root.source.getBoolean ("$metadata", "gui", "bounds", "parent");
                if (! open) pe.panelParent.setOpen (false);
            }
        }
        else
        {
            boolean open = root.source.getBoolean ("$metadata", "gui", "bounds", "open");
            if (! open) root.graph.setOpen (false);
        }
    }

    public void takeFocus ()
    {
        if (tree.isFocusOwner ()) restoreFocus ();
        else                      tree.requestFocusInWindow ();  // Triggers focus listener, which calls restoreFocus()
    }

    /**
        Subroutine of takeFocus(). Called either directly by takeFocus(), or indirectly via focus listener on tree.
    **/
    public void restoreFocus ()
    {
        container.active = this;
        if (root != null)
        {
            if (root.graph != null) root.graph.restoreFocus ();  // Raise graph node to top of z-order.
            if (tree.getSelectionCount () == 0)
            {
                FocusCacheEntry fce = container.getFocus (root);
                if (fce != null  &&  fce.sp != null) fce.sp.restore (tree);
                if (tree.getSelectionCount () == 0) tree.setSelectionRow (0);  // First-time display
            }
        }
    }

    public void updateFilterLevel ()
    {
        if (root == null) return;
        root.filter (FilteredTreeModel.filterLevel);
        StoredPath path = new StoredPath (tree);
        model.reload (root);
        path.restore (tree);
    }

    public NodeBase getSelected ()
    {
        NodeBase result = null;
        TreePath path = tree.getSelectionPath ();
        if (path != null) result = (NodeBase) path.getLastPathComponent ();
        if (result != null) return result;
        return root;
    }

    public void addAtSelected (String type)
    {
        if (container.locked) return;
        NodeBase selected = getSelected ();
        if (selected == null) return;  // empty tree (should only occur in tree view); could create new model here, but better to do that from search list
        NodeBase editMe = selected.add (type, tree, null, null);
        if (editMe != null)
        {
            TreePath path = new TreePath (editMe.getPath ());

            if (editMe instanceof NodePart)
            {
                GraphNode gn = ((NodePart) editMe).graph;
                if (gn != null  &&  gn.panelEquations.tree != tree)  // Newly-created part is root of a tree in a new graph node.
                {
                    gn.title.startEditing ();
                    return;
                }
            }

            tree.scrollPathToVisible (path);
            tree.setSelectionPath (path);
            tree.startEditingAtPath (path);
        }
    }

    public void deleteSelected ()
    {
        if (container.locked) return;
        NodeBase selected = getSelected ();
        if (selected != null) selected.delete (tree, false);
    }

    public void watchSelected ()
    {
        if (container.locked) return;
        NodeBase selected = getSelected ();
        if (! (selected instanceof NodeVariable)) return;

        // Toggle watch on the selected variable
        MPart watch = (MPart) selected.source.child ("$metadata", "watch");
        if (watch != null  &&  watch.isFromTopDocument ())  // currently on, so turn it off
        {
            DeleteAnnotation d = new DeleteAnnotation ((NodeAnnotation) selected.child ("watch"), false);
            d.setSelection = false;
            PanelModel.instance.undoManager.add (d);
        }
        else  // currently off, so turn it on
        {
            AddAnnotation a = new AddAnnotation (selected, selected.getChildCount (), new MVolatile ("", "watch"));
            a.setSelection = false;
            PanelModel.instance.undoManager.add (a);
        }
    }

    public void moveSelected (int direction)
    {
        if (container.locked) return;
        TreePath path = tree.getSelectionPath ();
        if (path == null) return;

        NodeBase nodeBefore = (NodeBase) path.getLastPathComponent ();
        NodeBase parent     = (NodeBase) nodeBefore.getParent ();
        if (parent instanceof NodePart)  // Only parts support $metadata.gui.order
        {
            // First check if we can move in the filtered (visible) list.
            int indexBefore = model.getIndexOfChild (parent, nodeBefore);
            int indexAfter  = indexBefore + direction;
            if (indexAfter >= 0  &&  indexAfter < model.getChildCount (parent))
            {
                // Then convert to unfiltered indices.
                NodeBase nodeAfter = (NodeBase) model.getChild (parent, indexAfter);
                indexBefore = parent.getIndex (nodeBefore);
                indexAfter  = parent.getIndex (nodeAfter);
                PanelModel.instance.undoManager.add (new ChangeOrder ((NodePart) parent, indexBefore, indexAfter));
            }
        }
    }

    public void updateVisibility (TreeNode path[])
    {
        updateVisibility (this, path, -2, true);
    }

    public void updateVisibility (TreeNode path[], int index)
    {
        updateVisibility (this, path, index, true);
    }

    public void updateVisibility (TreeNode path[], int index, boolean setSelection)
    {
        updateVisibility (this, path, index, setSelection);
    }

    /**
        Ensure that the tree down to the changed node is displayed with correct visibility and override coloring.
        @param path Every node from root to changed node, including changed node itself.
        The trailing nodes are allowed to be disconnected from root in the filtered view of the model,
        and they are allowed to be deleted nodes. Note: deleted nodes will have null parents.
        Deleted nodes should already be removed from tree by the caller, with proper notification.
        @param index Position of the last node in its parent node. Only used if the last node has been deleted.
        A value of -1 causes selection to shift up to the parent.
        A value of -2 cause index to be derived from given path.
        @param setSelection Highlights the closest tree node to the given path. Only does something if tree is non-null. 
    **/
    public static void updateVisibility (PanelEquationTree pet, TreeNode path[], int index, boolean setSelection)
    {
        // Calculate index, if requested
        if (index == -2)
        {
            if (path.length < 2)
            {
                index = -1;
            }
            else
            {
                NodeBase c = (NodeBase) path[path.length - 1];
                NodeBase p = (NodeBase) path[path.length - 2];
                index = p.getIndexFiltered (c);
            }
        }

        // Prepare list of indices for final selection
        int[] selectionIndices = new int[path.length];
        for (int i = 1; i < path.length; i++)
        {
            NodeBase p = (NodeBase) path[i-1];
            NodeBase c = (NodeBase) path[i];
            selectionIndices[i] = FilteredTreeModel.getIndexOfChildStatic (p, c);  // Could be -1, if c has already been deleted.
        }

        // Adjust visibility
        int inserted = path.length;
        int removed  = path.length;
        int removedIndex = -1;
        for (int i = path.length - 1; i > 0; i--)
        {
            NodeBase p = (NodeBase) path[i-1];
            NodeBase c = (NodeBase) path[i];
            if (c.getParent () == null) continue;  // skip deleted nodes
            int filteredIndex = FilteredTreeModel.getIndexOfChildStatic (p, c);
            boolean filteredOut = filteredIndex < 0;
            if (c.visible (FilteredTreeModel.filterLevel))
            {
                if (filteredOut)
                {
                    p.unhide (c, null);  // silently adjust the filtering
                    inserted = i; // promise to notify model
                }
            }
            else
            {
                if (! filteredOut)
                {
                    p.hide (c, null);
                    removed = i;
                    removedIndex = filteredIndex;
                }
            }
        }

        if (pet == null) return;  // Everything below this line has to do with updating tree view.
        FilteredTreeModel model = (FilteredTreeModel) pet.tree.getModel ();

        // update color to indicate override state
        int lastChange = Math.min (inserted, removed);
        for (int i = 1; i < lastChange; i++)
        {
            // Since it is hard to measure current color, just assume everything needs updating.
            NodeBase c = (NodeBase) path[i];
            if (c.getParent () == null) continue;
            model.nodeChanged (c);
            Rectangle bounds = pet.tree.getPathBounds (new TreePath (c.getPath ()));
            if (bounds != null) pet.tree.paintImmediately (bounds);
        }

        if (lastChange < path.length)
        {
            NodeBase p = (NodeBase) path[lastChange-1];
            NodeBase c = (NodeBase) path[lastChange];
            int[] childIndices = new int[1];
            if (inserted < removed)
            {
                childIndices[0] = p.getIndexFiltered (c);
                model.nodesWereInserted (p, childIndices);
            }
            else
            {
                childIndices[0] = removedIndex;
                Object[] childObjects = new Object[1];
                childObjects[0] = c;
                model.nodesWereRemoved (p, childIndices, childObjects);
            }
            pet.repaintSouth (new TreePath (p.getPath ()));
        }

        // select last visible node
        int i = 1;
        for (; i < path.length; i++)
        {
            NodeBase c = (NodeBase) path[i];
            if (c.getParent () == null) break;
            if (! c.visible (FilteredTreeModel.filterLevel)) break;
        }
        i--;  // Choose the last good node
        NodeBase c = (NodeBase) path[i];
        if (i == path.length - 2)
        {
            index = Math.min (index, model.getChildCount (c) - 1);
            if (index >= 0) c = (NodeBase) model.getChild (c, index);
        }
        else if (i < path.length - 2)
        {
            int childIndex = Math.min (selectionIndices[i+1], model.getChildCount (c) - 1);
            if (childIndex >= 0) c = (NodeBase) model.getChild (c, childIndex);
        }

        TreePath selectedPath = new TreePath (c.getPath ());
        GraphNode gn = pet.root.graph;
        PanelEquations pe = PanelModel.instance.panelEquations;
        boolean selectTitle =  c == pet.root  &&  (gn != null  ||  pet == pe.panelParent.panelEquations);
        if (setSelection  &&  ! selectTitle)
        {
            // make tree visible
            if (gn == null) pe.panelParent.setOpen (true);
            else            gn.setOpen (true);
            pet.tree.scrollPathToVisible (selectedPath);
        }
        if (lastChange == path.length)
        {
            boolean expanded = pet.tree.isExpanded (selectedPath);
            model.nodeStructureChanged (c);  // Should this be more targeted?
            if (c != pet.root  &&  expanded) pet.tree.expandPath (selectedPath);
            pet.repaintSouth (selectedPath);
        }
        if (setSelection)
        {
            if (selectTitle)
            {
                if (gn == null) pe.switchFocus (true);
                else            gn.switchFocus (true);
            }
            else
            {
                pet.tree.setSelectionPath (selectedPath);
                FocusCacheEntry fce = pe.createFocus (pet.root);
                fce.titleFocused = false;  // so pet.takeFocus() does not claw focus onto title
                pet.takeFocus ();
            }
        }
    }

    public void updateOrder (TreeNode path[])
    {
        updateOrder (tree, path);
    }

    /**
        Records the current order of nodes in "gui.order", provided that metadata field exists.
        Otherwise, we assume the user doesn't care.
        @param path To the node that changed (added, deleted, moved). In general, this node's
        parent will be the part that is tracking the order of its children.
    **/
    public static void updateOrder (JTree tree, TreeNode path[])
    {
        NodePart parent = null;
        for (int i = path.length - 2; i >= 0; i--)
        {
            if (path[i] instanceof NodePart)
            {
                parent = (NodePart) path[i];
                break;
            }
        }
        if (parent == null) return;  // This should never happen, because root of tree is a NodePart.

        // Find $metadata/gui.order for the currently selected node. If it exists, update it.
        // Note that this is a modified version of moveSelected() which does not actually move
        // anything, and which only modifies an existing $metadata/gui.order, not create a new one.
        NodeAnnotations metadataNode = null;
        String order = null;
        Enumeration<?> i = parent.children ();
        while (i.hasMoreElements ())
        {
            NodeBase c = (NodeBase) i.nextElement ();
            String key = c.source.key ();
            if (order == null) order = key;
            else               order = order + "," + key;
            if (key.equals ("$metadata")) metadataNode = (NodeAnnotations) c;
        }
        if (metadataNode == null) return;

        NodeBase a = AddAnnotation.resolve (metadataNode, "gui.order");
        if (a != metadataNode)
        {
            MNode m = ((NodeAnnotation) a).folded;
            if (m.key ().equals ("order")  &&  m.parent ().key ().equals ("gui"))  // This check is necessary to avoid overwriting a pre-existing node folded under "gui" (for example, gui.bounds).
            {
                m.set (order);  // Shouldn't require change to tab stops, which should already be set.
                if (tree != null)
                {
                    NodeBase ap = (NodeBase) a.getParent ();
                    FontMetrics fm = a.getFontMetrics (tree);
                    ap.updateTabStops (fm);  // Cause node to update it's text.
                    ((FilteredTreeModel) tree.getModel ()).nodeChanged (a);
                }
            }
        }
    }

    public void scrollToVisible (NodePart part)
    {
        TreePath path = new TreePath (part.getPath ());
        tree.setSelectionPath (path);
        Rectangle rect = tree.getPathBounds (path);
        rect.height = tree.getHeight ();
        tree.scrollRectToVisible (rect);
    }

    public void repaintSouth (TreePath path)
    {
        Rectangle node    = tree.getPathBounds (path);
        Rectangle visible = getViewport ().getViewRect ();
        if (! needsFullRepaint  &&  node != null)
        {
            visible.height -= node.y - visible.y;
            visible.y       = node.y;
        }
        needsFullRepaint = false;
        tree.paintImmediately (visible);
    }
}
