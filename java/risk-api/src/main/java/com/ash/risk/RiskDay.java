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

import com.ash.engine.Control;
import com.ash.engine.Partial;
import com.ash.engine.Request;
import com.ash.engine.Session;
import com.ash.engine.Shard;
import java.time.LocalDate;

/**
 * One business date, loaded once (design 4.1): an engine API {@link Session} whose snapshot is {@code { date }}. Asked through
 * {@link #measure} by an embedder, or through {@link Session#ask} by a host.
 */
public interface RiskDay extends Session {

    LocalDate date();

    /** A question about one measure ({@code var}, {@code es}, {@code exposure}, …) of a module this runtime holds. */
    MeasureQuery measure(String measure) throws RiskException;

    /** The additive aggregates of one shard of this date, for {@link RiskRuntime#assemble}. */
    Partial partial(Request request, Shard shard, Control control) throws RiskException;
}
