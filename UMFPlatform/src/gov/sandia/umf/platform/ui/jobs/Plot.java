/*
Copyright 2013 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.umf.platform.ui.jobs;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import replete.util.FileUtil;

public class Plot
{
    public class Column
    {
    	public String header = "";
    	public List<Double> values = new ArrayList<Double> ();
    	public int startRow;
    }
    public List<Column> columns = new ArrayList<Column> ();
    public boolean isXycePRN = false;

    public Plot (String path)
    {
    	parsePrnFile (new File (path));
    }

    public JPanel createGraphPanel ()
    {
        XYDataset dataset = createDataset ();
        JFreeChart chart = createChart (dataset);
        return new ChartPanel (chart);
    }

    public void parsePrnFile (File f)
    {
        columns = new ArrayList<Column> ();
        isXycePRN = false;

        try
        {
            int row = 0;
            BufferedReader br = new BufferedReader (new FileReader (f));
            while (true)
            {
                String line = br.readLine ();
                if (line == null) break;  // indicates end of stream

                line = line.trim ();
            	if (line.length () == 0) continue;
            	if (line.startsWith ("End of")) continue;

                String[] parts = line.split ("\\s+");
                while (columns.size () < parts.length)
                {
                	Column c = new Column ();
                	c.startRow = row;
                	columns.add (c);
                }

                char firstCharacter = parts[0].charAt (0);
                if (firstCharacter > '9')  // column header
                {
            		isXycePRN = parts[0].equals ("Index");
                    for (int p = 0; p < parts.length; p++)
                    {
                    	columns.get (p).header = parts[p];
                    }
                }
                else
                {
                	int p = isXycePRN ? 1 : 0;  // skip paring Index column, since we don't use it
                    for (; p < parts.length; p++)
                    {
                        columns.get (p).values.add (Double.parseDouble (parts[p]));
                    }
                    row++;
                }
            }
            br.close ();
        }
        catch (IOException e)
        {
		}
    }

    public XYDataset createDataset ()
    {
        XYSeriesCollection dataset = new XYSeriesCollection();

        Column time = columns.get (0);  // fallback, in case we don't find it by name
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
    		}
    	}

    	for (Column c : columns)
        {
        	if (c == time) continue;
        	if (isXycePRN  &&  c == columns.get (0)) continue;

        	XYSeries series = new XYSeries (c.header);
            for (int r = 0; r < c.values.size (); r++)
            {
                series.add (time.values.get (r + c.startRow), c.values.get (r));
            }
            dataset.addSeries (series);
        }
        return dataset;
    }

    public JFreeChart createChart (final XYDataset dataset)
    {
        final JFreeChart chart = ChartFactory.createXYLineChart
        (
            null,                     // chart title
            "Time",                   // x axis label
            "Value",                  // y axis label
            dataset,                  // data
            PlotOrientation.VERTICAL,
            true,                     // include legend
            true,                     // tooltips
            false                     // urls
        );

        chart.setBackgroundPaint (Color.white);

        //final StandardLegend legend = (StandardLegend) chart.getLegend();
        //legend.setDisplaySeriesShapes(true);

        // get a reference to the plot for further customisation...
        final XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint (Color.lightGray);
        // plot.setAxisOffset(new Spacer(Spacer.ABSOLUTE, 5.0, 5.0, 5.0, 5.0));
        plot.setDomainGridlinePaint (Color.white);
        plot.setRangeGridlinePaint (Color.white);

        final XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
        renderer.setSeriesShapesVisible(0, false);
        renderer.setSeriesShapesVisible(1, false);
        plot.setRenderer(renderer);

        // change the auto tick unit selection to integer units only...
        final NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setStandardTickUnits(NumberAxis.createIntegerTickUnits());

        return chart;
    }
}
