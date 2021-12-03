/*
Copyright 2017-2021 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui.jobs;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gov.sandia.n2a.db.MDoc;
import gov.sandia.n2a.db.MNode;
import gov.sandia.n2a.language.UnitValue;
import tech.units.indriya.AbstractUnit;

public class OutputParser
{
    public List<Column> columns = new ArrayList<Column> ();
    public boolean      raw = true;  // Indicates that all column names are empty, likely the result of output() in raw mode. Will be changed to false if any non-empty column name is found.
    public String       delimiter = " ";
    public boolean      delimiterSet;
    public boolean      isXycePRN;
    public Column       time;
    public boolean      timeFound;  // Indicates that time is a properly-labeled column, rather than a fallback.
    public int          rows;
    public long         nextPosition;   // Position in file where read should resume when more data arrives.
    public float        defaultValue;
    public double       xmin = Double.NEGATIVE_INFINITY; // Bounds for chart. If not specified, then simply fit to data.
    public double       xmax = Double.POSITIVE_INFINITY; // Note that "x" is always time.
    public double       ymin = Double.NaN;
    public double       ymax = Double.NaN;

    public static Map<String,Color> HTMLcolors = new HashMap<String,Color> ();
    static
    {
        // Ideally, this map would contain all 140 standard HTML colors.
        // Use pre-fab colors where possible, but always match the defined values in the standard.
        // This means some constants in awt.Color can't be used.
        HTMLcolors.put ("black",   Color.black);
        HTMLcolors.put ("blue",    Color.blue);
        HTMLcolors.put ("cyan",    Color.cyan);
        HTMLcolors.put ("gray",    Color.gray);
        HTMLcolors.put ("lime",    Color.green);
        HTMLcolors.put ("green",   new Color (0x008000));
        HTMLcolors.put ("silver",  Color.lightGray);
        HTMLcolors.put ("magenta", Color.magenta);
        HTMLcolors.put ("orange",  new Color (0xFFA500));
        HTMLcolors.put ("pink",    new Color (0xFFC0CB));
        HTMLcolors.put ("red",     Color.red);
        HTMLcolors.put ("white",   Color.white);
        HTMLcolors.put ("yellow",  Color.yellow);
        HTMLcolors.put ("purple",  new Color (0x800080));
    }

    public void parse (Path f)
    {
        try (SeekableByteChannel channel = Files.newByteChannel (f);
             Reader reader = Channels.newReader (channel, "UTF-8");
             BufferedReader br = new BufferedReader (reader))
        {
            // Adjust start position to be first character after last end-of-line character previously read.
            if (nextPosition > 0)  // Note that "nextPosition" refers to bytes, not characters.
            {
                channel.position (nextPosition - 1);
                ByteBuffer buffer = ByteBuffer.allocate (1);
                channel.read (buffer);
                byte b = buffer.get (0);
                if (b != 13  &&  b != 10)  // Last character is not CR or LF
                {
                    // Remove last row of loaded data, since we will read the whole row again.
                    if (rows > 0)
                    {
                        rows--;
                        for (Column c : columns) if (c.values.size () + c.startRow > rows) c.values.remove (c.values.size () - 1);
                    }

                    // Step backwards until we find a CR or LF
                    boolean found = false;
                    nextPosition--;  // Because we already checked the last byte.
                    int step = 2048;
                    while (nextPosition > 0  &&  ! found)
                    {
                        nextPosition -= step;
                        if (nextPosition < 0)
                        {
                            step += nextPosition;
                            nextPosition = 0;
                        }

                        channel.position (nextPosition);
                        if (buffer.array ().length != step) buffer = ByteBuffer.allocate (step);
                        else                                buffer.clear ();
                        channel.read (buffer);
                        for (int i = step - 1; i >= 0; i--)
                        {
                            b = buffer.get (i);
                            if (b == 13  ||  b == 10)
                            {
                                found = true;
                                nextPosition += i + 1;
                                break;
                            }
                        }
                    }
                }
                channel.position (nextPosition);
            }

            while (true)
            {
                String line = br.readLine ();
                if (line == null) break;  // indicates end of stream
            	if (line.length () == 0) continue;
            	if (line.startsWith ("End of")) continue;

                if (! delimiterSet)
                {
                    if      (line.contains ("\t")) delimiter = "\t"; // highest precedence
                    else if (line.contains (","))  delimiter = ",";
                    // space character is lowest precedence
                    delimiterSet =  ! delimiter.equals (" ")  ||  ! line.trim ().isEmpty ();
                }
                String[] parts = line.split (delimiter, -1);  // -1 means that trailing delimiters will produce additional columns. Internal and C backends do not produce trailing delimiters, but other simulators might.
                int lastSize = columns.size ();
                while (columns.size () < parts.length)
                {
                	Column c = new Column ();
                	c.startRow = rows;
                	columns.add (c);
                }

                char fc = parts[0].charAt (0);  // first character
                if (fc == '-'  ||  fc == '+'  ||  fc == '.'  ||  fc >= '0'  &&  fc <= '9')  // number
                {
                    int p = isXycePRN ? 1 : 0;  // skip parsing Index column, since we don't use it
                    for (; p < parts.length; p++)
                    {
                        Column c = columns.get (p);
                        float value = defaultValue;
                        if (! parts[p].isEmpty ())
                        {
                            value = Float.parseFloat (parts[p]);
                            c.textWidth = Math.max (c.textWidth, parts[p].length ());
                        }
                        c.values.add (value);
                    }
                    for (; p < columns.size (); p++) columns.get (p).values.add (defaultValue);  // Because the structure is not sparse, we must fill out every row.
                    rows++;
                }
                else  // column header
                {
                    raw = false;
                    isXycePRN = parts[0].equals ("Index");
                    for (int p = lastSize; p < parts.length; p++)
                    {
                        columns.get (p).header = parts[p];
                    }
                }
            }

            nextPosition = Files.size (f);
        }
        catch (IOException e)
        {
		}
        if (columns.size () == 0) return;

        // Determine time column
        time = columns.get (0);  // fallback, in case we don't find it by name
        int timeMatch = 0;
        for (Column c : columns)
        {
            int potentialMatch = 0;
            if      (c.header.equals ("t"   )) potentialMatch = 1;
            else if (c.header.equals ("TIME")) potentialMatch = 2;
            else if (c.header.equals ("$t"  )) potentialMatch = 3;
            if (potentialMatch > timeMatch)
            {
                timeMatch = potentialMatch;
                time = c;
                timeFound = true;
            }
        }

        // Get rid of Index column. No subclass uses it.
        if (isXycePRN) columns.remove (0);

        // If there is a separate columns file, open and parse it.
        Path jobDir = f.getParent ();
        Path columnPath = jobDir.resolve (f.getFileName ().toString () + ".columns");
        if (Files.isReadable (columnPath))
        {
            MDoc columnFile = new MDoc (columnPath);
            for (MNode n : columnFile)
            {
                int columnIndex = Integer.valueOf (n.key ());
                if (columnIndex >= columns.size ()) break;
                Column c = columns.get (columnIndex);
                c.header = n.getOrDefault (c.header);

                String colorName = n.get ("color");
                if (! colorName.isEmpty ())
                {
                    try
                    {
                        c.color = Color.decode (colorName);
                    }
                    catch (NumberFormatException error)
                    {
                        // Attempt to interpret as a standard HTML color name.
                        // If no match is found, color remains null.
                        c.color = HTMLcolors.get (colorName.toLowerCase ());
                    }
                }

                colorName = n.get ("hue");  // Like "color", but expects default values for saturation and brightness.
                if (! colorName.isEmpty ())
                {
                    float hue = 0;
                    try {hue = Float.valueOf (colorName);}
                    catch (NumberFormatException error) {}
                    c.color = Color.getHSBColor (hue, 1.0f, 0.8f);
                }

                String scale = n.get ("scale");
                if (! scale.isEmpty ())
                {
                    c.scale = new UnitValue (scale);
                    if (c.scale.value == 0)    c.scale.value = 1;
                    if (c.scale.unit  == null) c.scale.unit  = AbstractUnit.ONE;
                }

                c.width = (float) n.getOrDefault (1.0, "width");  // Note that width=0 means narrowest line possible, which isn't necessarily the best default.

                String dash = n.get ("dash");
                if (! dash.isEmpty ())
                {
                    String pieces[] = dash.split (":");
                    c.dash = new float[pieces.length];
                    for (int i = 0; i < pieces.length; i++)
                    {
                        try {c.dash[i] = Float.valueOf (pieces[i]);}
                        catch (NumberFormatException error) {}
                    }
                }

                if (c == time)
                {
                    xmin = (float) n.getOrDefault (xmin, "xmin");
                    xmax = (float) n.getOrDefault (xmax, "xmax");
                    ymin = (float) n.getOrDefault (ymin, "ymin");
                    ymax = (float) n.getOrDefault (ymax, "ymax");
                }
            }
        }
    }

    /**
        Optional post-processing step to give columns their position in a spike raster.
    **/
    public void assignSpikeIndices ()
    {
        if (raw)
        {
            int i = 0;
            for (Column c : columns)
            {
                if (! timeFound  ||  c != time) c.index = i++;
            }
        }
        else
        {
            int nextColumn = -1;
            for (Column c : columns)
            {
                try
                {
                    c.index = Integer.parseInt (c.header);
                }
                catch (NumberFormatException e)
                {
                    c.index = nextColumn--;
                }
            }
        }
    }

    public Column getColumn (String columnName)
    {
        for (Column c : columns) if (c.header.equals (columnName)) return c;
        return null;
    }

    public float get (String columnName)
    {
        return get (columnName, -1);
    }

    public float get (int columnNumber)
    {
        return get (columnNumber, -1);
    }

    public float get (String columnName, int row)
    {
        Column c = getColumn (columnName);
        if (c == null) return defaultValue;
        return c.get (row, defaultValue);
    }

    public float get (int columnNumber, int row)
    {
        if (columnNumber >= columns.size ()) return defaultValue;
        return columns.get (columnNumber).get (row, defaultValue);
    }

    public boolean hasData ()
    {
        for (Column c : columns) if (! c.values.isEmpty ()) return true;
        return false;
    }

    public boolean hasHeaders ()
    {
        for (Column c : columns) if (! c.header.isEmpty ()) return true;
        return false;
    }

    public static class Column
    {
        public String      header = "";
        public int         index;  // If this is a spike raster, then header should convert to an integer.
        public List<Float> values = new ArrayList<Float> ();
        public int         startRow;
        public int         textWidth;
        public double      min    = Double.POSITIVE_INFINITY;
        public double      max    = Double.NEGATIVE_INFINITY;
        public double      range;
        public UnitValue   scale;
        public Color       color;
        public float       width  = 1;
        public float[]     dash;
        public Object      data;  // optional data that client code associates with this column

        public void computeStats ()
        {
            for (Float f : values)
            {
                if (f.isInfinite ()  ||  f.isNaN ()) continue;
                min = Math.min (min, f);
                max = Math.max (max, f);
            }
            if (Double.isInfinite (max))  // There was no good data. If max is infinite, then so is min.
            {
                // Set defensive values, so plot doesn't explode
                range = 0;
                min   = 0;
                max   = 0;
            }
            else
            {
                range = max - min;
            }
        }

        public float get ()
        {
            return get (-1, 0);
        }

        public float get (int row)
        {
            return get (row, 0);
        }

        public float get (int row, float defaultValue)
        {
            //if (row < 0) return value;  TODO: implement line-by-line reading mode. row==-1 means retrieve current value. See OutputParser.h
            row -= startRow;
            if (row < 0  ||  row >= values.size ()) return defaultValue;
            return values.get (row);
        }
    }

    public static class ColumnComparator implements Comparator<Column>
    {
        public int compare (Column a, Column b)
        {
            if (a.range > b.range) return  1;
            if (a.range < b.range) return -1;
            return 0;
        }
    }
}
