/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.netbeans.modules.debugger.jpda.actions;

import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.Event;
import com.sun.jdi.request.EventRequest;
import java.io.File;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.logging.Level;
import org.netbeans.api.extexecution.ExecutionDescriptor;
import java.util.logging.Logger;
import org.netbeans.api.debugger.ActionsManager;
import org.netbeans.spi.debugger.ContextProvider;
import org.netbeans.api.debugger.Session;
import org.netbeans.api.debugger.jpda.AbstractDICookie;
import org.netbeans.api.debugger.jpda.JPDADebugger;
import org.netbeans.api.debugger.jpda.ListeningDICookie;
import org.netbeans.modules.debugger.jpda.JPDADebuggerImpl;
import org.netbeans.modules.debugger.jpda.jdi.VirtualMachineWrapper;
import org.netbeans.modules.debugger.jpda.util.Operator;
import org.netbeans.modules.debugger.jpda.util.Executor;
import org.netbeans.spi.debugger.ActionsProvider;
import org.netbeans.spi.debugger.ActionsProviderListener;
import org.netbeans.api.extexecution.ExecutionService;
import org.netbeans.spi.project.ActionProvider;
import org.openide.filesystems.FileObject;
import org.openide.util.Cancellable;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import org.netbeans.modules.java.openjdk.jtreg.SingleJavaFileActionProvider;

/**
*
* @author   Jan Jancura
*/
@ActionsProvider.Registration(path="netbeans-JPDASession", actions={"start"})
public class StartActionProvider extends ActionsProvider implements Cancellable {
//    private static transient String []        stopMethodNames = 
//        {"main", "start", "init", "<init>"}; // NOI18N

    private static final Logger logger = Logger.getLogger(StartActionProvider.class.getName());
    private static int jdiTrace;
    static { 
        if (System.getProperty ("netbeans.debugger.jditrace") != null) {
            try {
                jdiTrace = Integer.parseInt (
                    System.getProperty ("netbeans.debugger.jditrace")
                );
            } catch (NumberFormatException ex) {
                jdiTrace = VirtualMachine.TRACE_NONE;
            }
        } else
            jdiTrace = VirtualMachine.TRACE_NONE;
    }

    private final JPDADebuggerImpl debuggerImpl;
    private final ContextProvider lookupProvider;
    private Thread startingThread;
    private final Object startingThreadLock = new Object();
    
    
    public StartActionProvider (ContextProvider lookupProvider) {
        debuggerImpl = (JPDADebuggerImpl) lookupProvider.lookupFirst
            (null, JPDADebugger.class);
        this.lookupProvider = lookupProvider;
    }
    
    @Override
    public Set getActions () {
        return Collections.singleton (ActionsManager.ACTION_START);
    }
    
    @Override
    public void doAction (Object action) {
        logger.fine("S StartActionProvider.doAction ()");
        JPDADebuggerImpl debugger = (JPDADebuggerImpl) lookupProvider.
            lookupFirst (null, JPDADebugger.class);
        if ( debugger != null && 
             debugger.getVirtualMachine () != null
        ) return;
                
        logger.fine("S StartActionProvider." +
                    "doAction () setStarting");
        debuggerImpl.setStarting ();
        final AbstractDICookie cookie = lookupProvider.lookupFirst(null, AbstractDICookie.class);
        doStartDebugger(cookie);
        logger.fine("S StartActionProvider." +
                    "doAction () end");
    }
    
    @Override
    public void postAction(Object action, final Runnable actionPerformedNotifier) {
        logger.fine("S StartActionProvider.postAction ()");
        JPDADebuggerImpl debugger = (JPDADebuggerImpl) lookupProvider.
            lookupFirst (null, JPDADebugger.class);
        if ( debugger != null && 
             debugger.getVirtualMachine () != null
        ) {
            actionPerformedNotifier.run();
            return;
        }
        
        
        final AbstractDICookie cookie = lookupProvider.lookupFirst(null, AbstractDICookie.class);
        
        logger.fine("S StartActionProvider." +
                    "postAction () setStarting");

        debuggerImpl.setStarting ();  // JS
        
        logger.fine("S StartActionProvider." +
                    "postAction () setStarting end");
        
        debuggerImpl.getRequestProcessor().post(new Runnable() {
            @Override
            public void run() {
                //debuggerImpl.setStartingThread(Thread.currentThread());
                synchronized (startingThreadLock) {
                    startingThread = Thread.currentThread();
                }
                try {
                    doStartDebugger(cookie);
                //} catch (InterruptedException iex) {
                    // We've interrupted ourselves
                } finally {
                    synchronized (startingThreadLock) {
                        startingThread = null;
                        startingThreadLock.notifyAll();
                    }
                    //debuggerImpl.unsetStartingThread();
                    actionPerformedNotifier.run();
                }
                
            }
        });
        
    }
    
    public RunProcess invokeActionHelper (int port) {
        List<String> commandsList = new ArrayList<>();
        if (org.openide.util.Utilities.isUnix()) {
            commandsList.add("bash");
            commandsList.add("-c");
        }
        ActionProvider dap = null;
        for (ActionProvider ap : Lookup.getDefault().lookupAll(ActionProvider.class)) {
            if (Arrays.asList(ap.getSupportedActions()).contains("debug.single") && ap.isActionEnabled("debug.single", null)) {
                dap = ap;
                break;
            }
        }
        if (dap instanceof SingleJavaFileActionProvider) {
            SingleJavaFileActionProvider debugActionProvider = (SingleJavaFileActionProvider) dap;
            FileObject debugFile = debugActionProvider.getFileObject();
            File javaPathFile = new File(new File(new File(System.getProperty("java.home")), "bin"), "java");
            String javaPath = "\"" + javaPathFile.getAbsolutePath() + "\"";
            commandsList.add(javaPath + " -agentlib:jdwp=transport=dt_socket,suspend=y,server=n,address=localhost:" + port + " -classpath \"" + debugFile.getParent().getPath() + "\" " + debugFile.getName());
            return new RunProcess(commandsList);
        }
        return null;
    }
    
    private void doStartDebugger(AbstractDICookie cookie) {
        logger.fine("S StartActionProvider." +
                    "doStartDebugger");
        Throwable throwable = null;
        try {
            debuggerImpl.setAttaching(cookie);
            
            if (cookie instanceof ListeningDICookie) {
                ListeningDICookie ldc = (ListeningDICookie) cookie;
                ExecutionDescriptor descriptor = new ExecutionDescriptor().controllable(true).frontWindow(true).
                        preExecution(null).postExecution(null);
                RunProcess process = invokeActionHelper(ldc.getPortNumber());
                ExecutionService exeService = ExecutionService.newService(
                        process,
                        descriptor, "Debugging Java File");
                Future<Integer> exitCode = exeService.run();
            }
            
            VirtualMachine virtualMachine = cookie.getVirtualMachine ();
            debuggerImpl.setAttaching(null);
            VirtualMachineWrapper.setDebugTraceMode (virtualMachine, jdiTrace);

            final boolean[] startLock = { false };
            Operator o = createOperator (virtualMachine, startLock);
            synchronized (startLock) {
                logger.fine("S StartActionProvider.doAction () - " +
                            "starting operator thread");
                o.start ();
                if (cookie instanceof ListeningDICookie){
                    // need to wait longer for debugger in certain cases (RoboVM)
                    long now = System.currentTimeMillis();
                    startLock.wait(60000);
                    if (!startLock[0]) {
                        long ms = System.currentTimeMillis() - now;
                        logger.log(Level.WARNING, "start notification not obtained in {0} ms", ms);
                    }
                }
            }
       
            debuggerImpl.setRunning (
                virtualMachine,
                o
            );
          
            // PATCH #46295 JSP breakpoint isn't reached during 
            // second debugging
//            if (cookie instanceof AttachingDICookie) {
//                synchronized (debuggerImpl.LOCK) {
//                    virtualMachine.resume ();
//                }
//            }
            // PATCHEND Hanz

            logger.fine("S StartActionProvider." +
                        "doStartDebugger end: success");
//         
        } catch (InterruptedException iex) {
            throwable = iex;
        } catch (IOException ioex) {
            throwable = ioex;
        } catch (Exception ex) {
            throwable = ex;
            // Notify! Otherwise bugs in the code can not be located!!!
            Exceptions.printStackTrace(ex);
        } catch (ThreadDeath td) {
            throw td;
        } catch (Error err) {
            throwable = err;
            Exceptions.printStackTrace(err);
        }
        if (throwable != null) {
            logger.log(Level.FINE,"S StartActionProvider.doAction ().thread end: threw {0}",
                       throwable);
            debuggerImpl.setException (throwable);
            // kill the session that did not start properly
            final Session session = lookupProvider.lookupFirst(null, Session.class);
            debuggerImpl.getRequestProcessor().post(new Runnable() {
                @Override
                public void run() {
                    // Kill it in a separate thread so that the startup sequence can be finished.
                    session.kill();
                }
            });
        }
    }

    @Override
    public boolean isEnabled (Object action) {
        return true;
    }

    @Override
    public void addActionsProviderListener (ActionsProviderListener l) {}
    @Override
    public void removeActionsProviderListener (ActionsProviderListener l) {}
    
    private Operator createOperator (
        VirtualMachine virtualMachine,
        final boolean [] startLock
    ) {
        return new Operator (
            virtualMachine,
            debuggerImpl,
            new Executor () {
                @Override
                public boolean exec(Event event) {
                    synchronized(startLock) {
                        startLock[0] = true;
                        startLock.notify();
                    }
                    return false;
                }

                @Override
                public void removed(EventRequest eventRequest) {}
                
            },
            new Runnable () {
                @Override
                public void run () {
                    debuggerImpl.finish();
                }
            },
            debuggerImpl.accessLock
        );
    }

    @Override
    public boolean cancel() {
        synchronized (startingThreadLock) {
            logger.log(Level.FINE, "StartActionProvider.cancel(): startingThread = {0}",
                       startingThread);
            for (int i = 0; i < 10; i++) { // Repeat several times, it can be called too early
                if (startingThread != null) {
                    logger.log(Level.FINE, "Interrupting {0}", startingThread);
                    startingThread.interrupt();
                    boolean cancelInterrupted = false;
                    try {
                        startingThreadLock.wait(500);
                    } catch (InterruptedException iex) {
                        cancelInterrupted = true;
                    }
                    AbstractDICookie cookie = lookupProvider.lookupFirst(null, AbstractDICookie.class);
                    logger.log(Level.FINE, "Listening cookie = {0}, is listening = {1}", new Object[]{cookie, cookie instanceof ListeningDICookie});
                    if (cookie instanceof ListeningDICookie) {
                        ListeningDICookie lc = (ListeningDICookie) cookie;
                        try {
                            lc.getListeningConnector().stopListening(lc.getArgs());
                        } catch (IOException ex) {
                        } catch (IllegalConnectorArgumentsException ex) {
                        }
                    }
                    if (cancelInterrupted) {
                        return false;
                    }
                } else {
                    return true;
                }
            }
            return startingThread == null;
        }
    }
}
class RunProcess implements Callable<Process> {
    
    private static final Logger LOG = Logger.getLogger(RunProcess.class.getName());

    private final String dirPath;
    private final List<String> commandsList;
    private InputStream is;
    private Process p;

    public RunProcess(String command, String dirPath) {
        this.dirPath = dirPath;
        commandsList = new ArrayList<>();
        commandsList.add(command);
        setupProcess();
    }

    public RunProcess(String command) {
        commandsList = new ArrayList<>();
        commandsList.add(command);
        this.dirPath = System.getProperty("user.home");
        setupProcess();
    }

    public RunProcess(List<String> commandsList) {
        this.commandsList = commandsList;
        this.dirPath = System.getProperty("user.home");
        setupProcess();
    }

    public void setupProcess() {
        try {
            ProcessBuilder runFileProcessBuilder = new ProcessBuilder(commandsList);
            runFileProcessBuilder.directory(new File(dirPath));
            runFileProcessBuilder.redirectErrorStream(true);
            p = runFileProcessBuilder.start();
            is = p.getInputStream();
        } catch (IOException ex) {
            LOG.log(
                    Level.WARNING,
                    "Could not get InputStream of Run Process"); //NOI18N
        }
    }

    public InputStream getInputStream() {
        return is;
    }

    @Override
    public Process call() throws Exception {
        return p;
    }
    
}