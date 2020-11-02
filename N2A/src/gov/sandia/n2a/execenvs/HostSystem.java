/*
Copyright 2013-2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.execenvs;

import gov.sandia.n2a.db.AppData;
import gov.sandia.n2a.db.MNode;
import gov.sandia.n2a.db.MVolatile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
    Encapsulates access to a computer system, whether it is a workstation, a supercomputer or a "cloud" service.
    Services include:
    <ul>
    <li>File access
    <li>Process management (starting, monitoring, stopping)
    <li>Resource monitoring (memory, disk capacity, processor load)
    </ul>
**/
public abstract class HostSystem
{
    public String name;                        // Simple handle used internally. Typically the same as host name. However, IP address or host name is specified separately.
    public MNode  metadata = new MVolatile (); // Collection of attributes that describe the target, including login information, directory structure and command forms.

    protected static Map<String,HostSystem> hosts    = new HashMap<String,HostSystem> ();
    protected static int                    jobCount = 0;

    public static HostSystem get (String hostname)
    {
        // Lazy initialization of host collection
        if (hosts.isEmpty ())
        {
            HostSystem localhost;
            if (isWindows ()) localhost = new Windows ();
            else              localhost = new Linux ();  // Should be compatible with Mac bash shell.
            localhost.name = "localhost";
            hosts.put (localhost.name, localhost);

            // TODO: load configured remote hosts from app data
        }

        HostSystem result = hosts.get (hostname);
        if (result == null) result = hosts.get ("localhost");
        return result;
    }

    /**
        Determines if this application is running on a Windows system.
        Not to be confused with the type of system a particular job executes on.
    **/
    public static boolean isWindows ()
    {
        return System.getProperty ("os.name").toLowerCase ().indexOf ("win") >= 0;
    }

    /**
        Determines if this application is running on a Mac system.
        Not to be confused with the type of system a particular job executes on.
    **/
    public static boolean isMac ()
    {
        return System.getProperty ("os.name").toLowerCase ().indexOf ("mac") >= 0;
    }

    public abstract boolean   isActive       (MNode job)                 throws Exception;  // check if the given job is active
    public abstract Set<Long> getActiveProcs ()                          throws Exception;  // enumerate all of our active jobs
    public abstract void      submitJob      (MNode job, String command) throws Exception;
    public abstract void      killJob        (long pid, boolean force)   throws Exception;

    public void deleteJob (String jobName) throws Exception
    {
        Path resourceDir = getResourceDir ();
        Path jobsDir     = resourceDir.resolve ("jobs");
        Path jobDir      = jobsDir.resolve (jobName);
        deleteDirectory (jobDir);
    }

    public Path getResourceDir () throws Exception
    {
        return Paths.get (AppData.properties.get ("resourceDir"));  // Only suitable for localhost. Must override for remote systems.
    }

    public String quotePath (Path path)
    {
        return "'" + path + "'";
    }

    /**
        Determine memory usage for a given job.
    **/
    public abstract long getProcMem (long pid) throws Exception;

    public long getMemoryPhysicalTotal ()
    {
        OperatingSystemMXBean OS = ManagementFactory.getOperatingSystemMXBean ();
        try
        {
            return (Long) invoke (OS, "getTotalPhysicalMemorySize");
        }
        catch (Exception e)
        {
            System.out.println ("Lack getTotalPhysicalMemorySize call");
            return 0;
        }
    }

    public long getMemoryPhysicalFree ()
    {
        OperatingSystemMXBean OS = ManagementFactory.getOperatingSystemMXBean ();
        try
        {
            return (Long) invoke (OS, "getFreePhysicalMemorySize");
        }
        catch (Exception e)
        {
            System.out.println ("Lack getFreePhysicalMemorySize call");
            return 0;
        }
    }

    public int getProcessorTotal ()
    {
        // We will assume that processors available to JVM are exactly the same as total system processors.
        return Runtime.getRuntime ().availableProcessors ();
    }

    public double getProcessorLoad ()
    {
        OperatingSystemMXBean OS = ManagementFactory.getOperatingSystemMXBean ();
        try
        {
            return (Long) invoke (OS, "getSystemCpuLoad");
        }
        catch (Exception e)
        {
            return OS.getSystemLoadAverage ();  // TODO: known to fail on Windows
        }
    }


    // Utility functions -----------------------------------------------------

    public static Object invoke (Object target, String methodName, Object... args)
    {
        Class<?> clazz = target.getClass ();
        try
        {
            Class<?>[] parameterTypes = new Class<?>[args.length];
            for (int a = 0; a < args.length; a++) parameterTypes[a] = args[a].getClass ();
            Method m = clazz.getMethod (methodName, parameterTypes);
            m.setAccessible (true);
            return m.invoke (target, args);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public static void downloadFile (Path remotePath, Path localPath) throws Exception
    {
        Files.copy (remotePath, localPath, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void stringToFile (Path path, String value) throws IOException
    {
        // Writes string as UTF-8
        try (BufferedWriter writer = Files.newBufferedWriter (path))
        {
            writer.write (value);
        }
    }

    public static String fileToString (Path path)
    {
        try (InputStream fis = Files.newInputStream (path))
        {
            return streamToString (fis);
        }
        catch (IOException e)
        {
            return "";
        }
    }

    public static String streamToString (InputStream input)
    {
        try (BufferedReader reader = new BufferedReader (new InputStreamReader (input, "UTF-8")))
        {
            return reader.lines ().collect (Collectors.joining ("\n"));
        }
        catch (IOException e)
        {
            return "";
        }
    }

    public static long lastModified (Path path)
    {
        try
        {
            return Files.getLastModifiedTime (path).toMillis ();
        }
        catch (IOException e1)
        {
            return 0;
        }
    }

    public static void deleteDirectory (Path path)
    {
        try (Stream<Path> walk = Files.walk (path))
        {
            walk.sorted (Comparator.reverseOrder ()).forEach (t ->
            {
                try {Files.delete (t);}
                catch (IOException e) {}
            });
        }
        catch (IOException e) {}  // Main cause for this would be if "path" doesn't exist, so we don't care.
    }
}
