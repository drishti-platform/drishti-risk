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
package com.ash.risk;

import com.ash.engine.Answer;
import com.ash.engine.Control;
import com.ash.engine.Inputs;
import com.ash.engine.Partial;
import com.ash.engine.Request;
import com.ash.risk.spi.RiskModule;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * The facade for an embedder and for {@code risk-cli} (design 4.1): an explicit list of modules (no ServiceLoader, no static
 * registry), the inputs, an absolute work folder, options; then a {@link RiskDay} per business date. A host never calls it: it finds the
 * product's two engines ({@code market-risk}, {@code ccr}) by ServiceLoader and drives them through the engine API, whose types this
 * facade uses throughout (answers, explanations, tables, partials). The kernel provides the implementation ({@code risk-kernel}).
 */
public interface RiskRuntime extends AutoCloseable {

    /** The modules this runtime was built with, in the order given. */
    List<RiskModule> modules();

    /** Loads a business date once; any number of readers may ask it at the same time. */
    RiskDay open(LocalDate date) throws RiskException;

    /** The answer from the partials of a request split by a shard unit (design 2.1 Partitions; engine API {@code Assembling}). */
    Answer assemble(Request request, List<Partial> partials, Control control) throws RiskException;

    /** Closes every open day and releases the work folder's files. */
    @Override
    void close();

    /** How a runtime is put together; every port but the work folder is optional. */
    interface Builder {

        Builder modules(RiskModule... modules);

        /** The rows the engine reads: the engine API's {@link Inputs} (from files, a lake, a host). */
        Builder inputs(java.util.function.Function<LocalDate, Inputs> inputsForDate);

        /** An absolute folder of this runtime's own. */
        Builder workDir(Path absolute);

        /** Business time; the only clock the product reads. */
        Builder clock(Clock clock);

        Builder options(RiskOptions options);

        RiskRuntime build() throws RiskException;
    }
}
