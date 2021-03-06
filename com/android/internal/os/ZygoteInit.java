/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.os;

import static android.system.OsConstants.POLLIN;
import static android.system.OsConstants.S_IRWXG;
import static android.system.OsConstants.S_IRWXO;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.net.LocalServerSocket;
import android.opengl.EGL14;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.text.Hyphenator;
import android.util.EventLog;
import android.util.Log;
import android.webkit.WebViewFactory;

import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;
import dalvik.system.VMRuntime;

import libcore.io.IoUtils;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Startup class for the zygote process.
 * <p>
 * Pre-initializes some classes, and then waits for commands on a UNIX domain
 * socket. Based on these commands, forks off child processes that inherit
 * the initial state of the VM.
 * <p>
 * Please see {@link ZygoteConnection.Arguments} for documentation on the
 * client protocol.
 *
 * @hide
 */
public class ZygoteInit {
    private static final String TAG = "Zygote";

    private static final String PROPERTY_DISABLE_OPENGL_PRELOADING = "ro.zygote.disable_gl_preload";

    private static final String ANDROID_SOCKET_PREFIX = "ANDROID_SOCKET_";

    private static final int LOG_BOOT_PROGRESS_PRELOAD_START = 3020;
    private static final int LOG_BOOT_PROGRESS_PRELOAD_END = 3030;

    /**
     * when preloading, GC after allocating this many bytes
     */
    private static final int PRELOAD_GC_THRESHOLD = 50000;

    private static final String ABI_LIST_ARG = "--abi-list=";

    private static final String SOCKET_NAME_ARG = "--socket-name=";

    /**
     * 这个是SystemServer连接到 Zygote的一个Socket服务，会在启动SystemServer进程后关闭
     */
    private static LocalServerSocket sServerSocket;

    /**
     * Used to pre-load resources.  We hold a global reference on it so it
     * never gets destroyed.
     */
    private static Resources mResources;

    /**
     * The path of a file that contains classes to preload.
     */
    private static final String PRELOADED_CLASSES = "/system/etc/preloaded-classes";

    /**
     * Controls whether we should preload resources during zygote init.
     */
    private static final boolean PRELOAD_RESOURCES = true;

    /**
     * Registers a server socket for zygote command connections
     * <p>
     * 注册一个Socket的服务用于跨进程连接到 Zygote
     *
     * @throws RuntimeException when open fails
     */
    private static void registerZygoteSocket(String socketName) {
        if (sServerSocket == null) {
            int fileDesc;
            // 拼接Socket的名称，即：ANDROID_SOCKET_zygote
            final String fullSocketName = ANDROID_SOCKET_PREFIX + socketName;
            try {
                // 得到 Socket的环境变量的值
                String env = System.getenv(fullSocketName);
                // 将 Socket环境变量的值转换成文件描述符的参数
                fileDesc = Integer.parseInt(env);
            } catch (RuntimeException ex) {
                throw new RuntimeException(fullSocketName + " unset or invalid", ex);
            }

            try {
                // 创建文件描述符
                FileDescriptor fd = new FileDescriptor();
                fd.setInt$(fileDesc);
                // 创建服务器端的Socket
                sServerSocket = new LocalServerSocket(fd);
            } catch (IOException ex) {
                throw new RuntimeException(
                        "Error binding to local socket '" + fileDesc + "'", ex);
            }
        }
    }

    /**
     * Waits for and accepts a single command connection. Throws
     * RuntimeException on failure.
     */
    private static ZygoteConnection acceptCommandPeer(String abiList) {
        try {
            return new ZygoteConnection(sServerSocket.accept(), abiList);
        } catch (IOException ex) {
            throw new RuntimeException(
                    "IOException during accept()", ex);
        }
    }

    /**
     * Close and clean up zygote sockets. Called on shutdown and on the
     * child's exit path.
     */
    static void closeServerSocket() {
        try {
            if (sServerSocket != null) {
                FileDescriptor fd = sServerSocket.getFileDescriptor();
                sServerSocket.close();
                if (fd != null) {
                    Os.close(fd);
                }
            }
        } catch (IOException ex) {
            Log.e(TAG, "Zygote:  error closing sockets", ex);
        } catch (ErrnoException ex) {
            Log.e(TAG, "Zygote:  error closing descriptor", ex);
        }

        sServerSocket = null;
    }

    /**
     * Return the server socket's underlying file descriptor, so that
     * ZygoteConnection can pass it to the native code for proper
     * closure after a child process is forked off.
     */

    static FileDescriptor getServerSocketFileDescriptor() {
        return sServerSocket.getFileDescriptor();
    }

    private static final int UNPRIVILEGED_UID = 9999;
    private static final int UNPRIVILEGED_GID = 9999;

    private static final int ROOT_UID = 0;
    private static final int ROOT_GID = 0;

    /**
     * 初始化 Android的一些重要资源
     */
    static void preload() {
        Log.d(TAG, "begin preload");
        // 1、初始化Zygote中需要的class类
        preloadClasses();
        // 2、初始化系统资源
        preloadResources();
        // 3、初始化OpenGL
        preloadOpenGL();
        // 4、用于初始化系统libraries
        preloadSharedLibraries();
        // 5、用于初始化文字资源
        preloadTextResources();
        // Ask the WebViewFactory to do any initialization that must run in the zygote process,
        // for memory sharing purposes.
        WebViewFactory.prepareWebViewInZygote();
        Log.d(TAG, "end preload");
    }

    private static void preloadSharedLibraries() {
        Log.i(TAG, "Preloading shared libraries...");
        System.loadLibrary("android");
        System.loadLibrary("compiler_rt");
        System.loadLibrary("jnigraphics");
    }

    private static void preloadOpenGL() {
        if (!SystemProperties.getBoolean(PROPERTY_DISABLE_OPENGL_PRELOADING, false)) {
            EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        }
    }

    private static void preloadTextResources() {
        Hyphenator.init();
    }

    /**
     * Performs Zygote process initialization. Loads and initializes
     * commonly used classes.
     * <p>
     * Most classes only cause a few hundred bytes to be allocated, but
     * a few will allocate a dozen Kbytes (in one case, 500+K).
     */
    private static void preloadClasses() {
        final VMRuntime runtime = VMRuntime.getRuntime();

        InputStream is;
        try {
            is = new FileInputStream(PRELOADED_CLASSES);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Couldn't find " + PRELOADED_CLASSES + ".");
            return;
        }

        Log.i(TAG, "Preloading classes...");
        long startTime = SystemClock.uptimeMillis();

        // Drop root perms while running static initializers.
        final int reuid = Os.getuid();
        final int regid = Os.getgid();

        // We need to drop root perms only if we're already root. In the case of "wrapped"
        // processes (see WrapperInit), this function is called from an unprivileged uid
        // and gid.
        boolean droppedPriviliges = false;
        if (reuid == ROOT_UID && regid == ROOT_GID) {
            try {
                Os.setregid(ROOT_GID, UNPRIVILEGED_GID);
                Os.setreuid(ROOT_UID, UNPRIVILEGED_UID);
            } catch (ErrnoException ex) {
                throw new RuntimeException("Failed to drop root", ex);
            }

            droppedPriviliges = true;
        }

        // Alter the target heap utilization.  With explicit GCs this
        // is not likely to have any effect.
        float defaultUtilization = runtime.getTargetHeapUtilization();
        runtime.setTargetHeapUtilization(0.8f);

        try {
            BufferedReader br
                    = new BufferedReader(new InputStreamReader(is), 256);

            int count = 0;
            String line;
            while ((line = br.readLine()) != null) {
                // Skip comments and blank lines.
                line = line.trim();
                if (line.startsWith("#") || line.equals("")) {
                    continue;
                }

                try {
                    if (false) {
                        Log.v(TAG, "Preloading " + line + "...");
                    }
                    // Load and explicitly initialize the given class. Use
                    // Class.forName(String, boolean, ClassLoader) to avoid repeated stack lookups
                    // (to derive the caller's class-loader). Use true to force initialization, and
                    // null for the boot classpath class-loader (could as well cache the
                    // class-loader of this class in a variable).
                    Class.forName(line, true, null);
                    count++;
                } catch (ClassNotFoundException e) {
                    Log.w(TAG, "Class not found for preloading: " + line);
                } catch (UnsatisfiedLinkError e) {
                    Log.w(TAG, "Problem preloading " + line + ": " + e);
                } catch (Throwable t) {
                    Log.e(TAG, "Error preloading " + line + ".", t);
                    if (t instanceof Error) {
                        throw (Error) t;
                    }
                    if (t instanceof RuntimeException) {
                        throw (RuntimeException) t;
                    }
                    throw new RuntimeException(t);
                }
            }

            Log.i(TAG, "...preloaded " + count + " classes in "
                    + (SystemClock.uptimeMillis() - startTime) + "ms.");
        } catch (IOException e) {
            Log.e(TAG, "Error reading " + PRELOADED_CLASSES + ".", e);
        } finally {
            IoUtils.closeQuietly(is);
            // Restore default.
            runtime.setTargetHeapUtilization(defaultUtilization);

            // Fill in dex caches with classes, fields, and methods brought in by preloading.
            runtime.preloadDexCaches();

            // Bring back root. We'll need it later if we're in the zygote.
            if (droppedPriviliges) {
                try {
                    Os.setreuid(ROOT_UID, ROOT_UID);
                    Os.setregid(ROOT_GID, ROOT_GID);
                } catch (ErrnoException ex) {
                    throw new RuntimeException("Failed to restore root", ex);
                }
            }
        }
    }

    /**
     * Load in commonly used resources, so they can be shared across
     * processes.
     * <p>
     * These tend to be a few Kbytes, but are frequently in the 20-40K
     * range, and occasionally even larger.
     */
    private static void preloadResources() {
        final VMRuntime runtime = VMRuntime.getRuntime();

        try {
            mResources = Resources.getSystem();
            mResources.startPreloading();
            if (PRELOAD_RESOURCES) {
                Log.i(TAG, "Preloading resources...");

                long startTime = SystemClock.uptimeMillis();
                TypedArray ar = mResources.obtainTypedArray(
                        com.android.internal.R.array.preloaded_drawables);
                int N = preloadDrawables(runtime, ar);
                ar.recycle();
                Log.i(TAG, "...preloaded " + N + " resources in "
                        + (SystemClock.uptimeMillis() - startTime) + "ms.");

                startTime = SystemClock.uptimeMillis();
                ar = mResources.obtainTypedArray(
                        com.android.internal.R.array.preloaded_color_state_lists);
                N = preloadColorStateLists(runtime, ar);
                ar.recycle();
                Log.i(TAG, "...preloaded " + N + " resources in "
                        + (SystemClock.uptimeMillis() - startTime) + "ms.");
            }
            mResources.finishPreloading();
        } catch (RuntimeException e) {
            Log.w(TAG, "Failure preloading resources", e);
        }
    }

    private static int preloadColorStateLists(VMRuntime runtime, TypedArray ar) {
        int N = ar.length();
        for (int i = 0; i < N; i++) {
            int id = ar.getResourceId(i, 0);
            if (false) {
                Log.v(TAG, "Preloading resource #" + Integer.toHexString(id));
            }
            if (id != 0) {
                if (mResources.getColorStateList(id, null) == null) {
                    throw new IllegalArgumentException(
                            "Unable to find preloaded color resource #0x"
                                    + Integer.toHexString(id)
                                    + " (" + ar.getString(i) + ")");
                }
            }
        }
        return N;
    }


    private static int preloadDrawables(VMRuntime runtime, TypedArray ar) {
        int N = ar.length();
        for (int i = 0; i < N; i++) {
            int id = ar.getResourceId(i, 0);
            if (false) {
                Log.v(TAG, "Preloading resource #" + Integer.toHexString(id));
            }
            if (id != 0) {
                if (mResources.getDrawable(id, null) == null) {
                    throw new IllegalArgumentException(
                            "Unable to find preloaded drawable resource #0x"
                                    + Integer.toHexString(id)
                                    + " (" + ar.getString(i) + ")");
                }
            }
        }
        return N;
    }

    /**
     * Runs several special GCs to try to clean up a few generations of
     * softly- and final-reachable objects, along with any other garbage.
     * This is only useful just before a fork().
     */
    /*package*/
    static void gcAndFinalize() {
        final VMRuntime runtime = VMRuntime.getRuntime();

        /* runFinalizationSync() lets finalizers be called in Zygote,
         * which doesn't have a HeapWorker thread.
         */
        System.gc();
        runtime.runFinalizationSync();
        System.gc();
    }

    /**
     * Finish remaining work for the newly forked system server process.
     */
    private static void handleSystemServerProcess(
            ZygoteConnection.Arguments parsedArgs)
            throws ZygoteInit.MethodAndArgsCaller {

        closeServerSocket();

        // set umask to 0077 so new files and directories will default to owner-only permissions.
        Os.umask(S_IRWXG | S_IRWXO);

        if (parsedArgs.niceName != null) {
            Process.setArgV0(parsedArgs.niceName);
        }

        final String systemServerClasspath = Os.getenv("SYSTEMSERVERCLASSPATH");
        if (systemServerClasspath != null) {
            performSystemServerDexOpt(systemServerClasspath);
        }

        if (parsedArgs.invokeWith != null) {
            String[] args = parsedArgs.remainingArgs;
            // If we have a non-null system server class path, we'll have to duplicate the
            // existing arguments and append the classpath to it. ART will handle the classpath
            // correctly when we exec a new process.
            if (systemServerClasspath != null) {
                String[] amendedArgs = new String[args.length + 2];
                amendedArgs[0] = "-cp";
                amendedArgs[1] = systemServerClasspath;
                System.arraycopy(parsedArgs.remainingArgs, 0, amendedArgs, 2, parsedArgs.remainingArgs.length);
            }

            WrapperInit.execApplication(parsedArgs.invokeWith,
                    parsedArgs.niceName, parsedArgs.targetSdkVersion,
                    VMRuntime.getCurrentInstructionSet(), null, args);
        } else {
            ClassLoader cl = null;
            if (systemServerClasspath != null) {
                cl = new PathClassLoader(systemServerClasspath, ClassLoader.getSystemClassLoader());
                Thread.currentThread().setContextClassLoader(cl);
            }

            /*
             * Pass the remaining arguments to SystemServer.
             */
            RuntimeInit.zygoteInit(parsedArgs.targetSdkVersion, parsedArgs.remainingArgs, cl);
        }

        /* should never reach here */
    }

    /**
     * Performs dex-opt on the elements of {@code classPath}, if needed. We
     * choose the instruction set of the current runtime.
     */
    private static void performSystemServerDexOpt(String classPath) {
        final String[] classPathElements = classPath.split(":");
        final InstallerConnection installer = new InstallerConnection();
        installer.waitForConnection();
        final String instructionSet = VMRuntime.getRuntime().vmInstructionSet();

        try {
            for (String classPathElement : classPathElements) {
                final int dexoptNeeded = DexFile.getDexOptNeeded(
                        classPathElement, "*", instructionSet, false /* defer */);
                if (dexoptNeeded != DexFile.NO_DEXOPT_NEEDED) {
                    installer.dexopt(classPathElement, Process.SYSTEM_UID, false,
                            instructionSet, dexoptNeeded);
                }
            }
        } catch (IOException ioe) {
            throw new RuntimeException("Error starting system_server", ioe);
        } finally {
            installer.disconnect();
        }
    }

    /**
     * Prepare the arguments and fork for the system server process.
     * 准备好参数后就开始 fork SystemServer进程，由此可以知道 SystemServer会在 Zygote进程启动的时候
     * 由 Zygote进程 fork出来的
     */
    private static boolean startSystemServer(String abiList, String socketName)
            throws MethodAndArgsCaller, RuntimeException {
        long capabilities = posixCapabilitiesAsBits(
                OsConstants.CAP_BLOCK_SUSPEND,
                OsConstants.CAP_KILL,
                OsConstants.CAP_NET_ADMIN,
                OsConstants.CAP_NET_BIND_SERVICE,
                OsConstants.CAP_NET_BROADCAST,
                OsConstants.CAP_NET_RAW,
                OsConstants.CAP_SYS_MODULE,
                OsConstants.CAP_SYS_NICE,
                OsConstants.CAP_SYS_RESOURCE,
                OsConstants.CAP_SYS_TIME,
                OsConstants.CAP_SYS_TTY_CONFIG
        );
        /* Hardcoded command line to start the system server */
        // 创建args数组，这个数组用来保存启动SystemServer的启动参数
        String args[] = {
                "--setuid=1000",
                "--setgid=1000",
                "--setgroups=1001,1002,1003,1004,1005,1006,1007,1008,1009,1010,1018,1021,1032,3001,3002,3003,3006,3007",
                "--capabilities=" + capabilities + "," + capabilities,
                "--nice-name=system_server",
                "--runtime-args",
                "com.android.server.SystemServer",
        };
        ZygoteConnection.Arguments parsedArgs = null;

        int pid;

        try {
            parsedArgs = new ZygoteConnection.Arguments(args);
            ZygoteConnection.applyDebuggerSystemProperty(parsedArgs);
            ZygoteConnection.applyInvokeWithSystemProperty(parsedArgs);

            /* Request to fork the system server process */
            // 开始 fork SystemServer进程
            pid = Zygote.forkSystemServer(
                    parsedArgs.uid, parsedArgs.gid,
                    parsedArgs.gids,
                    parsedArgs.debugFlags,
                    null,
                    parsedArgs.permittedCapabilities,
                    parsedArgs.effectiveCapabilities);
        } catch (IllegalArgumentException ex) {
            throw new RuntimeException(ex);
        }

        /* For child process */
        // 如果创建进程成功，下面的逻辑将运行在子进程中，即：SystemServer
        if (pid == 0) {
            if (hasSecondZygote(abiList)) {
                waitForSecondaryZygote(socketName);
            }
            // 处理SystemServer进程
            handleSystemServerProcess(parsedArgs);
        }

        return true;
    }

    /**
     * Gets the bit array representation of the provided list of POSIX capabilities.
     */
    private static long posixCapabilitiesAsBits(int... capabilities) {
        long result = 0;
        for (int capability : capabilities) {
            if ((capability < 0) || (capability > OsConstants.CAP_LAST_CAP)) {
                throw new IllegalArgumentException(String.valueOf(capability));
            }
            result |= (1L << capability);
        }
        return result;
    }

    /**
     * Linux的 init进程会调用这个方法从而启动 Zygote进程
     *
     * @param argv 这个数组中保存了：是否启动SystemServer、abi列表、 zygote注册的Socket服务的名称
     */
    public static void main(String argv[]) {
        try {
            // 1、设置DDMS可用
            RuntimeInit.enableDdms();
            // Start profiling the zygote initialization.
            SamplingProfilerIntegration.start();

            // 是否启动 SystemServer进程
            boolean startSystemServer = false;
            // 这个是 Zygote的Socket服务的名称
            String socketName = "zygote";
            String abiList = null;

            // 2、遍历 argv数组，从中获取需要的数据
            for (int i = 1; i < argv.length; i++) {
                if ("start-system-server".equals(argv[i])) {
                    startSystemServer = true;
                } else if (argv[i].startsWith(ABI_LIST_ARG)) {
                    abiList = argv[i].substring(ABI_LIST_ARG.length());
                } else if (argv[i].startsWith(SOCKET_NAME_ARG)) {
                    socketName = argv[i].substring(SOCKET_NAME_ARG.length());
                } else {
                    throw new RuntimeException("Unknown command line argument: " + argv[i]);
                }
            }

            // 如果没有ABI就抛异常
            if (abiList == null) {
                throw new RuntimeException("No ABI list supplied.");
            }

            // 3、为Zygote注册一个Socket服务，这个Socket是给SystemServer连接到Zygote，同时会在
            // SystemServer进程创建后关闭
            // android系统中进程之间通讯的方式是Binder，但是有一个例外是 SystemService进程与 Zygote
            // 进程之间是通过Socket的方式进行通讯的
            registerZygoteSocket(socketName);
            EventLog.writeEvent(LOG_BOOT_PROGRESS_PRELOAD_START,
                    SystemClock.uptimeMillis());

            // 4、这个方法会初始化Android的一些重要资源
            preload();

            EventLog.writeEvent(LOG_BOOT_PROGRESS_PRELOAD_END,
                    SystemClock.uptimeMillis());

            // Finish profiling the zygote initialization.
            SamplingProfilerIntegration.writeZygoteSnapshot();

            // Do an initial gc to clean up after startup
            gcAndFinalize();

            // Disable tracing so that forked processes do not inherit stale tracing tags from
            // Zygote.
            Trace.setTracingEnabled(false);

            // 5、如果 argv数组中有个 start-system-server 这一段字符串就会启动 SystemServer
            if (startSystemServer) {
                // 启动 SystemServer进程
                startSystemServer(abiList, socketName);
            }

            Log.i(TAG, "Accepting command socket connections");
            runSelectLoop(abiList);

            // 6、最后关闭 Zygote的 Socket的服务
            closeServerSocket();
        } catch (MethodAndArgsCaller caller) {
            caller.run();
        } catch (RuntimeException ex) {
            Log.e(TAG, "Zygote died with exception", ex);
            closeServerSocket();
            throw ex;
        }
    }

    /**
     * Return {@code true} if this device configuration has another zygote.
     * <p>
     * We determine this by comparing the device ABI list with this zygotes
     * list. If this zygote supports all ABIs this device supports, there won't
     * be another zygote.
     */
    private static boolean hasSecondZygote(String abiList) {
        return !SystemProperties.get("ro.product.cpu.abilist").equals(abiList);
    }

    private static void waitForSecondaryZygote(String socketName) {
        String otherZygoteName = Process.ZYGOTE_SOCKET.equals(socketName) ?
                Process.SECONDARY_ZYGOTE_SOCKET : Process.ZYGOTE_SOCKET;
        while (true) {
            try {
                final Process.ZygoteState zs = Process.ZygoteState.connect(otherZygoteName);
                zs.close();
                break;
            } catch (IOException ioe) {
                Log.w(TAG, "Got error connecting to zygote, retrying. msg= " + ioe.getMessage());
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
            }
        }
    }

    /**
     * Runs the zygote process's select loop. Accepts new connections as
     * they happen, and reads commands from connections one spawn-request's
     * worth at a time.
     *
     * @throws MethodAndArgsCaller in a child process when a main() should
     *                             be executed.
     */
    private static void runSelectLoop(String abiList) throws MethodAndArgsCaller {
        ArrayList<FileDescriptor> fds = new ArrayList<FileDescriptor>();
        ArrayList<ZygoteConnection> peers = new ArrayList<ZygoteConnection>();

        fds.add(sServerSocket.getFileDescriptor());
        peers.add(null);

        while (true) {
            StructPollfd[] pollFds = new StructPollfd[fds.size()];
            for (int i = 0; i < pollFds.length; ++i) {
                pollFds[i] = new StructPollfd();
                pollFds[i].fd = fds.get(i);
                pollFds[i].events = (short) POLLIN;
            }
            try {
                // 接收SystemServer通过Socket发送过来的消息
                Os.poll(pollFds, -1);
            } catch (ErrnoException ex) {
                throw new RuntimeException("poll failed", ex);
            }
            for (int i = pollFds.length - 1; i >= 0; --i) {
                if ((pollFds[i].revents & POLLIN) == 0) {
                    continue;
                }
                if (i == 0) {
                    ZygoteConnection newPeer = acceptCommandPeer(abiList);
                    peers.add(newPeer);
                    fds.add(newPeer.getFileDesciptor());
                } else {
                    // 从Socket读取启动命令；如果成功，则抛出 在父进程中，该子进程中抛出MethodAndArgsCaller异常，
                    // 失败时，不会生成子级，消息会打印到日志和stderr。返回一个布尔状态值，该值指示是否读取到Socket命令的文件结尾。
                    boolean done = peers.get(i).runOnce();
                    if (done) {
                        peers.remove(i);
                        fds.remove(i);
                    }
                }
            }
        }
    }

    /**
     * Class not instantiable.
     */
    private ZygoteInit() {
    }

    /**
     * Helper exception class which holds a method and arguments and
     * can call them. This is used as part of a trampoline to get rid of
     * the initial process setup stack frames.
     */
    public static class MethodAndArgsCaller extends Exception
            implements Runnable {
        /**
         * method to call
         */
        private final Method mMethod;

        /**
         * argument array
         */
        private final String[] mArgs;

        public MethodAndArgsCaller(Method method, String[] args) {
            mMethod = method;
            mArgs = args;
        }

        public void run() {
            try {
                mMethod.invoke(null, new Object[]{mArgs});
            } catch (IllegalAccessException ex) {
                throw new RuntimeException(ex);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                } else if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw new RuntimeException(ex);
            }
        }
    }
}
