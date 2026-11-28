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
package com.ash.risk.ccr.engine;

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
import java.util.ArrayList;
import java.util.List;

/**
 * The counterparty-credit engine of Drishti Risk (product {@code drishti-risk-ccr}): exposure figures and profiles from supplied
 * simulator paths, netted by set and collateralised by CSA. A thin adapter over the moved CCR code ({@code com.ash.risk.ccr.calc}):
 * the exposure book, the collateral model and the quantile rules are Drishti's own classes, unchanged.
 *
 * <p>Lifecycle (ENGINE_SPEC 7.1): {@link #init} reads the options; {@link #start} makes the kernel state ready (the method
 * parameters); {@link #suspend} keeps every loaded day and refuses new work; {@link #resume} is cheap; {@link #shutdown} is idempotent.
 */
public final class CcrEngine implements Engine {

    static final String ID = "ccr";

    private volatile EngineContext context;
    private volatile CcrOptions options;
    private volatile com.ash.risk.ccr.calc.ExposureCalc.Params params;
    private volatile EngineMeter meter = EngineMeter.unstarted();
    private volatile LifecycleState lifecycle = LifecycleState.NEW;
    private volatile String failure;

    /** The jar's descriptor, read at the first call (ENGINE_SPEC 4.1: constant, at constant cost); every later call returns it. */
    private volatile Descriptor descriptor;

    @Override
    public Descriptor descriptor() {
        Descriptor d = descriptor;
        if (d == null) {
            d = Descriptors.of(CcrEngine.class, ID);
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
            this.options = CcrOptions.read(context.options());
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
        params = options.params();                                      // the kernel state: the method, once per instance
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
        CcrDay day = CcrDay.read(snapshot, inputs, control);
        meter.sessionOpened();
        return new CcrSession(this, snapshot, day);
    }

    /** Refuses work unless running: suspended in its own words (ENG-5023), any other state as a call out of order. */
    void running() throws EngineException {
        LifecycleState s = lifecycle;
        if (s == LifecycleState.SUSPENDED) {
            throw new EngineException("ENG-5023", Kind.REFUSED, "the ccr engine is suspended");
        }
        if (s != LifecycleState.RUNNING) {
            throw new EngineException("ENG-5020", Kind.REFUSED, "the ccr engine is " + s + ", not running");
        }
    }

    EngineContext context() {
        return context;
    }

    CcrOptions options() {
        return options;
    }

    com.ash.risk.ccr.calc.ExposureCalc.Params params() {
        return params;
    }

    EngineMeter meter() {
        return meter;
    }

    @Override
    public List<String> notes() {
        List<String> out = new ArrayList<>();
        CcrOptions o = options;
        if (o != null) {
            out.add("collateral model: " + o.model() + "; quantile rule: " + o.rule());
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
        params = null;
        lifecycle = LifecycleState.SHUTDOWN;
    }
}
