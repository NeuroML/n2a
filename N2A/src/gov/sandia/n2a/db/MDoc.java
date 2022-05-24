/*
Copyright 2016-2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.db;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.TreeMap;

/**
    Stores a document in memory and coordinates with its persistent form on disk.
    We assume that only one instance of this class exists for a given disk document
    at any given moment, and that no other process in the system modifies the file on disk.

    We inherit the value field from MVolatile. Since we don't really have a direct value,
    we store a copy of the file name there. This is used to implement several functions, such
    as renaming. It also allows an instance to stand alone, without being a child of an MDir.

    We inherit the children collection from MVolatile. This field is left null until we first
    read in the associated file on disk. If the file is empty or non-existent, children becomes
    non-null but empty. Thus, whether children is null or not safely indicates the need to load.
**/
public class MDoc extends MPersistent
{
    /**
        Constructs a document as a child of an MDir.
        In this case, the key contains the file name in the dir, and the full path is constructed
        when needed using information from the parent.
    **/
    protected MDoc (MDir parent, String key)
    {
        this (parent, null, key);
    }

    /**
        Constructs a stand-alone document with blank key.
        In this case, the value contains the full path to the file on disk.
    **/
    public MDoc (Path path)
    {
        this (null, path.toAbsolutePath ().toString (), null);
    }

    /**
        Constructs a stand-alone document with specified key.
        In this case, the value contains the full path to the file on disk.
    **/
    public MDoc (Path path, String key)
    {
        this (null, path.toAbsolutePath ().toString (), key);
    }

    protected MDoc (MDocGroup parent, String path, String name)
    {
        super (parent, path, name);
    }

    /**
        The value of an MDoc is defined to be its full path on disk.
        Note that the key for an MDoc depends on what kind of collection contains it.
        In an MDir, the key is the primary file name (without path prefix and suffix).
        For a stand-alone document the key is arbitrary, and the document may be stored
        in another MNode with arbitrary other objects.
    **/
    public synchronized String getOrDefault (String defaultValue)
    {
        if (parent instanceof MDocGroup) return ((MDocGroup) parent).pathForDoc (name).toAbsolutePath ().toString ();
        if (value == null) return defaultValue;
        return value.toString ();
    }

    public synchronized void markChanged ()
    {
        if (needsWrite) return;

        // If this is a new document, then treat it as if it were already loaded.
        // If there is content on disk, it will be blown away.
        if (children == null) children = new TreeMap<String,MNode> (comparator);
        needsWrite = true;
        if (parent instanceof MDocGroup)
        {
            synchronized (parent)
            {
                ((MDocGroup) parent).writeQueue.add (this);
            }
        }
    }

    /**
        Removes this document from persistent storage, but retains its contents in memory.
    **/
    public void delete ()
    {
        if (parent == null)
        {
            try
            {
                Files.delete (Paths.get (value.toString ()));
            }
            catch (IOException e)
            {
                System.err.println ("Failed to delete file: " + value);
            }
        }
        else parent.clear (name);
    }

    protected synchronized MNode getChild (String key)
    {
        if (children == null) load ();  // redundant with the guard in load(), but should save time in the common case that file is already loaded
        return children.get (key);
    }

    protected synchronized void clearChild (String key)
    {
        if (children == null) load ();
        super.clearChild (key);
    }

    public synchronized int size ()
    {
        if (children == null) load ();
        return children.size ();
    }

    /**
        If this is a stand-alone document, then move the file on disk.
        Otherwise, do nothing. DeleteDoc.undo() relies on this class to do nothing for a regular doc,
        because it uses merge() to restore the data, and merge() will touch the root value.
    **/
    public synchronized void set (String value)
    {
        if (parent != null) return;  // Not stand-alone, so ignore.
        if (value.equals (this.value)) return;  // Don't call file move if location on disk has not changed.
        try
        {
            Files.move (Paths.get (this.value.toString ()), Paths.get (value), StandardCopyOption.REPLACE_EXISTING);
            this.value = value;
        }
        catch (IOException e)
        {
            System.err.println ("Failed to move file: " + this.value + " --> " + value);
        }
    }

    public synchronized MNode set (String value, String key)
    {
        if (children == null) load ();
        return super.set (value, key);
    }

    public synchronized void move (String fromKey, String toKey)
    {
        if (toKey.equals (fromKey)) return;
        if (children == null) load ();
        super.move (fromKey, toKey);
    }

    public synchronized Iterator<MNode> iterator ()
    {
        if (children == null) load ();
        return super.iterator ();
    }

    public synchronized Path path ()
    {
        if (parent instanceof MDocGroup) return ((MDocGroup) parent).pathForDoc (name);
        return Paths.get (value.toString ());  // for stand-alone document
    }

	/**
        We only load once. We assume no other process is modifying the files, so once loaded, we know its exact state.
	**/
	public synchronized void load ()
	{
	    if (children != null) return;  // already loaded
	    children = new TreeMap<String,MNode> (comparator);
        Path file = path ();
        needsWrite = true;  // lie to ourselves, to prevent being put onto the MDir write queue
        try (BufferedReader br = Files.newBufferedReader (file))
        {
            Schema.readAll (this, br);
        }
        catch (IOException e) {}  // This exception is common for a newly created doc that has not yet been flushed to disk.
        clearChanged ();  // After load(), clear the slate so we can detect any changes and save the document.
	}

	public synchronized void save ()
	{
	    if (! needsWrite) return;
        Path file = path ();
	    try
	    {
	        Files.createDirectories (file.getParent ());
	        try (BufferedWriter writer = Files.newBufferedWriter (file))
	        {
	            Schema.latest ().writeAll (this, writer);
	            clearChanged ();
	        }
	    }
	    catch (IOException e)
	    {
            System.err.println ("Failed to write file: " + file);
            e.printStackTrace ();
	    }
	}
}
