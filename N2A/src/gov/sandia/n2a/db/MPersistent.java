/*
Copyright 2016-2022 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.db;

import java.util.TreeMap;

public class MPersistent extends MVolatile
{
    protected boolean needsWrite; // indicates that this node is new or has changed since it was last read from disk (and therefore should be written out)

    public MPersistent (MNode parent, String value, String name)
    {
        super (value, name, parent);
    }

	public synchronized void markChanged ()
	{
	    if (! needsWrite)
	    {
	        if (parent instanceof MPersistent) ((MPersistent) parent).markChanged ();
	        needsWrite = true;
	    }
	}

	public synchronized void clearChanged ()
	{
	    needsWrite = false;
        for (MNode i : this)
        {
            ((MPersistent) i).clearChanged ();
        }
	}

	public synchronized void clear ()
    {
        super.clear ();
        markChanged ();
    }

    protected synchronized void clearChild (String key)
    {
        super.clearChild (key);
        markChanged ();
    }

	public synchronized void set (String value)
    {
        if (value == null)
        {
            if (this.value != null)
            {
                this.value = null;
                markChanged ();
            }
        }
        else
        {
            if (this.value == null  ||  ! this.value.equals (value))
            {
                this.value = value;
                markChanged ();
            }
        }
    }

    public synchronized MNode set (String value, String key)
    {
        if (children == null) children = new TreeMap<String,MNode> (comparator);
        MNode result = children.get (key);
        if (result == null)
        {
            markChanged ();
            result = new MPersistent (this, value, key);
            children.put (key, result);
            return result;
        }
        result.set (value);
        return result;
    }

    public synchronized void move (String fromKey, String toKey)
    {
        if (toKey.equals (fromKey)) return;
        if (children == null) return;  // Nothing to move
        MNode source = children.get (fromKey);
        children.remove (toKey);
        children.remove (fromKey);
        if (source != null)
        {
            children.put (toKey, source);
            MPersistent p = (MPersistent) source;  // We can safely assume any child is MPersistent.
            p.name = toKey;
            p.markChanged ();
        }
        markChanged ();
    }
}
