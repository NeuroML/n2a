/*
Copyright 2016 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui.jobs;

import javax.swing.Icon;
import javax.swing.tree.DefaultMutableTreeNode;

@SuppressWarnings("serial")
public class NodeBase extends DefaultMutableTreeNode
{
    public boolean markDelete;  // Indicates that this node should be drawn with strikethru font, indicating that it is queued to be deleted.

    public Icon getIcon (boolean expanded)
    {
        return null;
    }

    public String toString ()
    {
        String result = super.toString ();
        if (markDelete) result = "<html><s>" + result + "</s></html>";
        return result;
    }
}
