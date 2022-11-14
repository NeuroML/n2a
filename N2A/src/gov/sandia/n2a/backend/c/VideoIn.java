/**
Copyright 2022 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
**/

package gov.sandia.n2a.backend.c;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferShort;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import gov.sandia.n2a.db.MVolatile;
import gov.sandia.n2a.host.Host;
import gov.sandia.n2a.ui.jobs.NodeJob;

public class VideoIn extends NativeResource implements Runnable
{
    protected static native String  suffixes    ();
    protected static native long    construct   (String path);
    protected static native void    open        (long handle, String path);
    protected static native void    close       (long handle);
    protected static native void    seekFrame   (long handle, int frame);
    protected static native void    seekTime    (long handle, double timestamp);
    protected static native Image   readNext    (long handle);
    protected static native boolean good        (long handle);
    protected static native String  get         (long handle, String name);
    protected static native void    set         (long handle, String name, String value);

    /**
        Makes FFmpeg available to the JVM on localhost via JNI.
        This should not be called from EDT. It can take a long time.
        Supports lazy evaluation by making a lightweight check before doing any work.
    **/
    public static void prepareJNI ()
    {
        Host localhost = Host.get ("localhost");
        if (localhost.objects.containsKey ("ffmpegJNI")) return;  // Only set after successful load of JNI DLLs.

        try
        {
            // Check runtime
            JobC t = new JobC (new MVolatile ());
            Path resourceDir = localhost.getResourceDir ();
            t.runtimeDir     = resourceDir.resolve ("backend").resolve ("c");
            t.localJobDir    = t.runtimeDir;
            t.jobDir         = t.runtimeDir;
            t.T              = "float";
            t.env            = localhost;
            t.detectExternalResources ();
            t.rebuildRuntime ();

            // Load JNI libs
            CompilerFactory factory = BackendC.getFactory (localhost);
            String prefix = factory.prefixLibrary ();
            String suffix = factory.suffixLibraryShared ();
            String runtimeName = prefix + t.runtimeName () + suffix;
            Path runtimePath = t.runtimeDir.resolve (runtimeName).toAbsolutePath ();
            // This is super ugly because Java doesn't support setting a search path
            // for dependent libraries at run time.
            Path avutilPath     = null;
            Path swresamplePath = null;
            // swscale
            // postproc
            Path avcodecPath    = null;
            Path avformatPath   = null;
            // avfilter
            // avdevice
            try (DirectoryStream<Path> stream = Files.newDirectoryStream (t.ffmpegBinDir))
            {
                for (Path path : stream)
                {
                    String fileName = path.getFileName ().toString ();
                    if (! fileName.startsWith (prefix)) continue;
                    if (! fileName.endsWith (suffix)) continue;
                    // The library file name may contain version number.
                    if      (fileName.contains ("avutil"    )) avutilPath     = path;
                    else if (fileName.contains ("swresample")) swresamplePath = path;
                    else if (fileName.contains ("avcodec"   )) avcodecPath    = path;
                    else if (fileName.contains ("avformat"  )) avformatPath   = path;
                }
            }
            System.load (avutilPath    .toAbsolutePath ().toString ());
            System.load (swresamplePath.toAbsolutePath ().toString ());
            System.load (avcodecPath   .toAbsolutePath ().toString ());
            System.load (avformatPath  .toAbsolutePath ().toString ());
            System.load (runtimePath   .toAbsolutePath ().toString ());

            // Get suffixes. If this works, then FFmpeg support has been successfully loaded.
            String[] suffixes = suffixes ().split (",");
            NodeJob.videoSuffixes.addAll (Arrays.asList (suffixes));
            NodeJob.videoSuffixes.remove ("");

            localhost.objects.put ("ffmpegJNI", true);
        }
        catch (Throwable t)
        {
            System.err.println (t.getMessage ());
        }
    }

    public VideoIn (Path path)
    {
        super (construct (path.toString ()), true);
    }

    public void openFile (Path path)
    {
        open (handle, path.toString ());
    }

    public void closeFile ()
    {
        close (handle);
    }

    public void run ()
    {
        close ();
    }

    public void seekFrame (int frame)
    {
        seekFrame (handle, frame);
    }

    public void seekTime (double timestamp)
    {
        seekTime (handle, timestamp);
    }

    public BufferedImage readNext ()
    {
        Image image = readNext (handle);
        if (image == null) return null;
        byte[] B = image.buffer;
        BufferedImage result = new BufferedImage (image.width, image.height, image.format);
        SampleModel sm = result.getSampleModel ();
        switch (sm.getDataType ())
        {
            case DataBuffer.TYPE_INT:
            {
                int count = B.length / 4;
                int[] ints = new int[count];  // Assume that buffer.length is a multiple of 4.
                for (int i = 0; i < count; i++)
                {
                    int b = i * 4;
                    // Note the that the masks are necessary, since byte is treated as signed, so the int may have sign extension.
                    int b0 = B[b]   & 0xFF;
                    int b1 = B[b+1] & 0xFF;
                    int b2 = B[b+2] & 0xFF;
                    int b3 = B[b+3] & 0xFF;
                    // The following is little-endian. TODO: handle big-endian processors (which are rare in practice).
                    ints[i] = b3 << 24 | b2 << 16 | b1 << 8 | b0;
                }
                DataBufferInt buffer = new DataBufferInt (ints, count);
                result.setData (Raster.createRaster (sm, buffer, new Point ()));
                break;
            }
            case DataBuffer.TYPE_SHORT:
            case DataBuffer.TYPE_USHORT:
            {
                int count = B.length / 2;
                short[] shorts = new short[count];  // Assume that buffer.length is a multiple of 4.
                for (int i = 0; i < count; i++)
                {
                    int b = i * 2;
                    int b0 = B[b]   & 0xFF;
                    int b1 = B[b+1] & 0xFF;
                    shorts[i] = (short) (b1 << 8 | b0);
                }
                DataBufferShort buffer = new DataBufferShort (shorts, count);
                result.setData (Raster.createRaster (sm, buffer, new Point ()));
                break;
            }
            default:
            {
                DataBufferByte buffer = new DataBufferByte (B, B.length);
                result.setData (Raster.createRaster (sm, buffer, new Point ()));
            }
        }
        return result;
    }

    public boolean good ()
    {
        return good (handle);
    }

    public String get (String name)
    {
        return get (handle, name);
    }

    public void set (String name, String value)
    {
        set (handle, name, value);
    }

    // For JNI exchange, BufferedImage is too complex to work with.
    // It's too locked down, with too many wrapper objects around the actual data.
    // This struct allows easy construction and passing back of the key data.
    // Then we do the rest of the work in Java-land.
    public static class Image
    {
        public int    width;
        public int    height;
        public int    format;
        public byte[] buffer;

        public Image (int width, int height, int format, int size)
        {
            this.width  = width;
            this.height = height;
            this.format = format;
            buffer = new byte[width * height * size];
        }
    }
}
