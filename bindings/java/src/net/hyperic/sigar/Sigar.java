package net.hyperic.sigar;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

import java.util.List;
import java.util.Map;

import net.hyperic.jni.ArchLoaderException;
import net.hyperic.jni.ArchNotSupportedException;

/**
 * The Sigar class provides access to the sigar objects containing
 * system information.  The Sigar object itself maintains internal
 * state specific to each platform.  It also implements the SigarProxy
 * interface which provides caching at the Java level.
 */
public class Sigar implements SigarProxy {

    public static final long FIELD_NOTIMPL = -1;

    /**
     * The Sigar version in String form.
     */
    public static final String VERSION_STRING =
        SigarVersion.VERSION_STRING;

    /**
     * The date on which the Sigar binaries were built.
     */
    public static final String BUILD_DATE =
        SigarVersion.BUILD_DATE;

    private static SigarLoader loader = new SigarLoader(Sigar.class);
    private FileSystemMap mounts = null;

    int sigarWrapper = 0; //holds the sigar_t *
    long longSigarWrapper = 0; //same, but where sizeof(void*) > sizeof(int)

    // lastCpu is used to calculate the cpuPerc;
    private Cpu lastCpu;
    private Cpu[] lastCpuList;
    private static SigarProxy instance = null;

    static {
        try {
            load();
        } catch (SigarException e) {
            String msg = "Sigar.load: " + e.getMessage();
            try {
                SigarLog.debug(msg, e);
            } catch (NoClassDefFoundError ne) {
                //no log4j.jar
                System.err.println(msg);
                e.printStackTrace();
            }
        }
    }

    private static void load() throws SigarException {
        try {
            if (SigarLoader.IS_WIN32 &&
                System.getProperty("os.version").equals("4.0"))
            {
                String lib =
                    loader.findJarPath("pdh.dll") +
                    File.separator + "pdh.dll";
                loader.systemLoad(lib);
            }
            loader.load();
        } catch (ArchNotSupportedException e) {
            throw new SigarException(e.getMessage());
        } catch (ArchLoaderException e) {
            throw new SigarException(e.getMessage());
        } catch (UnsatisfiedLinkError e) {
            throw new SigarException(e.getMessage());
        }
    }

    /**
     * Format size in bytes to a human readable string.
     *
     * @param size The size to format.
     * @return The formatted string.
     */
    public static native String formatSize(long size);

    /**
     * Allocate and initialize the native Sigar object.
     */
    public Sigar() {
        try {
            open();
        } catch (SigarException e) {
            //XXX log?
        } catch (UnsatisfiedLinkError e) {
            //XXX log?
        }
    }

    /**
     * Convenience method to keep a sigar instance alive.
     * This instance is safe to share between threads.
     */
    public static synchronized SigarProxy getInstance() {
        if (instance == null) {
            instance = new SynchronizedSigar();
        }
        return instance;
    }

    protected void finalize() {
        close();
    }
    
    private native void open() throws SigarException;

    /**
     * Release any native resources associated with this sigar instance.
     * The sigar object is no longer usable after it has been closed.
     * If the close method is not called directly, the finalize method will
     * call it if the Sigar object is garbage collected.
     */
    public void close() {
        if (this.sigarWrapper != 0) {
            nativeClose();
        }
    }

    private native int nativeClose();

    /**
     * Get pid of the current process.
     * @exception SigarException on failure.
     */
    public native long getPid();


    /**
     * Get pid for the Windows service with the given name.
     * This method is implemented on Windows only as a helper
     * for PTQL.
     */
    public native long getServicePid(String name) throws SigarException;

    /**
     * Send a signal to a process.
     *
     * @param pid The process id.
     * @param signum The signal number.
     * @exception SigarException on failure.
     */
    public native void kill(long pid, int signum) throws SigarException;

    /**
     * Get system memory info.
     * @exception SigarException on failure.
     */
    public Mem getMem() throws SigarException {
        return Mem.fetch(this);
    }

    /**
     * Get system swap info.
     * @exception SigarException on failure.
     */
    public Swap getSwap() throws SigarException {
        return Swap.fetch(this);
    }

    /**
     * Get system cpu info.
     * @exception SigarException on failure.
     */
    public Cpu getCpu() throws SigarException {
        return (this.lastCpu = Cpu.fetch(this));
    }

    static void pause(int millis) {
        try {
            Thread.sleep(millis);
        } catch(InterruptedException e) { }
    }

    static void pause() {
        pause(500);
    }

    /**
     * Get system CPU info in percentage format. (i.e. fraction of 1)
     * @exception SigarException on failure.
     */
    public CpuPerc getCpuPerc() throws SigarException {
        Cpu oldCpu, curCpu;

        if (this.lastCpu == null){
            oldCpu = this.getCpu();
            pause();
        }
        else {
            oldCpu = this.lastCpu;
        }

        curCpu = this.getCpu();
        return CpuPerc.calculate(oldCpu, curCpu);
    }

    /**
     * Get system per-CPU info in percentage format. (i.e. fraction of 1)
     * @exception SigarException on failure.
     */
    public CpuPerc[] getCpuPercList() throws SigarException {
        Cpu[] oldCpuList, curCpuList;

        if (this.lastCpuList == null){
            oldCpuList = getCpuList();
            pause();
        }
        else {
            oldCpuList = this.lastCpuList;
        }

        curCpuList = getCpuList();

        int curLen = curCpuList.length, oldLen = oldCpuList.length;

        CpuPerc[] perc = new CpuPerc[curLen < oldLen ? curLen : oldLen];
        
        for (int i=0; i<curCpuList.length; i++) {
            Cpu curCpu = curCpuList[i], oldCpu;
 
            oldCpu = oldCpuList[i];

            perc[i] = CpuPerc.calculate(oldCpu, curCpu);
        }

        return perc;
    }

    /**
     * Get system uptime info.
     * @exception SigarException on failure.
     */
    public Uptime getUptime() throws SigarException {
        return Uptime.fetch(this);
    }

    /**
     * Get system load average.
     * @exception SigarException on failure.
     * @return The system load averages for the past 1, 5, and 15 minutes.
     */
    public native double[] getLoadAverage() throws SigarException;

    /**
     * Get system process list.
     * @exception SigarException on failure.
     * @return Array of process ids.
     */
    public native long[] getProcList() throws SigarException;

    /**
     * Get system process stats.
     * @exception SigarException on failure.
     */
    public ProcStat getProcStat() throws SigarException {
        return ProcStat.fetch(this);
    }

    private long convertPid(String pid) throws SigarException {
        if (pid.equals("$$")) {
            return getPid();
        }

        return Long.parseLong(pid);
    }

    /**
     * Get process memory info.
     * @param pid The process id.
     * @exception SigarException on failure.
     */
    public ProcMem getProcMem(long pid) throws SigarException {
        return ProcMem.fetch(this, pid);
    }

    public ProcMem getProcMem(String pid) throws SigarException {
        return getProcMem(convertPid(pid));
    }

    public ProcMem getMultiProcMem(String query) throws SigarException {
        return MultiProcMem.get(this, query);
    }

    /**
     * Get process state info.
     * @param pid The process id.
     * @exception SigarException on failure.
     */
    public ProcState getProcState(long pid) throws SigarException {
        return ProcState.fetch(this, pid);
    }

    public ProcState getProcState(String pid) throws SigarException {
        return getProcState(convertPid(pid));
    }

    /**
     * Get process time info.
     * @param pid The process id.
     * @exception SigarException on failure.
     */
    public ProcTime getProcTime(long pid) throws SigarException {
        return ProcTime.fetch(this, pid);
    }

    public ProcTime getProcTime(String pid) throws SigarException {
        return getProcTime(convertPid(pid));
    }

    /**
     * Get process cpu info.
     * @param pid The process id.
     * @exception SigarException on failure.
     */
    public ProcCpu getProcCpu(long pid) throws SigarException {
        return ProcCpu.get(this, pid);
    }

    public ProcCpu getProcCpu(String pid) throws SigarException {
        return getProcCpu(convertPid(pid));
    }

    public MultiProcCpu getMultiProcCpu(String query) throws SigarException {
        return MultiProcCpu.get(this, query);
    }

    /**
     * Get process credential info.
     * @param pid The process id.
     * @exception SigarException on failure.
     */
    public ProcCred getProcCred(long pid) throws SigarException {
        return ProcCred.fetch(this, pid);
    }

    public ProcCred getProcCred(String pid) throws SigarException {
        return getProcCred(convertPid(pid));
    }

    /**
     * Get process credential names.
     * @param pid The process id.
     * @exception SigarException on failure.
     */
    public ProcCredName getProcCredName(long pid) throws SigarException {
        return ProcCredName.fetch(this, pid);
    }

    public ProcCredName getProcCredName(String pid) throws SigarException {
        return getProcCredName(convertPid(pid));
    }

    /**
     * Get process file descriptor info.
     * @param pid The process id.
     * @exception SigarException on failure.
     */
    public ProcFd getProcFd(long pid) throws SigarException {
        return ProcFd.fetch(this, pid);
    }

    public ProcFd getProcFd(String pid) throws SigarException {
        return getProcFd(convertPid(pid));
    }

    /**
     * Get process current working directory.
     * @param pid The process id.
     * @exception SigarException on failure.
     */
    public ProcExe getProcExe(long pid) throws SigarException {
        return ProcExe.fetch(this, pid);
    }

    public ProcExe getProcExe(String pid) throws SigarException {
        return getProcExe(convertPid(pid));
    }

    /**
     * Get process arguments.
     * @param pid The process id.
     * @return Array of argument strings.
     * @exception SigarException on failure.
     */
    public native String[] getProcArgs(long pid) throws SigarException;

    public String[] getProcArgs(String pid) throws SigarException {
        return getProcArgs(convertPid(pid));
    }

    /**
     * Get process environment.
     * @param pid The process id.
     * @return Map of environment strings.
     * @exception SigarException on failure.
     */
    public Map getProcEnv(long pid) throws SigarException {
        return ProcEnv.getAll(this, pid);
    }

    public Map getProcEnv(String pid) throws SigarException {
        return getProcEnv(convertPid(pid));
    }

    /**
     * Get process environment variable value.
     * This method is intended to avoid the overhead
     * of creating a Map with all variables if only
     * a single variable is needed.
     * @param pid The process id.
     * @param key Environment variable name.
     * @return Environment variable value.
     * @exception SigarException on failure.
     */
    public String getProcEnv(long pid, String key) throws SigarException {
        return ProcEnv.getValue(this, pid, key);
    }

    public String getProcEnv(String pid, String key) throws SigarException {
        return getProcEnv(convertPid(pid), key);
    }

    /**
     * Get process loaded modules.<p>
     * Supported Platforms: Linux, Solaris and Windows.
     * @param pid The process id.
     * @return List of loaded modules.
     * @exception SigarException on failure.
     */
    private native List getProcModulesNative(long pid) throws SigarException;

    public List getProcModules(long pid) throws SigarException {
        return getProcModulesNative(pid);
    }

    public List getProcModules(String pid) throws SigarException {
        return getProcModules(convertPid(pid));
    }

    /**
     * Find the pid of the process which is listening on the given port.<p>
     * Supported Platforms: Linux and Windows XP only.
     * @param port The port number.
     * @return pid of the process.
     * @exception SigarException on failure.
     */
    public native long getProcPort(long port) throws SigarException;

    public long getProcPort(String port) throws SigarException {
        return getProcPort(Integer.parseInt(port));
    }

    /**
     * Get the cumulative cpu time for the calling thread.
     */
    public ThreadCpu getThreadCpu() throws SigarException {
        return ThreadCpu.fetch(this, 0);
    }

    private ThreadCpu diff = null;
    
    /**
     * Get cpu time since the last time cpu.gather was called
     * for the calling thread.
     */
    public ThreadCpu getThreadCpuDiff(ThreadCpu cpu)
        throws SigarException {

        long startTotal = cpu.total;
        long startUser  = cpu.user;
        long startSys   = cpu.sys;
    
        if (this.diff == null) {
            this.diff = new ThreadCpu();
        }

        cpu.gather(this, 0);
        
        this.diff.total = cpu.total - startTotal;
        this.diff.user  = cpu.user  - startUser;
        this.diff.sys   = cpu.sys   - startSys;
        
        return this.diff;
    }
    
    /**
     * Get list of file systems.
     * @exception SigarException on failure.
     */
    public native FileSystem[] getFileSystemList() throws SigarException;

    /**
     * Get file system usage.
     * @param name Name of the directory on which filesystem is mounted.
     * @exception SigarException on failure.
     */
    public FileSystemUsage getFileSystemUsage(String name)
        throws SigarException {
        if (name == null) {
            throw new SigarException("name cannot be null");
        }
        return FileSystemUsage.fetch(this, name);
    }

    /**
     * Get file system usage of a mounted directory.
     * This method checks that the given directory is mounted.
     * Unlike getFileSystemUsage() which only requires that the
     * directory exists within a mounted file system.
     * @param name Name of the directory on which filesystem is mounted.
     * @exception SigarException If given directory is not mounted.
     * @see net.hyperic.sigar.Sigar#getFileSystemUsage
     */
    public FileSystemUsage getMountedFileSystemUsage(String name)
        throws SigarException {

        if (!getFileSystemMap().isMounted(name)) {
            throw new SigarException(name + " is not a mounted filesystem");
        }

        return FileSystemUsage.fetch(this, name);
    }

    public FileSystemMap getFileSystemMap()
        throws SigarException {

        if (this.mounts == null) {
            this.mounts = new FileSystemMap();
        }

        this.mounts.init(getFileSystemList());

        return this.mounts;
    }

    public FileInfo getFileInfo(String name)
        throws SigarException {
        return FileInfo.fetchFileInfo(this, name);
    }

    public FileInfo getLinkInfo(String name)
        throws SigarException {
        return FileInfo.fetchLinkInfo(this, name);
    }

    public DirStat getDirStat(String name)
        throws SigarException {
        return DirStat.fetch(this, name);
    }

    /**
     * Get list of cpu infomation.
     * @exception SigarException on failure.
     */
    public native CpuInfo[] getCpuInfoList() throws SigarException;

    private native Cpu[] getCpuListNative() throws SigarException;

    /**
     * Get list of per-cpu metrics.
     * @exception SigarException on failure.
     */
    public Cpu[] getCpuList() throws SigarException {
        return (this.lastCpuList = getCpuListNative());
    }

    /**
     * Get list of network routes.
     * @exception SigarException on failure.
     */
    public native NetRoute[] getNetRouteList() throws SigarException;

    /**
     * Get list of network connections.
     * @exception SigarException on failure.
     */
    public native NetConnection[] getNetConnectionList(int flags)
        throws SigarException;

    /**
     * Get network interface configuration info.
     * @exception SigarException on failure.
     */
    public NetInterfaceConfig getNetInterfaceConfig(String name)
        throws SigarException {
        return NetInterfaceConfig.fetch(this, name);
    }

    /**
     * Get network interface stats.
     * @exception SigarException on failure.
     */
    public NetInterfaceStat getNetInterfaceStat(String name)
        throws SigarException {
        return NetInterfaceStat.fetch(this, name);
    }

    /**
     * Get the list of configured network interface names.
     * @exception SigarException on failure.
     */
    public native String[] getNetInterfaceList() throws SigarException;

    /**
     * Prompt for a password, disabling terminal echo
     * during user input.
     * @param prompt Text printed before disabling echo
     * @return Text entered by the user.
     * @throws IOException If input could not be read.
     * @throws SigarNotImplementedException If the native method
     * is not implemented on the current platform.
     */

    native static String getPasswordNative(String prompt)
        throws IOException, SigarNotImplementedException;

    /**
     * Prompt for a password, disabling terminal echo
     * during user input if possible.
     * @param prompt Text printed before disabling echo
     * @return Text entered by the user.
     * @throws IOException If input could not be read.
     */
    public static String getPassword(String prompt)
        throws IOException
    {
        try {
            return getPasswordNative(prompt);
        } catch (IOException e) {
            throw e;
        } catch (SigarNotImplementedException e) {
            //fallthrough
        }

        //fallback if native .so was not loaded or not supported
        System.out.print(prompt);

        return (new BufferedReader(new InputStreamReader(System.in))).
            readLine();
    }

    /**
     * Reliably retrieve the FQDN for a machine
     *
     * @return The fully qualified domain name of the machine.
     * @exception SigarException on failure.
     */
    public native String getFQDN() throws SigarException;

    /**
     * Enabling logging in the native Sigar code.
     * This method will hook log4j into the Sigar
     * native logging methods.  Note that the majority
     * of logging in the native code is only at the DEBUG
     * level.
     */
    public void enableLogging(boolean value) {
        if (value) {
            SigarLog.enable(this);
        }
        else {
            SigarLog.disable(this);
        }
    }
}
