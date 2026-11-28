/*
 * Drishti Risk · market and counterparty risk as engines.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.risk.market.engine;

import com.ash.engine.Control;
import com.ash.engine.Descriptor;
import com.ash.engine.Descriptors;
import com.ash.engine.Engine;
import com.ash.engine.EngineContext;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.EngineMeter;
import com.ash.engine.EngineStats;
import com.ash.engine.Health;
import com.ash.engine.Inputs;
import com.ash.engine.LifecycleState;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;
import com.ash.risk.kernel.linalg.Blas;
import com.ash.risk.kernel.vector.MultiSums;
import com.ash.risk.kernel.vector.ParallelSums;
import java.util.ArrayList;
import java.util.List;

/**
 * The market-risk engine of Drishti Risk (product {@code drishti-risk-market}): historical VaR and ES by hierarchy node and system basket
 * over supplied scenario PVs or P&amp;L vectors. A thin adapter over the moved market-risk code ({@code com.ash.risk.market.calc}): the book,
 * the rank rules, the currency conversion and the scope grammar are Drishti's own classes, unchanged, so the numbers are Drishti's.
 *
 * <p>Lifecycle (ENGINE_SPEC 7.1): {@link #init} reads the options; {@link #start} builds the kernel state (the deterministic summation
 * pool, the method choices, hierarchies and position rules); {@link #suspend} keeps every loaded day and refuses new work; {@link #resume}
 * is cheap; {@link #shutdown} is idempotent and releases the pool.
 */
public final class MarketRiskEngine implements Engine {

    static final String ID = "market-risk";

    private volatile EngineContext context;
    private volatile MarketOptions options;
    private volatile ParallelSums sums;
    private volatile Blas.Choice linearAlgebra;
    private volatile EngineMeter meter = EngineMeter.unstarted();
    private volatile LifecycleState lifecycle = LifecycleState.NEW;
    private volatile String failure;

    /** The jar's descriptor, read at the first call (ENGINE_SPEC 4.1: constant, at constant cost); every later call returns it. */
    private volatile Descriptor descriptor;

    @Override
    public Descriptor descriptor() {
        Descriptor d = descriptor;
        if (d == null) {
            d = Descriptors.of(MarketRiskEngine.class, ID);
            descriptor = d;
        }
        return d;
    }

    @Override
    public synchronized void init(EngineContext context) throws EngineException {
        if (lifecycle != LifecycleState.NEW) {
            throw new EngineException("ENG-5020", Kind.REFUSED, "init is called once, on a new engine; this one is " + lifecycle);
        }
        this.context = context;
        this.meter = new EngineMeter(context.clock());
        try {
            this.options = MarketOptions.read(context.options());
        } catch (IllegalArgumentException e) {
            lifecycle = LifecycleState.FAILED;
            failure = e.getMessage();
            throw new EngineException("ENG-1013", Kind.REFUSED, e.getMessage());
        }
        lifecycle = LifecycleState.INITIALISED;
    }

    @Override
    public synchronized void start() throws EngineException {
        if (lifecycle != LifecycleState.INITIALISED) {
            throw new EngineException("ENG-5020", Kind.REFUSED, "start follows init; this engine is " + lifecycle);
        }
        // the summation runs on the host's executor (ENGINE_SPEC 8.5 rule 3): one per engine instance, never a static one
        // the linear algebra, bound once per engine instance (ENGINE_SPEC 8.4: OpenBLAS from this JAR behind the guard, else Java)
        MarketOptions.LinearAlgebraChoice want = options.linearAlgebra();
        Blas.Choice la = Blas.choose(want.java(), context.workDir().resolve("native"));
        linearAlgebra = la;
        if (!want.java() && !la.status().isNative()) {
            context.log().warn("linear algebra: Java (" + la.status().reason() + "); the figures are the same, the many-scope sums slower");
        } else {
            context.log().info("linear algebra: " + (la.status().isNative() ? "OpenBLAS " + la.status().version() : "Java (chosen)"));
        }
        sums = new ParallelSums(context.executor(), options.threads(), MarketOptions.PARALLEL_ROWS, null,
                new MultiSums.Gemm(la.la(), want.gemmMinDensity(), want.gemmMinSets()));
        lifecycle = LifecycleState.RUNNING;
    }

    @Override
    public synchronized void suspend() throws EngineException {
        if (lifecycle != LifecycleState.RUNNING) {
            throw new EngineException("ENG-5020", Kind.REFUSED, "only a running engine is suspended; this one is " + lifecycle);
        }
        lifecycle = LifecycleState.SUSPENDED;                            // loaded days and the pool stay: resume is cheap
    }

    @Override
    public synchronized void resume() throws EngineException {
        if (lifecycle != LifecycleState.SUSPENDED) {
            throw new EngineException("ENG-5020", Kind.REFUSED, "only a suspended engine resumes; this one is " + lifecycle);
        }
        lifecycle = LifecycleState.RUNNING;
    }

    @Override
    public Session session(Snapshot snapshot, Inputs inputs, Control control) throws EngineException {
        running();
        MarketDay day = MarketDay.read(snapshot, inputs, control);
        meter.sessionOpened();
        return new MarketSession(this, snapshot, day);
    }

    /** Refuses work unless running: suspended in its own words (ENG-5023), any other state as a call out of order. */
    void running() throws EngineException {
        LifecycleState s = lifecycle;
        if (s == LifecycleState.SUSPENDED) {
            throw new EngineException("ENG-5023", Kind.REFUSED, "the market-risk engine is suspended");
        }
        if (s != LifecycleState.RUNNING) {
            throw new EngineException("ENG-5020", Kind.REFUSED, "the market-risk engine is " + s + ", not running");
        }
    }

    EngineContext context() {
        return context;
    }

    MarketOptions options() {
        return options;
    }

    ParallelSums sums() {
        return sums;
    }

    EngineMeter meter() {
        return meter;
    }

    @Override
    public List<String> notes() {
        List<String> out = new ArrayList<>();
        MarketOptions o = options;
        if (o != null) {
            Blas.Choice la = linearAlgebra;
            out.add("linear algebra: " + (la == null ? "not bound yet" : la.status().isNative() ? "OpenBLAS" + (la.status().version() == null ? ""
                    : " " + la.status().version()) : "Java (" + la.status().reason() + ")") + "; summation threads: " + o.threads());
        }
        return out;
    }

    @Override
    public Health health() {
        java.time.Instant at = meter.snapshot().takenAt();
        if (lifecycle == LifecycleState.FAILED && failure != null) {
            return Health.down(at, failure);
        }
        return Health.of(lifecycle, at);
    }

    @Override
    public EngineStats stats() {
        return meter.snapshot();
    }

    @Override
    public synchronized void shutdown() {
        if (lifecycle == LifecycleState.SHUTDOWN) {
            return;
        }
        sums = null;                                                   // the executor is the host's: nothing of ours to stop
        lifecycle = LifecycleState.SHUTDOWN;
    }
}
