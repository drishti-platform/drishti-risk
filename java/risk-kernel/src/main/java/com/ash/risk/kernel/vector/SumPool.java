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
package com.ash.risk.kernel.vector;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Summation with an executor of its own, for code that runs outside a host (tests, benchmarks, a command line): daemon threads, stopped
 * when the sums are closed. Inside a host an engine sums on the host's executor ({@code new ParallelSums(context.executor(), ..)},
 * ENGINE_SPEC 8.5 rule 3) and never uses this class.
 */
public final class SumPool {

    private SumPool() {
    }

    /** Sums on a pool of {@code threads} daemon threads of their own, shut down by {@link ParallelSums#close}. */
    public static ParallelSums of(int threads, int parallelRows) {
        AtomicInteger n = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "drishti-vector-sum-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        return new ParallelSums(pool, threads, parallelRows, pool::shutdownNow);
    }
}
