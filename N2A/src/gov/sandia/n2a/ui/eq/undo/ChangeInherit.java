/*
Copyright 2016-2023 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui.eq.undo;

import java.util.List;

import javax.swing.tree.TreeNode;
import javax.swing.undo.CannotRedoException;

import gov.sandia.n2a.ui.eq.FilteredTreeModel;
import gov.sandia.n2a.ui.eq.PanelEquationGraph;
import gov.sandia.n2a.ui.eq.PanelEquationTree;
import gov.sandia.n2a.ui.eq.PanelEquations;
import gov.sandia.n2a.ui.eq.PanelModel;
import gov.sandia.n2a.ui.eq.tree.NodeBase;
import gov.sandia.n2a.ui.eq.tree.NodeInherit;
import gov.sandia.n2a.ui.eq.tree.NodePart;

public class ChangeInherit extends UndoableView
{
    protected List<String> path;
    protected String       valueBefore;
    protected String       valueAfter;
    public    boolean      connection;  // Indicates that this object was created by the search panel to update a newly-created connection.

    /**
        @param container The direct container of the node being changed.
    **/
    public ChangeInherit (NodeInherit node, String valueAfter)
    {
        path            = node.getKeyPath ();  // includes "$inherit"
        valueBefore     = node.source.get ();
        this.valueAfter = valueAfter;
    }

    public void undo ()
    {
        super.undo ();
        apply (valueBefore);
    }

    public void redo ()
    {
        super.redo ();
        apply (valueAfter);
    }

    public void apply (String value)
    {
        NodeBase node = NodeBase.locateNode (path);
        if (node == null) throw new CannotRedoException ();
        NodePart parent      = (NodePart) node.getParent ();
        NodePart grandparent = (NodePart) parent.getTrueParent ();

        PanelEquations pe = PanelModel.instance.panelEquations;
        PanelEquationTree pet = node.getTree ();
        FilteredTreeModel model = null;
        if (pet != null) model = (FilteredTreeModel) pet.tree.getModel ();
        PanelEquationGraph peg = pe.panelEquationGraph;

        node.source.set (value);  // Complex restructuring happens here.

        parent.build ();
        if (grandparent == null) parent     .findConnections ();
        else                     grandparent.findConnections ();
        parent.rebuildPins ();
        parent.filter ();
        if (parent == pe.part)
        {
            peg.reloadPart ();
            parent.filter ();  // Ensure that parts are not visible in parent panel.
        }

        if (pet == null)
        {
            if (parent.graph != null) parent.graph.updateTitle ();
        }
        else
        {
            model.nodeStructureChanged (parent);
            TreeNode[] nodePath = parent.child ("$inherit").getPath ();
            pet.updateOrder (nodePath);
            pet.updateVisibility (nodePath);
            pet.animate ();
        }

        if (parent != pe.part)
        {
            peg.updatePins ();
            peg.reconnect ();
            peg.repaint ();
        }

        if (parent.getTrueParent () == null)  // root node, so update categories in search list
        {
            PanelModel.instance.panelSearch.search ();
        }
    }
}
