package dev.carbons.carpet_dap.adapter;

import carpet.script.Module;
import dev.carbons.carpet_dap.debug.CarpetDebugHost;
import org.eclipse.lsp4j.debug.Thread;
import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.carbons.carpet_dap.CarpetDebugMod.LOGGER;
import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 *
 */
public class CarpetDebugAdapter implements IDebugProtocolServer {
    private final BlockingQueue<Record> sender;
    private final Map<Source, SourceBreakpoint[]> breakpoints = new HashMap<>();
    private final CarpetDebugHost debugHost;
    // When true the debugger client sends lines and columns starting from 1 instead of 0
    private boolean linesStartAt1 = false;
    private boolean columnsStartAt1 = false;
    private AtomicInteger sourceReferenceNext = new AtomicInteger(1);
    private Source source;

    public CarpetDebugAdapter(BlockingQueue<Record> sender, CarpetDebugHost debugHost) {
        this.sender = sender;
        this.debugHost = debugHost;
    }

    @Nullable
    private static String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof String string) return string;
        return null;
    }

    @Nullable
    private static Boolean getBoolean(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) return bool;
        return null;
    }

    public Map<Source, SourceBreakpoint[]> getBreakpoints() {
        return breakpoints;
    }

    public boolean getLinesStartAt1() {
        return linesStartAt1;
    }

    public boolean getColumnsStartAt1() {
        return columnsStartAt1;
    }

    @Override
    public CompletableFuture<SourceResponse> source(SourceArguments args) {
        //
        var sourceResponse = new SourceResponse();
        Integer sourceReference = args.getSource().getSourceReference();
        if (sourceReference != null && sourceReference != 0) {
            Module module = debugHost.getModule(sourceReference);
            if (module != null)
                sourceResponse.setContent(module.code());
        }
        return completedFuture(sourceResponse);
    }


    @Override
    public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
        LOGGER.info("[DAP]: initialize()");
        if (args.getLinesStartAt1()) linesStartAt1 = true;
        if (args.getColumnsStartAt1()) columnsStartAt1 = true;
        var capabilities = new Capabilities();
        // TODO: which capabilities do we support?
        return completedFuture(capabilities);
    }

    @Override
    public CompletableFuture<ThreadsResponse> threads() {
        LOGGER.info("[DAP]: threads()");
        var threads = new ThreadsResponse();
        var thread = new Thread();
        thread.setId(1);
        thread.setName("main");
        threads.setThreads(new Thread[]{thread});
        return completedFuture(threads);
    }

    @Override
    public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {
        LOGGER.info("[DAP]: configurationDone()");
        return completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> launch(Map<String, Object> args) {
        LOGGER.info("[DAP]: launch()");
        var type = getString(args, "type");
        var program = getString(args, "program");
        var noDebug = getBoolean(args, "noDebug");
        var trace = getBoolean(args, "trace");
        var stopOnEvent = getBoolean(args, "stopOnEvent");
        if (!Objects.equals(type, "scarpet")) {
            LOGGER.info("[DAP]: unknown launch type {}", type);
        }
        LOGGER.info("[DAP]: launch program {}", program);
        LOGGER.info("[DAP]: noDebug {}", noDebug);
        try {
            // Put makes sure to wait until the onTick handler picks up the launch event.
            sender.put(new LaunchParams(program, noDebug != null && noDebug, trace == null || trace, stopOnEvent != null && stopOnEvent));
        } catch (InterruptedException ex) {
            LOGGER.error("[DAP]: Had an oopsie: {}", ex.toString());
        }
        return completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> disconnect(DisconnectArguments args) {
        LOGGER.info("[DAP]: disconnect()");
        return completedFuture(null);
    }

    @Override
    public CompletableFuture<ContinueResponse> continue_(ContinueArguments args) {
        return IDebugProtocolServer.super.continue_(args);
    }

    @Override
    public CompletableFuture<Void> next(NextArguments args) {
        LOGGER.info("[DAP]: next()");
        try {
            sender.put(new NextParams());
        } catch (InterruptedException ex) {
            //
        }
        return completedFuture(null);
    }

//    @Override
//    public CompletableFuture<BreakpointLocationsResponse> breakpointLocations(BreakpointLocationsArguments args) {
//        return IDebugProtocolServer.super.breakpointLocations(args);
//    }

    @Override
    public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments args) {
        LOGGER.info("[DAP]: setBreakpoints()");
        LOGGER.info(args.toString());
        source = args.getSource();
        debugHost.setBreakpoints(args.getSource().getPath(), args.getBreakpoints(), linesStartAt1);
        var res = new SetBreakpointsResponse();
        return completedFuture(res);
    }

    @Override
    public CompletableFuture<SetExceptionBreakpointsResponse> setExceptionBreakpoints(SetExceptionBreakpointsArguments args) {
        return completedFuture(new SetExceptionBreakpointsResponse());
    }

    @Override
    public CompletableFuture<EvaluateResponse> evaluate(EvaluateArguments args) {
        return IDebugProtocolServer.super.evaluate(args);
    }

    @Override
    public CompletableFuture<StackTraceResponse> stackTrace(StackTraceArguments args) {
        StackFrame[] stackTrace = debugHost.getStackTrace(args.getThreadId(), linesStartAt1, columnsStartAt1);
        var res = new StackTraceResponse();
        res.setStackFrames(stackTrace);
        return completedFuture(res);
    }

    @Override
    public CompletableFuture<ScopesResponse> scopes(ScopesArguments args) {
        var scopes = new ScopesResponse();
        scopes.setScopes(debugHost.getScopes(args.getFrameId(), debugHost.getSuspendedState()));
        return completedFuture(scopes);
    }

    @Override
    public CompletableFuture<VariablesResponse> variables(VariablesArguments args) {
        var vars = new VariablesResponse();
        vars.setVariables(debugHost.getSuspendedState().get(args.getVariablesReference()));
        return completedFuture(vars);
    }
}
